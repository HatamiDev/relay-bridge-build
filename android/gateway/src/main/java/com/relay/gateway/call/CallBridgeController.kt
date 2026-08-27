package com.relay.gateway.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.getSystemService
import com.relay.core.model.CallIncoming
import com.relay.core.model.CallState
import com.relay.core.model.CallStateUpdate
import com.relay.core.model.Ev
import com.relay.core.model.IceServerDto
import com.relay.core.model.RtcIce
import com.relay.core.model.RtcSdp
import com.relay.core.net.SignalingClient
import com.relay.core.webrtc.WebRtcEngine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.AudioTrack
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Agent 3 — the call state machine that glues Telecom to WebRTC.
 *
 * Lives inside the foreground service so a PeerConnection outlives Telecom
 * binding and unbinding [RelayInCallService]. One instance, one active bridged
 * call — relaying two simultaneous calls would require two capture paths, which
 * no Android device provides.
 *
 * The gateway is always the WebRTC offerer, so there is no glare to resolve.
 *
 * With several receivers able to ring for the same cellular call, exactly one
 * of them wins the bridge: whichever `call:answer` arrives first. [answeringDeviceId]
 * records the winner; every other receiver is told the call ended
 * (`cause = "answered_elsewhere"`) and any later control message from a
 * non-winning device is silently dropped.
 */
