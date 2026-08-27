package com.relay.core.webrtc

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import android.util.Log
import com.relay.core.model.IceServerDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Agent 3 + 4 + 6 — the WebRTC media plane.
 *
 * A single audio-only PeerConnection carrying Opus at 48 kHz. Both apps use this
 * class; only the [AudioProfile] differs:
 *
 *  • **Gateway** captures from a telephony-adjacent source and plays the remote
 *    party's voice out toward the modem uplink.
 *  • **Client** captures from the handset mic (`VOICE_COMMUNICATION`, so hardware
 *    AEC/NS/AGC engage) and plays the far end through the earpiece or speaker.
 *
 * Opus tuning (docs/03-QA-OPTIMIZATION.md §Opus):
 *   `useinbandfec=1` — recover single packet losses without retransmission
 *   `usedtx=0`       — DTX off; comfort noise on a relayed call sounds broken
 *   `stereo=0`       — telephony is mono; halves the bitrate
 *   `maxaveragebitrate=32000` — transparent for speech, cheap on mobile data
 *   `ptime=20 / minptime=10`  — 20 ms frames, ~40 ms end-to-end algorithmic delay
 */
class WebRtcEngine(
    private val context: Context,
    private val profile: AudioProfile,
    private val callbacks: Callbacks,
) {

    /** Which capture/playback posture this endpoint uses. */
    enum class AudioProfile {
        /** Client handset: mic in, earpiece/speaker out. */
        HANDSET,

        /** Gateway: telephony capture in, uplink-directed playback out. */
        TELEPHONY_BRIDGE,
    }

    interface Callbacks {
        fun onLocalDescription(type: String, sdp: String)
        fun onIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onRemoteAudioTrack(track: AudioTrack)
        fun onStats(stats: CallQuality)
        fun onError(message: String, cause: Throwable?)
    }

    data class CallQuality(
        val rttMs: Int = 0,
        val jitterMs: Int = 0,
        val lossPct: Double = 0.0,
        val bitrateKbps: Int = 0,
        val codec: String = "opus/48000",
        val audioLevel: Double = 0.0,
        val candidatePairType: String = "",
    )

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val released = AtomicBoolean(false)

    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: AudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var statsJob: Job? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphone = false

    private val _quality = MutableStateFlow(CallQuality())
    val quality: StateFlow<CallQuality> = _quality.asStateFlow()

    /** Which capture source actually succeeded — reported to the client UI. */
    @Volatile var activeCaptureSource: String = "none"
        private set

    // For bitrate deltas between stats polls.
    private var lastBytesReceived = 0L
    private var lastStatsAt = 0L

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Build the factory once per process-lifetime of a call.
     *
     * @param preferredSources ordered capture-source candidates for the gateway.
     *        The first one the platform actually grants wins. See
     *        `CallAudioBridge` for how the gateway picks this list.
     */
    fun initialize(preferredSources: List<Int> = defaultSources()) {
        if (factory != null) return

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setFieldTrials(FIELD_TRIALS)
                .setEnableInternalTracer(false)
                .createInitializationOptions(),
        )

        val adm = buildAudioDeviceModule(preferredSources)
        audioDeviceModule = adm

        val options = PeerConnectionFactory.Options().apply {
            // Audio-only: no need to enumerate network interfaces we cannot use.
            disableNetworkMonitor = false
        }

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            // Video factories are required by the builder but never exercised.
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(null, false, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(null))
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory ready (profile=$profile, capture=$activeCaptureSource)")
    }

    private fun defaultSources(): List<Int> = when (profile) {
        AudioProfile.HANDSET -> listOf(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        AudioProfile.TELEPHONY_BRIDGE -> listOf(
            // Tried in order; see docs/03-QA-OPTIMIZATION.md "capture reality matrix".
            MediaRecorder.AudioSource.VOICE_CALL,          // both legs, privileged
            MediaRecorder.AudioSource.VOICE_DOWNLINK,      // far end only, privileged
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // speakerphone loopback
            MediaRecorder.AudioSource.MIC,                 // last-resort loopback
        )
    }

    /**
     * Construct the ADM, probing capture sources in order.
     *
     * `JavaAudioDeviceModule` validates the source lazily, so we build one per
     * candidate and keep the first that initialises its AudioRecord without
     * throwing. This is the honest way to discover what the firmware permits
     * rather than assuming `VOICE_CALL` works and shipping silence.
     */
    private fun buildAudioDeviceModule(sources: List<Int>): AudioDeviceModule {
        var lastError: Throwable? = null

        for (source in sources) {
            try {
                val adm = JavaAudioDeviceModule.builder(context)
                    .setAudioSource(source)
                    .setAudioFormat(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    // Hardware AEC and NS are switched off, which is the
                    // opposite of the obvious choice.
                    //
                    // On a loopback bridge the sound we are trying to record is
                    // the far end's voice coming out of this phone's own
                    // loudspeaker. The hardware canceller's reference is the
                    // device's playback mix, and on most HALs that mix includes
                    // the cellular downlink — so the chip removes precisely the
                    // signal the bridge exists to carry, and the call is silent
                    // while every indicator says it is working. Hardware NS is
                    // no better: speaker-relayed speech is exactly the kind of
                    // "not a person talking into this mic" signal it suppresses.
                    //
                    // WebRTC's own AEC3 still runs, and its reference is only
                    // our WebRTC playback, which is the correct thing to cancel.
                    .setUseHardwareAcousticEchoCanceler(false)
                    .setUseHardwareNoiseSuppressor(false)
                    .setAudioAttributes(playbackAttributes())
                    .setUseStereoInput(false)
                    .setUseStereoOutput(false)
                    .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                        override fun onWebRtcAudioRecordInitError(msg: String) {
                            callbacks.onError("AudioRecord init: $msg", null)
                        }
                        override fun onWebRtcAudioRecordStartError(
                            code: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                            msg: String,
                        ) = callbacks.onError("AudioRecord start ($code): $msg", null)
                        override fun onWebRtcAudioRecordError(msg: String) =
                            callbacks.onError("AudioRecord: $msg", null)
                    })
                    .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                        override fun onWebRtcAudioTrackInitError(msg: String) =
                            callbacks.onError("AudioTrack init: $msg", null)
                        override fun onWebRtcAudioTrackStartError(
                            code: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                            msg: String,
                        ) = callbacks.onError("AudioTrack start ($code): $msg", null)
                        override fun onWebRtcAudioTrackError(msg: String) =
                            callbacks.onError("AudioTrack: $msg", null)
                    })
                    .createAudioDeviceModule()

                activeCaptureSource = sourceName(source)
                Log.i(TAG, "audio capture source accepted: $activeCaptureSource")
                return adm
            } catch (t: Throwable) {
                lastError = t
                Log.w(TAG, "capture source ${sourceName(source)} rejected: ${t.message}")
            }
        }

        callbacks.onError("No usable audio capture source", lastError)
        activeCaptureSource = "unavailable"
        // Fall back to a plain default ADM so the call at least completes one-way.
        return JavaAudioDeviceModule.builder(context)
            .setSampleRate(SAMPLE_RATE_HZ)
            .createAudioDeviceModule()
    }

    private fun playbackAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Call setup
    // ─────────────────────────────────────────────────────────────────────────

    fun createPeerConnection(iceServers: List<IceServerDto>, forceRelay: Boolean = false) {
        val f = factory ?: run {
            callbacks.onError("initialize() must be called first", null)
            return
        }

        val rtcIceServers = iceServers.map { dto ->
            PeerConnection.IceServer.builder(dto.urls)
                .setUsername(dto.username)
                .setPassword(dto.credential)
                .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE)
                .createIceServer()
        }

        val config = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType =
                if (forceRelay) PeerConnection.IceTransportsType.RELAY
                else PeerConnection.IceTransportsType.ALL
            // Trickle ICE end-to-end; the signaling channel is already low-latency.
            keyType = PeerConnection.KeyType.ECDSA
            // `enableDtlsSrtp` used to live here. Current libwebrtc removed the
            // flag because DTLS-SRTP is no longer optional — SDES was dropped,
            // so media is always encrypted and there is nothing left to toggle.
            // Aggressive but not abusive on a mobile radio.
            iceConnectionReceivingTimeout = 8_000
            iceBackupCandidatePairPingInterval = 4_000
        }

        peerConnection = f.createPeerConnection(config, PcObserver())
            ?: run { callbacks.onError("createPeerConnection returned null", null); return }

        attachLocalAudio(f)
        startStatsLoop()
        applyAudioRouting()
    }

    private fun attachLocalAudio(f: PeerConnectionFactory) {
        val constraints = MediaConstraints().apply {
            // Software processing. On the gateway's VOICE_CALL path we disable
            // AEC/NS because the modem has already done it — doubling up here
            // produces the classic "underwater" artefact.
            val telephony = profile == AudioProfile.TELEPHONY_BRIDGE &&
                activeCaptureSource.startsWith("VOICE_CALL")

            mandatory.add(pair("googEchoCancellation", !telephony))
            mandatory.add(pair("googAutoGainControl", !telephony))
            mandatory.add(pair("googNoiseSuppression", !telephony))
            mandatory.add(pair("googHighpassFilter", true))
            optional.add(pair("googTypingNoiseDetection", false))
        }

        localAudioSource = f.createAudioSource(constraints)
        localAudioTrack = f.createAudioTrack(LOCAL_TRACK_ID, localAudioSource).apply {
            setEnabled(true)
        }

        peerConnection?.addTransceiver(
            localAudioTrack,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                listOf(STREAM_ID),
            ),
        )
    }

    private fun pair(key: String, value: Boolean) =
        MediaConstraints.KeyValuePair(key, value.toString())

    // ── Offer / answer ───────────────────────────────────────────────────────

    /** Gateway is always the offerer — see docs/01-ARCHITECTURE.md §3.3. */
    fun createOffer(iceRestart: Boolean = false) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(pair("OfferToReceiveAudio", true))
            mandatory.add(pair("OfferToReceiveVideo", false))
            if (iceRestart) mandatory.add(pair("IceRestart", true))
        }
        pc.createOffer(object : SimpleSdpObserver("createOffer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                val tuned = SessionDescription(desc.type, tuneOpus(desc.description))
                pc.setLocalDescription(SimpleSdpObserver("setLocal(offer)"), tuned)
                callbacks.onLocalDescription(tuned.type.canonicalForm(), tuned.description)
            }
        }, constraints)
    }

    fun createAnswer() {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(pair("OfferToReceiveAudio", true))
            mandatory.add(pair("OfferToReceiveVideo", false))
        }
        pc.createAnswer(object : SimpleSdpObserver("createAnswer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                val tuned = SessionDescription(desc.type, tuneOpus(desc.description))
                pc.setLocalDescription(SimpleSdpObserver("setLocal(answer)"), tuned)
                callbacks.onLocalDescription(tuned.type.canonicalForm(), tuned.description)
            }
        }, constraints)
    }

    fun setRemoteDescription(type: String, sdp: String) {
        val pc = peerConnection ?: return
        val sdpType = when (type.lowercase()) {
            "offer" -> SessionDescription.Type.OFFER
            "answer" -> SessionDescription.Type.ANSWER
            "pranswer" -> SessionDescription.Type.PRANSWER
            else -> { callbacks.onError("Unknown SDP type '$type'", null); return }
        }
        pc.setRemoteDescription(
            SimpleSdpObserver("setRemote($type)"),
            SessionDescription(sdpType, sdp),
        )
    }

    fun addIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        peerConnection?.addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    // ── Runtime controls ─────────────────────────────────────────────────────

    fun setMicrophoneMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun setRemoteAudioMuted(muted: Boolean) {
        remoteAudioTrack?.setEnabled(!muted)
    }

    fun setSpeakerphone(on: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = on
        }
    }

    /**
     * Route audio for a live call.
     *
     * `MODE_IN_COMMUNICATION` is what makes the platform hand us the hardware
     * AEC reference signal and pick the voice-optimised output path. On the
     * gateway this also places our playback on the same stream the modem
     * monitors when speakerphone loopback is the active bridge strategy.
     */
    private fun applyAudioRouting() {
        savedAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        savedSpeakerphone = audioManager.isSpeakerphoneOn

        runCatching {
            when (profile) {
                AudioProfile.HANDSET -> {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    setSpeakerphone(false)   // earpiece by default
                }

                AudioProfile.TELEPHONY_BRIDGE -> {
                    // Two bugs used to live in these three lines, and together
                    // they silenced every bridged call.
                    //
                    // 1. The privileged test was `startsWith("VOICE_")`, which
                    //    is also true of "VOICE_COMMUNICATION" — the loopback
                    //    source, and the only one an unprivileged install can
                    //    ever get. So on every real device the branch that
                    //    turns the speaker ON was replaced by one that turned
                    //    it OFF. Match the two privileged sources by name.
                    //
                    // 2. This method runs *after* CallAudioBridge.prepareRouting
                    //    has set MODE_IN_CALL, and unconditionally overwrote it
                    //    with MODE_IN_COMMUNICATION. For loopback the modem
                    //    uplink only carries what the loudspeaker plays while
                    //    the platform is in MODE_IN_CALL, so that overwrite
                    //    broke the one path the audio has.
                    //
                    // With no privileged capture, the bridge is acoustic: the
                    // far end is heard through the loudspeaker and re-captured,
                    // and the receiver's voice is played out of that same
                    // speaker for the modem's mic to pick up. That demands
                    // speakerphone ON and MODE_IN_CALL — leave both alone.
                    val privilegedCapture =
                        activeCaptureSource == "VOICE_CALL" ||
                            activeCaptureSource == "VOICE_DOWNLINK"

                    if (privilegedCapture) {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                        setSpeakerphone(false)
                    } else {
                        setSpeakerphone(true)
                    }
                }
            }
        }
    }

    private fun restoreAudioRouting() {
        runCatching {
            audioManager.mode = savedAudioMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = savedSpeakerphone
        }
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                val pc = peerConnection ?: break
                pc.getStats { report -> _quality.value = parseStats(report).also(callbacks::onStats) }
            }
        }
    }

    private fun parseStats(report: RTCStatsReport): CallQuality {
        var rttMs = 0
        var jitterMs = 0
        var lossPct = 0.0
        var bitrateKbps = 0
        var codec = "opus/48000"
        var audioLevel = 0.0
        var pairType = ""

        val now = System.currentTimeMillis()

        for (stat in report.statsMap.values) {
            when (stat.type) {
                "inbound-rtp" -> {
                    if (stat.members["kind"] != "audio") continue
                    (stat.members["jitter"] as? Double)?.let { jitterMs = (it * 1000).roundToInt() }
                    (stat.members["audioLevel"] as? Double)?.let { audioLevel = it }

                    val received = (stat.members["packetsReceived"] as? Long) ?: 0L
                    val lost = (stat.members["packetsLost"] as? Int)?.toLong() ?: 0L
                    if (received + lost > 0) lossPct = lost * 100.0 / (received + lost)

                    val bytes = (stat.members["bytesReceived"] as? Long) ?: 0L
                    if (lastStatsAt > 0 && bytes > lastBytesReceived) {
                        val deltaSec = (now - lastStatsAt) / 1000.0
                        if (deltaSec > 0) {
                            bitrateKbps = (((bytes - lastBytesReceived) * 8) / deltaSec / 1000).roundToInt()
                        }
                    }
                    lastBytesReceived = bytes
                }
                "candidate-pair" -> {
                    if (stat.members["state"] != "succeeded") continue
                    (stat.members["currentRoundTripTime"] as? Double)
                        ?.let { rttMs = (it * 1000).roundToInt() }
                }
                "local-candidate" -> {
                    (stat.members["candidateType"] as? String)?.let { pairType = it }
                }
                "codec" -> {
                    val mime = stat.members["mimeType"] as? String
                    val rate = stat.members["clockRate"] as? Long
                    if (mime?.contains("opus", true) == true) codec = "${mime.substringAfter('/')}/${rate ?: 48000}"
                }
            }
        }
        lastStatsAt = now

        return CallQuality(rttMs, jitterMs, lossPct, bitrateKbps, codec, audioLevel, pairType)
    }

    // ── Teardown ─────────────────────────────────────────────────────────────

    fun close() {
        if (!released.compareAndSet(false, true)) return
        statsJob?.cancel()
        restoreAudioRouting()

        runCatching { peerConnection?.close(); peerConnection?.dispose() }
        runCatching { localAudioTrack?.dispose() }
        runCatching { localAudioSource?.dispose() }
        peerConnection = null
        localAudioTrack = null
        localAudioSource = null
        remoteAudioTrack = null

        runCatching { factory?.dispose() }
        runCatching { audioDeviceModule?.release() }
        factory = null
        audioDeviceModule = null

        scope.coroutineContext[Job]?.cancel()
        Log.i(TAG, "engine released")
    }

    // ─────────────────────────────────────────────────────────────────────────

    private inner class PcObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            callbacks.onIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "connection state → $newState")
            callbacks.onConnectionStateChanged(newState)
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver?.track()
            if (track is AudioTrack) {
                remoteAudioTrack = track.apply { setEnabled(true) }
                callbacks.onRemoteAudioTrack(track)
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
            (receiver.track() as? AudioTrack)?.let {
                remoteAudioTrack = it.apply { setEnabled(true) }
                callbacks.onRemoteAudioTrack(it)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d(TAG, "ice connection → $state")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent) {
            Log.d(TAG, "candidate pair changed: ${event.reason}")
        }
    }

    private open inner class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit

        // Braces, not `=`. `Log.d` returns an Int, and an expression body would
        // make this override return Int where the interface declares Unit.
        override fun onSetSuccess() {
            Log.d(TAG, "$tag ok")
        }

        override fun onCreateFailure(error: String) = callbacks.onError("$tag failed: $error", null)
        override fun onSetFailure(error: String) = callbacks.onError("$tag failed: $error", null)
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        private const val LOCAL_TRACK_ID = "relay_audio_local"
        private const val STREAM_ID = "relay_stream"
        private const val SAMPLE_RATE_HZ = 48_000
        private const val STATS_INTERVAL_MS = 1_000L

        /**
         * Field trials.
         *  • `WebRTC-Audio-Allocation` pins the encoder's bitrate envelope so the
         *    bandwidth estimator cannot starve speech during congestion.
         *  • `WebRTC-Audio-NetEqDecelerationTargetLevelOffset` trims jitter-buffer
         *    latency; 85 ms is a good compromise for a relayed cellular leg.
         */
        private const val FIELD_TRIALS =
            "WebRTC-Audio-Allocation/min:16000bps,max:40000bps/" +
                "WebRTC-Audio-NetEqDecelerationTargetLevelOffset/Enabled-85/" +
                "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/"

        /**
         * Rewrite the Opus fmtp line and force Opus to the top of the m-line.
         *
         * libwebrtc offers Opus first by default, but Samsung firmware has been
         * observed reordering payload types after a renegotiation, so this makes
         * the preference explicit and idempotent.
         */
        fun tuneOpus(sdp: String): String {
            val lines = sdp.split("\r\n").toMutableList()

            // 1. Find Opus payload type.
            val opusPt = lines
                .firstOrNull { it.startsWith("a=rtpmap:") && it.contains("opus/48000", true) }
                ?.substringAfter("a=rtpmap:")
                ?.substringBefore(' ')
                ?: return sdp

            // 2. Replace or insert the fmtp line.
            val fmtpIndex = lines.indexOfFirst { it.startsWith("a=fmtp:$opusPt") }
            if (fmtpIndex >= 0) {
                lines[fmtpIndex] = "a=fmtp:$opusPt $OPUS_FMTP"
            } else {
                val rtpmapIndex = lines.indexOfFirst { it.startsWith("a=rtpmap:$opusPt") }
                if (rtpmapIndex >= 0) lines.add(rtpmapIndex + 1, "a=fmtp:$opusPt $OPUS_FMTP")
            }

            // 3. Ensure ptime attributes exist exactly once.
            lines.removeAll { it.startsWith("a=ptime:") || it.startsWith("a=maxptime:") }
            val audioMIndex = lines.indexOfFirst { it.startsWith("m=audio") }
            if (audioMIndex >= 0) {
                lines.add(audioMIndex + 1, "a=maxptime:60")
                lines.add(audioMIndex + 1, "a=ptime:20")

                // 4. Promote Opus to the front of the payload list.
                val parts = lines[audioMIndex].split(' ').toMutableList()
                if (parts.size > 3 && parts.remove(opusPt)) {
                    parts.add(3, opusPt)
                    lines[audioMIndex] = parts.joinToString(" ")
                }
            }

            return lines.joinToString("\r\n")
        }

        private const val OPUS_FMTP =
            "minptime=10;useinbandfec=1;usedtx=0;stereo=0;sprop-stereo=0;" +
                "maxaveragebitrate=32000;maxplaybackrate=48000;cbr=0"

        fun sourceName(source: Int): String = when (source) {
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_DOWNLINK -> "VOICE_DOWNLINK"
            MediaRecorder.AudioSource.VOICE_UPLINK -> "VOICE_UPLINK"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            else -> "SOURCE_$source"
        }
    }
}

/** Convenience: is this track live and unmuted? */
val MediaStreamTrack.isLive: Boolean
    get() = state() == MediaStreamTrack.State.LIVE && enabled()
