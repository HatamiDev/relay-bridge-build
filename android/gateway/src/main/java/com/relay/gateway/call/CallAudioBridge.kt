package com.relay.gateway.call

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService

/**
 * Agent 3 + Agent 6 — the honest audio-capture strategy resolver.
 *
 * ## Why this class exists
 *
 * Android does **not** give ordinary apps the cellular voice-call audio stream.
 * `MediaRecorder.AudioSource.VOICE_CALL`, `VOICE_DOWNLINK` and `VOICE_UPLINK`
 * are gated behind `android.permission.CAPTURE_AUDIO_OUTPUT`, which carries
 * `protectionLevel="signature|privileged"`. A normally-installed APK cannot hold
 * it. Samsung additionally blocks these sources at the HAL on most retail
 * firmware even for privileged callers.
 *
 * Code that simply calls `setAudioSource(VOICE_CALL)` and hopes therefore ships
 * silence on the majority of devices. This resolver instead probes what the
 * device will actually grant and reports the answer, so the client UI can show
 * the user the truth ("Loopback mode — keep the gateway handset face-down").
 *
 * ## Strategies, best to worst
 *
 * | Strategy | Requires | Quality | Both legs? |
 * |---|---|---|---|
 * | `VOICE_CALL` | privileged/system install, or Knox-enabled MDM build | excellent | yes |
 * | `VOICE_DOWNLINK` | same | excellent | far end only |
 * | `VOICE_COMMUNICATION` + speakerphone | ordinary install | fair | yes, acoustically |
 * | `MIC` + speakerphone | ordinary install | poor | yes, acoustically |
 *
 * The loopback strategies work because the gateway puts the cellular call on
 * speakerphone: the far end's voice comes out of the loudspeaker, our AudioRecord
 * picks it up, and our WebRTC playback goes back out of the same speaker where
 * the modem's uplink mic captures it. Crude, but it is the only thing that
 * functions on a stock retail device — and hardware AEC keeps it from howling.
 */
class CallAudioBridge(private val context: Context) {