class CallBridgeController(
    private val context: Context,
    /** [targetDeviceId] null means broadcast to every paired receiver. */
    private val emit: (event: String, plaintextJson: String, targetDeviceId: String?) -> Unit,
    private val onStateChanged: () -> Unit,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val audioBridge = CallAudioBridge(context)
    private val telecom = context.getSystemService<TelecomManager>()

    private var signaling: SignalingClient? = null
    private var iceServers: List<IceServerDto> = emptyList()

    private var engine: WebRtcEngine? = null
    private var telecomCall: Call? = null
    private var callId: String = ""
    private var state: CallState = CallState.IDLE
    private var activeStrategy: CallAudioBridge.Strategy? = null

    /** The receiver that won the answer race for [callId]. Empty until answered. */
    private var answeringDeviceId: String = ""

    /**
     * Set by [placeCall], consumed by [onTelecomCallAdded].
     *
     * Telecom gives no way to correlate the call it surfaces with the
     * `placeCall` that caused it, so the id and owner are held here across
     * that gap. Cleared as soon as they are adopted, and again on teardown, so
     * a later inbound call can never inherit them.
     */
    private var pendingOutgoingCallId: String = ""
    private var pendingOutgoingDeviceId: String = ""

    /** Guards [announceRinging] against announcing the same call twice. */
    private var announcedRinging = false

    /**
     * True when this call is being driven by [TelephonyCallWatcher] rather than
     * by a Telecom `Call`. Recorded so state reported to the receiver can say
     * which path is live when something goes wrong.
     */
    private var telephonyDriven = false

    /** ICE candidates that arrive before the remote description is applied. */
    private val pendingRemoteIce = ConcurrentLinkedQueue<RtcIce>()
    private var remoteDescriptionSet = false

    init { current = this }

    fun attachSignaling(client: SignalingClient) { signaling = client }
    fun onIceServers(servers: List<IceServerDto>) {
        if (servers.isNotEmpty()) iceServers = servers
    }

    fun activeCallSummary(): String? = when (state) {
        CallState.IDLE, CallState.ENDED -> null
        CallState.RINGING -> "Incoming call — ringing on client"
        CallState.DIALING, CallState.CONNECTING -> "Connecting call…"
        CallState.ACTIVE -> {
            val path = if (telephonyDriven) "telephony" else "telecom"
            "Call bridged · ${activeStrategy?.label ?: "audio"} · $path"
        }
        CallState.HELD -> "Call on hold"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Telecom → relay
    // ─────────────────────────────────────────────────────────────────────────

    fun onTelecomCallAdded(call: Call) {
        // Never relay an emergency call — the user must speak on the handset
        // that is actually placing it, and interfering is dangerous.
        if (isEmergency(call)) {
            Log.w(TAG, "emergency call detected — bridge stands down")
            return
        }
        if (telecomCall != null && telecomCall !== call) {
            Log.w(TAG, "second concurrent call ignored; one bridge at a time")
            return
        }

        telecomCall = call

        // Adopt the id the receiver already minted, if this is the call it just
        // asked us to place.
        //
        // Unconditionally generating a fresh UUID here broke every outgoing
        // call: the receiver kept its own id, the gateway switched to a new
        // one, and from then on the two disagreed about what the call was
        // called. `hangup()` compares ids and silently returns on a mismatch,
        // so the receiver could end its own screen while the cellular call
        // stayed up. DTMF, mute and every state update were dropped for the
        // same reason, which is why an outgoing call never left "CALLING…".
        if (pendingOutgoingCallId.isNotEmpty()) {
            callId = pendingOutgoingCallId
            answeringDeviceId = pendingOutgoingDeviceId
            pendingOutgoingCallId = ""
            pendingOutgoingDeviceId = ""
        } else {
            callId = UUID.randomUUID().toString()
            answeringDeviceId = ""
        }
        announcedRinging = false
        remoteDescriptionSet = false
        pendingRemoteIce.clear()

        when (call.state) {
            // SIMULATED_RINGING is what Samsung's Telecom reports while it
            // plays its own ringtone; treating it as "not ringing" is why the
            // receiver stayed silent on exactly the handset this bridge runs on.
            Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> announceRinging(call)
            Call.STATE_DIALING, Call.STATE_CONNECTING -> {
                state = CallState.DIALING
                pushState(CallState.DIALING)
            }
            Call.STATE_ACTIVE -> {
                // Call was already up (e.g. we bound mid-call after a restart).
                startBridge()
            }
        }
        onStateChanged()
    }

    /**
     * Tell every receiver a call is ringing. Safe to call more than once.
     *
     * Split out of [onTelecomCallAdded] because Telecom frequently hands us a
     * call in STATE_NEW — and on Samsung also STATE_SIMULATED_RINGING — and
     * only moves it to STATE_RINGING a moment later, through
     * [onTelecomStateChanged]. `onCallAdded` never fires twice for one call, so
     * announcing only from there meant that on the common path the gateway rang
     * and the receiver was never told anything at all.
     */
    private fun announceRinging(call: Call) =
        announceRinging(handleOf(call), call.details?.callerDisplayName.orEmpty())

    private fun announceRinging(number: String, displayName: String) {
        if (announcedRinging) return
        announcedRinging = true

        state = CallState.RINGING
        // Broadcast: nobody has answered yet, so every receiver rings.
        emit(
            Ev.CALL_INCOMING,
            json.encodeToString(
                CallIncoming(
                    callId = callId,
                    from = number.ifEmpty { "unknown" },
                    displayName = displayName,
                ),
            ),
            null,
        )
        pushState(CallState.RINGING)
    }

    fun onTelecomStateChanged(call: Call, newState: Int) {
        if (call !== telecomCall) return
        when (newState) {
            // The branch that was missing. Without it, a call added in
            // STATE_NEW never reached any receiver.
            Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> announceRinging(call)
            Call.STATE_ACTIVE -> startBridge()
            Call.STATE_HOLDING -> { state = CallState.HELD; pushState(CallState.HELD) }
            Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> {
                val cause = call.details?.disconnectCause?.reason.orEmpty()
                teardown(cause.ifEmpty { "remote_hangup" })
            }
            Call.STATE_DIALING -> { state = CallState.DIALING; pushState(CallState.DIALING) }
        }
        onStateChanged()
    }

    fun onTelecomCallRemoved(call: Call) {
        if (call === telecomCall) teardown("call_removed")
    }

    fun onTelecomDetailsChanged(call: Call, details: Call.Details) {
        // Nothing to relay today; hook exists for CDMA/VoLTE detail changes.
        Log.d(TAG, "details changed: caps=${details.callCapabilities}")
    }

    fun onCallAudioRouteChanged(route: Int) {
        // If Telecom yanks us off speakerphone while a loopback strategy is
        // active the bridge goes silent, so re-assert it.
        val strategy = activeStrategy ?: return
        if (!strategy.privileged) audioBridge.prepareRouting(strategy)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Telephony → relay  (the path that works without the dialer role)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A cellular call is ringing, seen through [TelephonyCallWatcher].
     *
     * Ignored when [telecomCall] is set: that means the InCallService is bound
     * for this call and is already driving it with better information, and two
     * paths announcing the same call would ring the receiver twice.
     */
    fun onTelephonyRinging(number: String) {
        if (telecomCall != null) return
        if (state != CallState.IDLE && state != CallState.ENDED) return
        if (isEmergencyNumber(number)) {
            Log.w(TAG, "emergency call detected — bridge stands down")
            return
        }

        callId = UUID.randomUUID().toString()
        answeringDeviceId = ""
        announcedRinging = false
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        telephonyDriven = true

        announceRinging(number, displayName = "")
        onStateChanged()
    }

    /**
     * The line went off-hook — someone answered, or an outgoing call connected.
     *
     * This is the only "call is up" signal available without the dialer role, so
     * it is what starts the audio bridge on both the inbound and the outbound
     * path.
     */
    fun onTelephonyOffhook() {
        if (telecomCall != null) return
        if (callId.isEmpty()) {
            // Off-hook with no call we know about: a call dialled directly on
            // the gateway handset. Not ours to bridge.
            return
        }
        telephonyDriven = true
        startBridge()
        onStateChanged()
    }

    /** The line went idle — whatever was happening is over. */
    fun onTelephonyIdle() {
        if (telecomCall != null) return
        if (state == CallState.IDLE) return
        teardown("remote_hangup")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Relay → Telecom
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * First `call:answer` for [requestedCallId] wins the bridge. A later answer
     * from a different device is ignored; every device that lost the race is
     * told the call ended so its ringing UI clears.
     */
    fun answer(requestedCallId: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (answeringDeviceId.isNotEmpty()) {
            if (answeringDeviceId != fromDeviceId) {
                Log.i(TAG, "ignoring answer from $fromDeviceId — $answeringDeviceId already won")
            }
            return
        }
        answeringDeviceId = fromDeviceId
        notifyLosers()

        Log.i(TAG, "answering on behalf of $fromDeviceId")
        pushState(CallState.CONNECTING)

        val call = telecomCall
        runCatching {
            if (call != null) {
                @Suppress("DEPRECATION")
                call.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
            } else {
                // No Call object because the InCallService is not bound.
                // acceptRingingCall needs only ANSWER_PHONE_CALLS, which an
                // ordinary app can hold, and it answers whatever is ringing —
                // which, since we only get here off a RINGING state, is this
                // call.
                requireAnswerPermission()
                telecom?.acceptRingingCall() ?: error("no TelecomManager")
            }
        }.onFailure {
            Log.e(TAG, "answer failed", it)
            pushState(CallState.ENDED, cause = "answer_failed: ${it.message}")
        }
    }

    /** Tell every receiver other than the winner that the call is no longer theirs. */
    private fun notifyLosers() {
        val losers = signaling?.knownPeerIds().orEmpty() - answeringDeviceId
        for (peerId in losers) {
            emit(
                Ev.CALL_STATE,
                json.encodeToString(
                    CallStateUpdate(callId = callId, state = CallState.ENDED, cause = "answered_elsewhere"),
                ),
                peerId,
            )
        }
    }

    fun reject(requestedCallId: String, reason: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        endCellularCall { telecomCall?.reject(false, null) }
        teardown(reason.ifEmpty { "rejected_by_client" })
    }

    fun hangup(requestedCallId: String, reason: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        endCellularCall { telecomCall?.disconnect() }
        teardown(reason.ifEmpty { "client_hangup" })
    }

    /**
     * End the call on the SIM side.
     *
     * Without a `Call` object the only lever is `TelecomManager.endCall()`. It
     * is the reason hanging up from the receiver used to leave the gateway's
     * call running: the old code called `telecomCall?.disconnect()`, and
     * `telecomCall` was always null, so the safe-call quietly did nothing and
     * only the receiver's own screen closed.
     */
    private inline fun endCellularCall(viaTelecomCall: () -> Unit) {
        if (telecomCall != null) {
            runCatching { viaTelecomCall() }
            return
        }
        runCatching {
            requireAnswerPermission()
            @Suppress("DEPRECATION")
            val ended = telecom?.endCall() ?: false
            if (!ended) Log.w(TAG, "endCall() reported nothing to end")
        }.onFailure { Log.e(TAG, "endCall failed", it) }
    }

    private fun requireAnswerPermission() {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            error("ANSWER_PHONE_CALLS not granted")
        }
    }

    fun sendDtmf(requestedCallId: String, tone: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        val call = telecomCall ?: return
        for (char in tone) {
            runCatching {
                call.playDtmfTone(char)
                call.stopDtmfTone()
            }
        }
    }

    /** Mute what the far end hears (i.e. disable our outgoing WebRTC track). */
    fun setRemoteMuted(requestedCallId: String, muted: Boolean, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        engine?.setMicrophoneMuted(muted)
    }

    /** True once someone has answered and [fromDeviceId] is not that device. */
    private fun isFromLoser(fromDeviceId: String): Boolean =
        answeringDeviceId.isNotEmpty() && fromDeviceId != answeringDeviceId

    /**
     * Place an outbound cellular call on behalf of [fromDeviceId].
     * Requires `CALL_PHONE`; Telecom will surface the dialing call back through
     * [onTelecomCallAdded], which starts the bridge. There is no race to
     * arbitrate here — the requester owns the call from the start.
     */
    fun placeCall(requestedCallId: String, destination: String, fromDeviceId: String) {
        callId = requestedCallId
        answeringDeviceId = fromDeviceId
        // Stashed so that when Telecom surfaces this call through
        // onTelecomCallAdded — which cannot tell an outgoing call it just
        // placed from an unrelated inbound one — the receiver's id survives
        // instead of being replaced by a fresh UUID.
        pendingOutgoingCallId = requestedCallId
        pendingOutgoingDeviceId = fromDeviceId
        state = CallState.DIALING
        pushState(CallState.DIALING)

        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pushState(CallState.ENDED, cause = "call_phone_permission_missing")
            return
        }

        val uri = Uri.fromParts("tel", destination, null)

        // TelecomManager.placeCall first, ACTION_CALL second. placeCall is the
        // cleaner API but some builds refuse it from an app that is not the
        // default dialer, and this app deliberately is not; ACTION_CALL needs
        // nothing beyond CALL_PHONE and always works. Falling through on
        // failure rather than choosing by SDK level means neither a
        // SecurityException nor a missing Telecom service leaves the receiver
        // stuck on "Dialling" with no call ever placed.
        val placed = runCatching {
            val extras = android.os.Bundle().apply {
                putInt(
                    TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                    android.telecom.VideoProfile.STATE_AUDIO_ONLY,
                )
            }
            telecom?.placeCall(uri, extras) ?: error("no TelecomManager")
            true
        }.getOrElse {
            Log.w(TAG, "placeCall via Telecom failed, falling back to ACTION_CALL", it)
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrElse { fallback ->
                Log.e(TAG, "placeCall failed", fallback)
                false
            }
        }

        if (!placed) pushState(CallState.ENDED, cause = "place_failed")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebRTC
    // ─────────────────────────────────────────────────────────────────────────

    private fun startBridge() {
        if (engine != null) {
            state = CallState.ACTIVE
            pushState(CallState.ACTIVE)
            return
        }
        Log.i(TAG, "starting audio bridge for $callId")
        state = CallState.CONNECTING
        pushState(CallState.CONNECTING)

        val sources = audioBridge.preferredSources()

        val webRtc = WebRtcEngine(
            context = context,
            profile = WebRtcEngine.AudioProfile.TELEPHONY_BRIDGE,
            callbacks = object : WebRtcEngine.Callbacks {

                override fun onLocalDescription(type: String, sdp: String) {
                    if (type == "offer") {
                        emitToActive(Ev.RTC_OFFER, json.encodeToString(RtcSdp(callId, type, sdp)))
                    }
                }

                override fun onIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    emitToActive(
                        Ev.RTC_ICE,
                        json.encodeToString(RtcIce(callId, sdpMid, sdpMLineIndex, candidate)),
                    )
                }

                override fun onConnectionStateChanged(pcState: PeerConnection.PeerConnectionState) {
                    when (pcState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            state = CallState.ACTIVE
                            pushState(CallState.ACTIVE)
                        }
                        PeerConnection.PeerConnectionState.FAILED -> {
                            Log.w(TAG, "peer connection failed — attempting ICE restart")
                            engine?.createOffer(iceRestart = true)
                        }
                        PeerConnection.PeerConnectionState.CLOSED ->
                            teardown("peer_connection_closed")
                        else -> Unit
                    }
                    onStateChanged()
                }

                override fun onRemoteAudioTrack(track: AudioTrack) {
                    // The client's voice. Playing it out of the gateway's chosen
                    // output is what feeds the modem uplink.
                    track.setEnabled(true)
                }

                override fun onStats(stats: WebRtcEngine.CallQuality) = Unit

                override fun onError(message: String, cause: Throwable?) {
                    Log.e(TAG, "webrtc: $message", cause)
                    // Relay it, don't just log it. An audio-capture failure is
                    // the difference between a working call and a silent one,
                    // and the receiver's screen otherwise shows a confident
                    // "Speakerphone loopback" label while nothing is being
                    // recorded at all. Surfacing the cause is what turns a
                    // baffling silent call into a diagnosable one.
                    pushState(state, cause = "audio_error: $message")
                }
            },
        )

        engine = webRtc
        webRtc.initialize(preferredSources = sources)

        // Discover which strategy actually opened and configure routing for it.
        activeStrategy = CallAudioBridge.Strategy.entries
            .firstOrNull { WebRtcEngine.sourceName(it.source) == webRtc.activeCaptureSource }
        activeStrategy?.let(audioBridge::prepareRouting)
        lastStrategyLabel = describeAudio()
        reassertRouting()

        // Same guard as the client: an empty ICE list gathers host candidates
        // only and cannot traverse NAT. Fall back to public STUN and ask the
        // server for fresh TURN credentials in the background.
        val servers = iceServers.ifEmpty {
            Log.w(TAG, "no ICE servers cached — using STUN fallback")
            signaling?.refreshIceServers()
            listOf(
                IceServerDto(
                    urls = listOf(
                        "stun:stun.l.google.com:19302",
                        "stun:stun1.l.google.com:19302",
                    ),
                ),
            )
        }

        webRtc.createPeerConnection(servers)
        webRtc.createOffer()

        Log.i(TAG, "bridge up via ${activeStrategy?.label ?: webRtc.activeCaptureSource}")
    }

    fun onRemoteAnswer(requestedCallId: String, sdp: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        engine?.setRemoteDescription("answer", sdp)
        remoteDescriptionSet = true
        // Drain any candidates that raced ahead of the answer.
        while (true) {
            val ice = pendingRemoteIce.poll() ?: break
            engine?.addIceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
        }
    }

    fun onRemoteIce(ice: RtcIce, fromDeviceId: String) {
        if (ice.callId != callId) return
        if (isFromLoser(fromDeviceId)) return
        if (!remoteDescriptionSet) {
            pendingRemoteIce.add(ice)
            return
        }
        engine?.addIceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate)
    }

    fun renegotiate(requestedCallId: String, fromDeviceId: String) {
        if (requestedCallId != callId) return
        if (isFromLoser(fromDeviceId)) return
        remoteDescriptionSet = false
        engine?.createOffer(iceRestart = true)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun teardown(cause: String) {
        if (state == CallState.IDLE) return
        Log.i(TAG, "tearing down bridge: $cause")
        pushState(CallState.ENDED, cause)

        engine?.close()
        engine = null
        telecomCall = null
        activeStrategy = null
        remoteDescriptionSet = false
        pendingRemoteIce.clear()
        state = CallState.IDLE
        callId = ""
        answeringDeviceId = ""
        pendingOutgoingCallId = ""
        pendingOutgoingDeviceId = ""
        announcedRinging = false
        telephonyDriven = false
        onStateChanged()
    }

    fun shutdown() {
        teardown("service_shutdown")
        if (current === this) current = null
    }

    private fun pushState(newState: CallState, cause: String = "") {
        state = newState
        emitToActive(
            Ev.CALL_STATE,
            json.encodeToString(
                CallStateUpdate(
                    callId = callId,
                    state = newState,
                    cause = cause,
                    audioMode = describeAudio(),
                ),
            ),
        )
    }

    /**
     * Route per-call traffic: broadcast while nobody has answered yet (so every
     * ringing receiver stays in sync), then only to whichever device won once
     * [answeringDeviceId] is set.
     */
    private fun emitToActive(event: String, plaintextJson: String) {
        emit(event, plaintextJson, answeringDeviceId.ifEmpty { null })
    }

    /**
     * What to show on the receiver under the call timer.
     *
     * A loopback strategy only carries audio while the gateway's loudspeaker is
     * on, and on builds that refuse to move the route for a call this app does
     * not own, it will not be. Saying so turns "the call is silent and I have no
     * idea why" into "press the speaker button on the other phone".
     */
    private fun describeAudio(): String {
        val label = activeStrategy?.label ?: engine?.activeCaptureSource.orEmpty()
        val strategy = activeStrategy ?: return label
        if (strategy.privileged) return label
        return when {
            audioBridge.hasWiredHeadset() -> "$label · headset on sender"
            audioBridge.isSpeakerphoneActive() -> label
            else -> "$label · speaker OFF on sender"
        }
    }

    /**
     * Put the loudspeaker back on, twice, after the bridge comes up.
     *
     * Telephony re-routes audio when the call actually connects, which happens
     * after the bridge starts on an outgoing call and can happen again on an
     * incoming one. A single call to prepareRouting at bridge time is therefore
     * routinely undone a second later.
     */
    private fun reassertRouting() {
        val strategy = activeStrategy ?: return
        if (strategy.privileged) return
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in longArrayOf(800L, 2_500L)) {
            handler.postDelayed({
                if (state == CallState.ACTIVE || state == CallState.CONNECTING) {
                    audioBridge.prepareRouting(strategy)
                }
            }, delay)
        }
    }

    private fun handleOf(call: Call): String =
        call.details?.handle?.schemeSpecificPart ?: "unknown"

    /**
     * Is this an emergency call?
     *
     * The modern check hangs off `TelephonyManager`, not `TelecomManager` —
     * an easy thing to get wrong, because `TelecomManager` is what owns the
     * call object itself. It also needs READ_PHONE_STATE, which the user can
     * revoke, so the whole thing is wrapped: any failure answers "not an
     * emergency", which keeps the relay running rather than silently blocking
     * ordinary calls on a permission hiccup.
     */
    private fun isEmergency(call: Call): Boolean = isEmergencyNumber(handleOf(call))

    private fun isEmergencyNumber(number: String): Boolean = runCatching {
        if (number.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService<android.telephony.TelephonyManager>()
                ?.isEmergencyNumber(number) == true
        } else {
            @Suppress("DEPRECATION")
            android.telephony.PhoneNumberUtils.isEmergencyNumber(number)
        }
    }.getOrDefault(false)

    companion object {
        private const val TAG = "CallBridgeController"

        /** Reachable from [RelayInCallService], which Telecom owns. */
        @Volatile
        var current: CallBridgeController? = null
            private set

        /**
         * What the last bridged call actually managed to open.
         *
         * Kept on the companion so the setup screen can report it between
         * calls. Which capture source a device permits is the single fact that
         * decides whether this app is useful on that handset, and it is only
         * discoverable by trying — so once discovered it should not vanish with
         * the call that discovered it.
         */
        @Volatile
        var lastStrategyLabel: String = ""
    }
}
