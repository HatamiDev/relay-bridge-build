package com.relay.client.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ui.components.AuroraBackground
import com.relay.client.ui.components.BlurredWallpaper
import com.relay.client.ui.components.CircleIconButton
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.OverBrightRegion
import com.relay.client.ui.components.SquircleShape
import com.relay.client.ui.components.glow
import com.relay.client.ui.components.gradientRing
import com.relay.client.ui.theme.AuroraVariant
import com.relay.client.ui.theme.Glass
import com.relay.client.util.decodeBase64Image
import com.relay.client.util.initials
import com.relay.core.model.CallState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * Agent 4 — the call screen.
 *
 * Spec: blurred full-screen avatar wallpaper, glowing call controls, HD audio
 * metrics.
 *
 * The metrics panel is not decoration. On a relayed call there are two failure
 * modes a user cannot otherwise diagnose — a bad network leg and a degraded
 * capture path on the gateway — so the panel reports RTT/jitter/loss/bitrate
 * *and* names the capture strategy the gateway actually got. A "Speakerphone
 * loopback" label explains poor audio far better than a spinner does.
 */
@Composable
fun CallScreen(
    state: CallScreenState,
    callId: String,
    fallbackNumber: String,
    fallbackName: String,
    onClose: () -> Unit,
) {
    val colors = Glass.colors
    val repository = state.repository

    val call by repository.callState.collectAsState()
    val quality by state.session.quality.collectAsState()
    val mediaConnected by state.session.connected.collectAsState()
    val contacts by repository.contacts.collectAsState()

    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var showKeypad by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }

    val number = call.peerNumber.ifEmpty { fallbackNumber }
    val name = call.peerName.ifEmpty { fallbackName.ifEmpty { number } }

    val photo = remember(contacts, number) {
        contacts.firstOrNull { it.number.digits() == number.digits() }
            ?.photoB64
            ?.let(::decodeBase64Image)
    }

    // ── Ringback ─────────────────────────────────────────────────────────────
    //
    // Played locally rather than relayed. The network's own ringback is in-band
    // audio on the gateway's earpiece, and it only reaches this handset once the
    // acoustic bridge is up and the far end's speaker is carrying it — which is
    // late, unreliable, and on some networks never happens at all. A dialling
    // phone that is completely silent reads as broken, so this fills the gap
    // with the standard supervisory tone and stops the moment the call connects
    // or ends.
    LaunchedEffect(call.state) {
        if (call.state != CallState.DIALING && call.state != CallState.CONNECTING) return@LaunchedEffect
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME)
        }.getOrNull() ?: return@LaunchedEffect
        try {
            tone.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            // Cancelled by the effect restarting on the next state change.
            awaitCancellation()
        } finally {
            runCatching { tone.stopTone(); tone.release() }
        }
    }

    // Duration ticks locally from the moment the gateway reports ACTIVE.
    LaunchedEffect(call.state) {
        if (call.state == CallState.ACTIVE) {
            while (true) {
                elapsed = call.elapsedMs
                delay(500)
            }
        } else {
            elapsed = 0L
        }
    }

    Box(Modifier.fillMaxSize().background(colors.canvas)) {

        // ── Wallpaper: the contact's own photo, pushed far out of focus ───────
        if (photo != null) {
            BlurredWallpaper(Modifier.fillMaxSize(), blurRadius = 80.dp, scrimAlpha = 0.6f) {
                Image(
                    bitmap = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            // No photo to refract, so the aurora itself carries the screen —
            // pushed above shipped intensity because it is the only thing here.
            AuroraBackground(
                Modifier.fillMaxSize(),
                variant = AuroraVariant.Feed,
                intensity = 1.4f,
            ) {}
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))

            // ── Status line ──────────────────────────────────────────────────
            Text(
                text = statusLabel(call.state, call.inbound),
                color = colors.textSecondary,
                fontSize = 13.sp,
                letterSpacing = 1.2.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(Modifier.height(24.dp))

            // ── Avatar ───────────────────────────────────────────────────────
            CallAvatar(
                photo = photo,
                name = name,
                ringing = call.state == CallState.RINGING || call.state == CallState.DIALING,
            )

            Spacer(Modifier.height(22.dp))

            Text(
                text = name,
                color = colors.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                letterSpacing = (-0.5).sp,
            )
            if (name != number) {
                Spacer(Modifier.height(4.dp))
                Text(number, color = colors.textTertiary, fontSize = 14.sp)
            }

            Spacer(Modifier.height(10.dp))

            Text(
                text = if (call.state == CallState.ACTIVE) formatElapsed(elapsed) else " ",
                color = colors.accent,
                fontSize = 17.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )

            // ── Why is this call silent? ─────────────────────────────────────
            //
            // Three different faults all present as "no sound", and the fix for
            // each is different and none of them is guessable: a dead media
            // path needs a network change, a muted loudspeaker needs a button
            // pressed on the other handset, and a connected-but-empty stream
            // means the sender's capture was refused. Naming which one it is
            // costs one line and saves an evening.
            //
            // Held back for a few seconds because all three are briefly true
            // while a call is still setting up.
            val diagnosis = when {
                call.state != CallState.ACTIVE || elapsed < SETTLE_MS -> ""
                !mediaConnected ->
                    "No media path — the phones cannot reach each other. " +
                        "Put both on the same Wi-Fi, or configure a TURN server."
                call.audioMode.contains("speaker OFF", ignoreCase = true) ->
                    "The sender's loudspeaker is off. Android refused to move the " +
                        "route, so press the speaker button on the SIM phone."
                quality.bitrateKbps == 0 ->
                    "Connected, but no audio is arriving — the sender's microphone " +
                        "capture was refused by the system."
                else -> ""
            }

            if (diagnosis.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    diagnosis,
                    color = colors.warning,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Spacer(Modifier.height(18.dp))

            // ── HD metrics ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = call.state == CallState.ACTIVE,
                enter = fadeIn(tween(400)),
                exit = fadeOut(tween(200)),
            ) {
                HdMetricsPanel(
                    connected = mediaConnected,
                    rttMs = quality.rttMs,
                    jitterMs = quality.jitterMs,
                    lossPct = quality.lossPct,
                    bitrateKbps = quality.bitrateKbps,
                    codec = quality.codec,
                    transport = quality.candidatePairType,
                    gatewayAudioMode = call.audioMode,
                )
            }

            Spacer(Modifier.weight(1f))

            // ── Keypad ───────────────────────────────────────────────────────
            AnimatedVisibility(showKeypad, enter = fadeIn(), exit = fadeOut()) {
                DtmfKeypad { tone -> repository.sendDtmf(callId, tone) }
            }

            Spacer(Modifier.height(20.dp))

            // ── Controls ─────────────────────────────────────────────────────
            //
            // The cluster sits in the bright lower band of the aurora, so its
            // untinted buttons default to light frost. Dark smoke down there
            // reads as a muddy grey smear rather than as glass.
            OverBrightRegion {
                when (call.state) {
                    CallState.RINGING -> IncomingControls(
                        onDecline = { repository.rejectCall(callId); onClose() },
                        onAnswer = { repository.answerCall(callId) },
                    )
                    else -> ActiveControls(
                        muted = muted,
                        speaker = speaker,
                        keypadOpen = showKeypad,
                        onToggleMute = {
                            muted = !muted
                            state.session.setMuted(muted)
                            repository.setFarEndMuted(callId, muted)
                        },
                        onToggleSpeaker = {
                            speaker = !speaker
                            state.session.setSpeakerphone(speaker)
                        },
                        onToggleKeypad = { showKeypad = !showKeypad },
                        onHangUp = { repository.hangUp(callId); onClose() },
                    )
                }
            }

            Spacer(Modifier.height(36.dp))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun CallAvatar(
    photo: androidx.compose.ui.graphics.ImageBitmap?,
    name: String,
    ringing: Boolean,
) {
    val colors = Glass.colors
    val shape = remember { SquircleShape(56.dp) }

    // Two concentric halos breathing out of phase: the visual language of "this
    // is ringing right now" without a literal bell.
    val transition = rememberInfiniteTransition(label = "ring")
    val outer by transition.animateFloat(
        1f, 1.16f,
        infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "outer",
    )
    val glowAlpha by transition.animateFloat(
        0.30f, 0.75f,
        infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "glowAlpha",
    )

    Box(contentAlignment = Alignment.Center) {
        if (ringing) {
            Box(
                Modifier
                    .size(176.dp)
                    .scale(outer)
                    .clip(shape)
                    .background(colors.accent.copy(alpha = glowAlpha * 0.14f)),
            )
        }
        Box(
            Modifier
                .size(148.dp)
                .then(
                    if (ringing) {
                        Modifier.glow(colors.accent.copy(alpha = glowAlpha), shape, 20.dp)
                    } else {
                        Modifier.glow(colors.auroraIndigo.copy(alpha = 0.5f), shape, 14.dp)
                    },
                )
                .clip(shape)
                .background(colors.glassDarkStrong)
                .gradientRing(colors.auroraSweep, shape, 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().clip(shape),
                )
            } else {
                // initials() is empty for an unsaved number — a person glyph
                // then, rather than a blank circle.
                val letters = name.initials()
                if (letters.isNotEmpty()) {
                    Text(
                        letters,
                        color = colors.textPrimary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = colors.textPrimary.copy(alpha = 0.72f),
                        modifier = Modifier.size(64.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HdMetricsPanel(
    connected: Boolean,
    rttMs: Int,
    jitterMs: Int,
    lossPct: Double,
    bitrateKbps: Int,
    codec: String,
    transport: String,
    gatewayAudioMode: String,
) {
    val colors = Glass.colors

    // A single honest verdict, derived from the worst of the three signals.
    val grade = when {
        !connected -> Grade("CONNECTING", colors.textTertiary)
        lossPct > 5 || rttMs > 400 -> Grade("POOR", colors.danger)
        lossPct > 1.5 || rttMs > 200 || jitterMs > 40 -> Grade("FAIR", colors.warning)
        else -> Grade("HD", colors.success)
    }

    GlassSurface(shape = RoundedCornerShape(20.dp), strong = true) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(grade.color))
                Spacer(Modifier.width(7.dp))
                Text(
                    grade.label,
                    color = grade.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    codec.uppercase(),
                    color = colors.textTertiary,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (transport.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        transport.uppercase(),
                        color = colors.textTertiary,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(9.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Metric("RTT", "$rttMs", "ms")
                Metric("JITTER", "$jitterMs", "ms")
                Metric("LOSS", "%.1f".format(lossPct), "%")
                Metric("RATE", "$bitrateKbps", "kbps")
            }

            if (gatewayAudioMode.isNotEmpty()) {
                Spacer(Modifier.height(9.dp))
                Text(
                    "Gateway capture · $gatewayAudioMode",
                    color = colors.textTertiary,
                    fontSize = 10.5.sp,
                )
            }
        }
    }
}

private data class Grade(val label: String, val color: Color)

@Composable
private fun Metric(label: String, value: String, unit: String) {
    val colors = Glass.colors
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, color = colors.textTertiary, fontSize = 8.5.sp, letterSpacing = 0.8.sp)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(unit, color = colors.textTertiary, fontSize = 9.sp,
                modifier = Modifier.padding(start = 1.dp, bottom = 1.dp))
        }
    }
}

/**
 * Answer / decline, as the kit draws them.
 *
 * The kit puts these on a small popup card over the call list: two 8dp
 * rounded rectangles, text-only, Decline on the left. That works for a chat
 * app where a call is a foreground event.
 *
 * It cannot work here. A relayed cellular call arrives on a phone that is
 * locked, in a pocket, screen off — the notification is a full-screen intent
 * and the user is answering by feel and glance, not by reading a card. So the
 * layout stays full-screen and the buttons stay large, while the *vocabulary*
 * is the kit's: its error and success reds and greens, its 8dp radius, its
 * 14sp Medium button label.
 *
 * Icons are kept alongside the labels because at arm's length, half awake, a
 * green pill and a red pill are distinguishable before either word is.
 */
@Composable
private fun IncomingControls(onDecline: () -> Unit, onAnswer: () -> Unit) {
    val colors = Glass.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CallActionButton(
            label = "Decline",
            icon = Icons.Rounded.CallEnd,
            background = colors.danger,
            onClick = onDecline,
            modifier = Modifier.weight(1f),
        )
        CallActionButton(
            label = "Accept",
            icon = Icons.Rounded.Call,
            background = colors.success,
            onClick = onAnswer,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CallActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Glass.colors
    Row(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.textOnLight,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            color = colors.textOnLight,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ActiveControls(
    muted: Boolean,
    speaker: Boolean,
    keypadOpen: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleKeypad: () -> Unit,
    onHangUp: () -> Unit,
) {
    val colors = Glass.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        // An engaged toggle turns solid accent and gains a bloom; an idle one
        // passes no background at all, so it picks up the region's glass tone
        // and its hairline ring from CircleIconButton.
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            CircleIconButton(
                icon = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = if (muted) "Unmute" else "Mute",
                size = TOGGLE_BUTTON,
                tint = if (muted) colors.textOnLight else colors.textPrimary,
                background = if (muted) colors.accent else null,
                glowColor = if (muted) colors.accent else null,
                onClick = onToggleMute,
            )
            CircleIconButton(
                icon = Icons.Rounded.Dialpad,
                contentDescription = "Keypad",
                size = TOGGLE_BUTTON,
                tint = if (keypadOpen) colors.textOnLight else colors.textPrimary,
                background = if (keypadOpen) colors.accent else null,
                glowColor = if (keypadOpen) colors.accent else null,
                onClick = onToggleKeypad,
            )
            CircleIconButton(
                icon = Icons.Rounded.VolumeUp,
                contentDescription = "Speaker",
                size = TOGGLE_BUTTON,
                tint = if (speaker) colors.textOnLight else colors.textPrimary,
                background = if (speaker) colors.accent else null,
                glowColor = if (speaker) colors.accent else null,
                onClick = onToggleSpeaker,
            )
        }
        Spacer(Modifier.height(26.dp))
        CircleIconButton(
            icon = Icons.Rounded.CallEnd,
            contentDescription = "End call",
            size = ANSWER_BUTTON,
            tint = Color.White,
            background = colors.danger,
            glowColor = colors.danger,
            onClick = onHangUp,
        )
    }
}

// Answer / decline / hang up are the irreversible ones, so they are visibly
// larger than the toggles rather than merely differently coloured.
private val ANSWER_BUTTON = 68.dp
private val TOGGLE_BUTTON = 58.dp

@Composable
private fun DtmfKeypad(onTone: (String) -> Unit) {
    val colors = Glass.colors
    val rows = listOf(
        listOf("1" to "", "2" to "ABC", "3" to "DEF"),
        listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
        listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
        listOf("*" to "", "0" to "+", "#" to ""),
    )

    GlassSurface(shape = RoundedCornerShape(26.dp), strong = true) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { (digit, letters) ->
                        Box(
                            Modifier
                                .size(58.dp)
                                .clip(CircleShape)
                                .background(colors.glassDark)
                                .clickable { onTone(digit) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(digit, color = colors.textPrimary, fontSize = 22.sp,
                                    fontWeight = FontWeight.Medium)
                                if (letters.isNotEmpty()) {
                                    Text(letters, color = colors.textTertiary, fontSize = 8.sp,
                                        letterSpacing = 1.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

private fun statusLabel(state: CallState, inbound: Boolean): String = when (state) {
    CallState.RINGING -> "INCOMING CALL"
    CallState.DIALING -> "CALLING…"
    CallState.CONNECTING -> "CONNECTING…"
    CallState.ACTIVE -> if (inbound) "RELAYED CALL" else "RELAYED CALL"
    CallState.HELD -> "ON HOLD"
    CallState.ENDED -> "CALL ENDED"
    CallState.IDLE -> ""
}

private fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

private fun String.digits() = filter(Char::isDigit)

// ToneGenerator's scale is 0..100. Loud enough to be obvious against a quiet
// room, quiet enough not to startle someone holding the phone to their ear.
private const val RINGBACK_VOLUME = 70

// A call needs a moment to negotiate media and settle its audio route, and all
// three silence diagnoses are briefly true in that window. Six seconds is long
// enough that a normal call never shows a warning and short enough that a
// broken one does not sit there mute and unexplained.
private const val SETTLE_MS = 6_000L