    enum class Strategy(val source: Int, val label: String, val privileged: Boolean) {
        VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL, "Telephony (both legs)", true),
        VOICE_DOWNLINK(MediaRecorder.AudioSource.VOICE_DOWNLINK, "Telephony (far end)", true),
        // MIC before VOICE_COMMUNICATION, which is the reverse of the usual
        // preference. VOICE_COMMUNICATION asks the platform for a processed
        // call-quality stream, and during a live cellular call that processing
        // — AEC keyed on the device's own playback, plus aggressive noise
        // gating — strips out speaker-relayed speech, which is the only thing
        // this capture is for. MIC is the raw path and the one that actually
        // carries the far end's voice.
        LOOPBACK_MIC(MediaRecorder.AudioSource.MIC, "Microphone loopback", false),
        LOOPBACK_COMM(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "Speakerphone loopback",
            false,
        ),
    }

    private val audioManager = context.getSystemService<AudioManager>()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether WebRTC should cancel our own playback out of what it captures.
     *
     * A setting rather than a constant because the right answer depends on a
     * device's echo canceller, which cannot be measured from here. On an
     * acoustic loopback the wanted signal — the far end out of the loudspeaker
     * — and the cancelled one arrive together, and an over-eager AEC removes
     * both. Turning it off makes the far end audible at the cost of the other
     * party hearing themselves, which on some handsets is the only way to have
     * a call at all.
     */
    var echoCancellation: Boolean
        get() = prefs.getBoolean(KEY_AEC, true)
        set(value) = prefs.edit().putBoolean(KEY_AEC, value).apply()

    /**
     * Ordered list of sources to hand to `WebRtcEngine.initialize`.
     *
     * Privileged strategies are only offered when the app actually holds
     * `CAPTURE_AUDIO_OUTPUT`; otherwise probing them wastes ~200 ms per call
     * setup on an AudioRecord that is guaranteed to fail.
     */
    fun preferredSources(): List<Int> = availableStrategies().map { it.source }

    fun availableStrategies(): List<Strategy> {
        val privileged = hasCaptureAudioOutput()
        return Strategy.entries
            .filter { !it.privileged || privileged }
            .also {
                Log.i(
                    TAG,
                    "capture strategies: ${it.joinToString { s -> s.name }} " +
                        "(privileged=$privileged)",
                )
            }
    }

    /**
     * True when this build was installed with system/privileged privileges and
     * therefore may open the telephony audio sources.
     */
    fun hasCaptureAudioOutput(): Boolean =
        context.checkSelfPermission(CAPTURE_AUDIO_OUTPUT) == PackageManager.PERMISSION_GRANTED

    /** True when the OS reports another app already owns the capture path. */
    fun isCaptureContested(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        audioManager?.activeRecordingConfigurations?.any {
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                it.clientAudioSource == MediaRecorder.AudioSource.VOICE_CALL
        } ?: false
    }.getOrDefault(false)

    /**
     * Prepare the device's audio policy for a bridged call.
     *
     * @param strategy the source that [com.relay.core.webrtc.WebRtcEngine]
     *        actually managed to open
     */
    fun prepareRouting(strategy: Strategy) {
        val am = audioManager ?: return

        // Mode first, on its own, and failure is fine.
        //
        // This used to be the first statement inside a single runCatching that
        // also held the speakerphone setup — and `setMode(MODE_IN_CALL)` needs
        // MODIFY_PHONE_STATE, which is signature|privileged. So on every
        // ordinary install the very first line threw, the catch swallowed it,
        // and the speakerphone code below it never ran at all. The loudspeaker
        // stayed off, the far end's voice never reached the microphone, and the
        // bridge carried silence in both directions while confidently
        // reporting "Speakerphone loopback".
        //
        // During a real cellular call the telephony stack owns the mode anyway,
        // so not setting it is the correct behaviour, not a compromise.
        runCatching { am.mode = AudioManager.MODE_IN_CALL }
            .onFailure { Log.d(TAG, "audio mode is owned by telephony, as expected") }

        when (strategy) {
            Strategy.VOICE_CALL, Strategy.VOICE_DOWNLINK -> {
                // Privileged tap: no acoustic path needed, keep the earpiece so
                // a person standing next to the gateway hears nothing.
                setSpeakerphone(false)
            }
            Strategy.LOOPBACK_COMM, Strategy.LOOPBACK_MIC -> {
                // A wired headset left plugged into the gateway is a better
                // acoustic bridge than the loudspeaker and a far better
                // neighbour: the earpiece and the headset's inline microphone
                // sit a few centimetres apart, so the coupling is tighter than
                // across a room, and nothing is audible to anyone standing near
                // the phone. Forcing the speaker in that case would be actively
                // worse — louder, noisier, and broadcasting a private call into
                // the room the handset happens to live in.
                if (hasWiredHeadset()) {
                    Log.i(TAG, "wired headset present — leaving the route alone")
                    setCallVolume(0.5f)
                    return
                }
                setSpeakerphone(true)
                // Push the loudspeaker up so the far end is clearly captured,
                // but not to max — clipping destroys the AEC reference.
                setCallVolume(0.9f)
            }
        }
    }

    /**
     * Route call audio to the loudspeaker, or away from it.
     *
     * `isSpeakerphoneOn` was deprecated in API 31 and is ignored on many builds
     * from 33 onwards; `setCommunicationDevice` is its replacement and the only
     * one that actually moves the audio on a current Samsung. Both are attempted
     * — the new API first, the old one as a fallback — because which of them
     * works varies by OEM even within one API level, and the acoustic loopback
     * is worthless if the speaker never comes on.
     */
    private fun setSpeakerphone(on: Boolean) {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val moved = runCatching {
                if (!on) {
                    am.clearCommunicationDevice()
                    true
                } else {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    speaker != null && am.setCommunicationDevice(speaker)
                }
            }.getOrElse {
                Log.w(TAG, "setCommunicationDevice failed", it)
                false
            }
            if (moved) {
                Log.i(TAG, "speakerphone ${if (on) "on" else "off"} via communication device")
                return
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
            Log.i(TAG, "speakerphone ${if (on) "on" else "off"} via legacy flag")
        }.onFailure { Log.w(TAG, "speakerphone toggle failed", it) }
    }

    /**
     * Is the loudspeaker actually on?
     *
     * Reported to the receiver so a silent call has a visible cause. On builds
     * where neither routing API is honoured for a call this app does not own,
     * the answer is no, and the only fix is to press the speaker button on the
     * gateway handset — which is worth telling the user rather than leaving
     * them with a dead line.
     */
    fun isSpeakerphoneActive(): Boolean = runCatching {
        val am = audioManager ?: return false
        // A headset is a valid — in fact preferred — acoustic path, so it
        // counts as "the route is fine" and must not raise the speaker warning.
        if (hasWiredHeadset()) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn
        }
    }.getOrDefault(false)

    private fun setCallVolume(fraction: Float) {
        val am = audioManager ?: return
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            am.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                (max * fraction).toInt().coerceAtLeast(1),
                0,
            )
        }.onFailure { Log.w(TAG, "could not set call volume", it) }
    }

    /**
     * Is anything plugged in — wired headset, USB audio, or a Bluetooth
     * headset — that already carries call audio away from the loudspeaker?
     */
    fun hasWiredHeadset(): Boolean = runCatching {
        val am = audioManager ?: return false
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
    }.getOrDefault(false)

    /** Advice text surfaced on the client so the user knows what to expect. */
    fun advisoryFor(strategy: Strategy?): String = when (strategy) {
        Strategy.VOICE_CALL -> "Direct telephony tap — full duplex, HD."
        Strategy.VOICE_DOWNLINK ->
            "Telephony tap (far end only). Your voice reaches them through the " +
                "gateway's microphone, so keep it in a quiet place."
        Strategy.LOOPBACK_COMM, Strategy.LOOPBACK_MIC ->
            "Acoustic loopback — this device's firmware does not expose the call " +
                "stream. Plug a wired headset into the sender and rest the " +
                "earpiece against its inline microphone: the call stays private " +
                "and the coupling is far cleaner than across a room. Without " +
                "one, the loudspeaker is used and the room is audible to both " +
                "parties."
        null -> "No audio capture path available on this device."
    }

    private companion object {
        const val TAG = "CallAudioBridge"
        const val PREFS = "relay_audio"
        const val KEY_AEC = "echo_cancellation"
        const val CAPTURE_AUDIO_OUTPUT = "android.permission.CAPTURE_AUDIO_OUTPUT"
    }
}
