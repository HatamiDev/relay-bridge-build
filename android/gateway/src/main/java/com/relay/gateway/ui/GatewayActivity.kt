package com.relay.gateway.ui

import android.Manifest
import android.app.role.RoleManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.compose.runtime.DisposableEffect
import android.telecom.TelecomManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.relay.client.ui.components.AuroraBackground
import com.relay.client.ui.components.GlassSurface
import com.relay.client.ui.components.SquircleShape
import com.relay.client.ui.components.glow
import com.relay.client.ui.components.gradientRing
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.RelayGlassTheme
import com.relay.core.model.DeviceRole
import com.relay.core.net.PairingCoordinator
import com.relay.core.util.SamsungBatterySettings
import com.relay.core.util.SystemHealth
import com.relay.gateway.BuildConfig
import com.relay.gateway.GatewayRuntime
import com.relay.gateway.call.CallAudioBridge
import com.relay.gateway.call.CallBridgeController
import com.relay.gateway.service.RelayForegroundService
import kotlinx.coroutines.launch

/**
 * The Sender console.
 *
 * This handset is meant to live in a drawer, so the screen is a checklist, not a
 * dashboard: grant the permissions, take the dialer role, survive Doze, then
 * hand out a code. The pretty messaging interface is the receiver's job.
 *
 * The pairing section is the part that matters. It shows one code in large
 * monospace with a QR beside it, then lists every receiver that has redeemed it
 * along with a six-digit verification number to compare. The code stays live
 * until it expires or is revoked, so several phones can join with the same one.
 */
class GatewayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { RelayGlassTheme { GatewayScreen() } }
    }
}

@Composable
private fun GatewayScreen() {
    val colors = Glass.colors
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val clipboard = LocalClipboardManager.current
    val store = remember { GatewayRuntime.secureStore }
    val scope = rememberCoroutineScope()

    val coordinator = remember {
        PairingCoordinator(context, store, BuildConfig.RELAY_SERVER_URL)
    }

    var session by remember { mutableStateOf<PairingCoordinator.GatewaySession?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var receivers by remember { mutableStateOf(store.peers()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    var dozeExempt by remember {
        mutableStateOf(SystemHealth.isIgnoringBatteryOptimizations(context))
    }

    // Whether Android will actually bind our InCallService. Without this role
    // no call event ever reaches the app, so the gateway rings, the receiver
    // hears nothing, and there is no error anywhere to explain it. The card
    // used to offer the request with no indication of the result, which made an
    // ungranted role indistinguishable from a granted one.
    var dialerHeld by remember { mutableStateOf(holdsDialerRole(context)) }
    var aecOn by remember { mutableStateOf(CallAudioBridge(context).echoCancellation) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        status = if (granted.values.all { it }) {
            "All permissions granted."
        } else {
            "Still missing: " +
                granted.filterValues { !it }.keys.joinToString { it.substringAfterLast('.') }
        }
    }

    val dialerRole = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Activity.RESULT_OK, not ComponentActivity.RESULT_OK — Kotlin does not
        // surface inherited Java statics through a subclass name.
        // The result code lies on some OEM builds — Samsung returns CANCELED
        // even after the user accepts. Ask the system what it actually thinks.
        dialerHeld = holdsDialerRole(context)
        status = if (dialerHeld) {
            "Dialer role granted — call relay is live."
        } else {
            "Dialer role declined. Messages will relay; calls will not."
        }
    }

    // Both can be changed from system settings while this screen is in the
    // background, so re-read them whenever it comes forward rather than trusting
    // the value captured at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                dialerHeld = holdsDialerRole(context)
                dozeExempt = SystemHealth.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Start or restart the pairing session and begin polling for joins. */
    fun openPairing(fresh: Boolean) {
        if (busy) return
        busy = true
        status = "Contacting relay server…"

        activity.lifecycleScope.launch {
            val result = if (fresh && store.isPaired) {
                coordinator.issueAnotherCode()
            } else {
                coordinator.startAsGateway(BuildConfig.BOOTSTRAP_SECRET)
            }

            result
                .onSuccess { created ->
                    session = created
                    qr = renderQr(created.qrPayload)
                    status = "Enter this code on your other phones."
                    RelayForegroundService.start(context)

                    // Poll until the code expires; each new receiver appears in
                    // the list below with its own verification number.
                    coordinator.awaitJoins(created.ttlSeconds) {
                        receivers = store.peers()
                    }
                    if (session != null) status = "Code expired. Generate a new one to add more."
                }
                .onFailure { status = "Pairing failed: ${it.message}" }

            busy = false
        }
    }

    AuroraBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(20.dp))

            Text(
                "Sender",
                color = colors.textPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.6).sp,
            )
            Text(
                "This phone holds the SIM. Keep it powered and online — everything " +
                    "else depends on it.",
                color = colors.textSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
            )

            // ── Pairing ──────────────────────────────────────────────────────
            StepCard("Pairing code", accent = colors.auroraCyan) {
                val current = session

                if (current == null) {
                    Text(
                        "Generates a code your other phones type in. One code can " +
                            "add several receivers while it is still valid.",
                        color = colors.textTertiary, fontSize = 12.5.sp, lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        label = if (busy) "Working…" else "Create pairing code",
                        enabled = !busy,
                        onClick = { openPairing(fresh = store.isPaired) },
                    )
                } else {
                    // The code, big enough to read across a room.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(colors.canvasRaised)
                            .clickable {
                                clipboard.setText(AnnotatedString(current.pairCode))
                                status = "Code copied."
                            }
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                current.pairCode,
                                color = colors.accent,
                                fontSize = 40.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 6.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(11.dp),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("tap to copy", color = colors.textTertiary, fontSize = 10.5.sp)
                            }
                        }
                    }

                    qr?.let { bitmap ->
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White)
                                .padding(14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                bitmap.asImageBitmap(),
                                contentDescription = "Pairing QR",
                                modifier = Modifier.size(220.dp),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Scanning the QR is safer than typing the code — it carries " +
                                "the key itself, so the server cannot get between you.",
                            color = colors.textTertiary, fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("New code", Modifier.weight(1f)) {
                            openPairing(fresh = true)
                        }
                        SecondaryButton("Stop accepting", Modifier.weight(1f)) {
                            scope.launch {
                                coordinator.revokeCode()
                                session = null
                                qr = null
                                status = "Code revoked. Existing receivers are unaffected."
                            }
                        }
                    }
                }
            }

            // ── Paired receivers ─────────────────────────────────────────────
            if (receivers.isNotEmpty()) {
                StepCard("Receivers (${receivers.size})", accent = colors.auroraViolet) {
                    Text(
                        "Check that each number below matches what that phone shows. " +
                            "If one differs, remove it — the connection was tampered with.",
                        color = colors.warning, fontSize = 11.5.sp, lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(12.dp))

                    receivers.forEach { peer ->
                        ReceiverRow(
                            label = peer.label.ifEmpty { peer.model.ifEmpty { peer.deviceId } },
                            sas = peer.sas,
                            confirmed = peer.confirmed,
                            onConfirm = {
                                scope.launch {
                                    status = "Confirming…"
                                    // The result used to be discarded and the
                                    // success message printed unconditionally,
                                    // so a rejected confirm looked exactly like
                                    // a successful one — and since the row never
                                    // changed either, the button looked dead
                                    // whichever way the call went.
                                    coordinator.confirmReceiver(peer.deviceId)
                                        .onSuccess {
                                            store.markConfirmed(peer.deviceId)
                                            receivers = store.peers()
                                            // Confirming is the moment this
                                            // device stops being "a phone
                                            // showing a QR code" and becomes a
                                            // paired gateway. The foreground
                                            // service was started earlier,
                                            // before any peer existed, so its
                                            // connect() bailed out on isPaired
                                            // and left the socket closed.
                                            // Nothing else ever asked it to try
                                            // again — poke it now that a root
                                            // key is on disk.
                                            RelayForegroundService.reconnect(context)
                                            status = "${peer.label.ifEmpty { "Receiver" }} confirmed."
                                        }
                                        .onFailure {
                                            status = "Confirm failed: " + friendlyError(it)
                                        }
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    store.removePeer(peer.deviceId)
                                    receivers = store.peers()
                                    status = "Removed ${peer.label}."
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            // ── Permissions ──────────────────────────────────────────────────
            StepCard("Permissions", accent = colors.accent) {
                Text(
                    "SMS, phone state, call log, microphone, contacts and " +
                        "notifications. Call log is what carries the caller's " +
                        "number — without it every call reaches the receiver as " +
                        "\"unknown\".",
                    color = colors.textTertiary, fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton("Request permissions") { permissions.launch(requiredPermissions()) }
            }

            // ── Audio capture ────────────────────────────────────────────────
            StepCard("Call audio", accent = colors.auroraCyan) {
                val privileged = remember { CallAudioBridge(context).hasCaptureAudioOutput() }
                val headset = remember(dialerHeld) { CallAudioBridge(context).hasWiredHeadset() }

                StatusLine(
                    ok = privileged,
                    okText = "Telephony tap available — full-quality audio",
                    badText = "Telephony tap unavailable — acoustic bridge only",
                )
                Spacer(Modifier.height(10.dp))
                StatusLine(
                    ok = headset,
                    okText = "Headset connected — the call stays private",
                    badText = "No headset — the loudspeaker will be used",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (privileged) {
                        "This build holds CAPTURE_AUDIO_OUTPUT, so the bridge can " +
                            "read the call stream directly. Nothing is played into " +
                            "the room and quality is full-duplex."
                    } else {
                        "Android reserves the call stream for privileged apps, so " +
                            "audio is carried acoustically. Plug a wired headset in " +
                            "and rest its earpiece against its own inline microphone: " +
                            "the call stays inaudible in the room and couples far " +
                            "better than a loudspeaker across a room. " +
                            "docs/05-PRIVILEGED-INSTALL.md covers the other route."
                    },
                    color = colors.textTertiary, fontSize = 12.5.sp, lineHeight = 17.sp,
                )

                if (!privileged) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Cancel our own audio from the capture",
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                // The honest description of the trade, because
                                // the right setting depends on this device's
                                // echo canceller and cannot be determined from
                                // here — only by making a call each way.
                                "Leave on. If the far end is inaudible, turn it " +
                                    "off: some cancellers remove the call along " +
                                    "with the echo, and the cost is that the " +
                                    "other party hears themselves.",
                                color = colors.textTertiary,
                                fontSize = 11.5.sp,
                                lineHeight = 15.sp,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = aecOn,
                            onCheckedChange = {
                                aecOn = it
                                CallAudioBridge(context).echoCancellation = it
                                status = if (it) {
                                    "Echo cancellation on. Place a new call to apply."
                                } else {
                                    "Echo cancellation off. Place a new call to apply."
                                }
                            },
                        )
                    }
                }

                val last = CallBridgeController.lastStrategyLabel
                if (last.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Last call used: $last",
                        color = colors.textSecondary, fontSize = 12.sp,
                    )
                }
            }

            // ── Dialer role ──────────────────────────────────────────────────
            StepCard("Default phone app", accent = colors.accent) {
                StatusLine(
                    ok = dialerHeld,
                    okText = "Dialer role held — precise call control",
                    badText = "Dialer role not held — using the telephony path",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Optional. Calls relay either way: ringing, answering, hanging " +
                        "up and audio all work through phone-state and " +
                        "ANSWER_PHONE_CALLS, which need no special role. Taking the " +
                        "role would add DTMF and hold, but would also make this app " +
                        "the phone's in-call screen — so leaving it alone keeps this " +
                        "handset a normal phone.",
                    color = colors.textTertiary, fontSize = 12.5.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                PrimaryButton(if (dialerHeld) "Change default phone app" else "Request dialer role") {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val roleManager = context.getSystemService(RoleManager::class.java)
                        if (roleManager?.isRoleAvailable(RoleManager.ROLE_DIALER) == true) {
                            dialerRole.launch(
                                roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER),
                            )
                        } else {
                            status = "This device does not offer the dialer role."
                        }
                    } else {
                        status = "Requires Android 10 or newer."
                    }
                }
            }

            // ── Survival ─────────────────────────────────────────────────────
            StepCard("Keep me running", accent = colors.warning) {
                StatusLine(
                    ok = dozeExempt,
                    okText = "Battery exemption granted",
                    badText = "Battery exemption missing",
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Without it, Android can hold this app's connection closed for " +
                        "15 minutes at a stretch, and a call will ring long after it " +
                        "stopped.",
                    color = colors.textTertiary, fontSize = 12.sp, lineHeight = 17.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Grant", Modifier.weight(1f)) {
                        runCatching {
                            context.startActivity(
                                SystemHealth.requestIgnoreBatteryOptimizationsIntent(context),
                            )
                        }.onFailure {
                            context.startActivity(SystemHealth.batteryOptimizationSettingsIntent())
                        }
                        dozeExempt = SystemHealth.isIgnoringBatteryOptimizations(context)
                    }
                    if (SamsungBatterySettings.isSamsung()) {
                        SecondaryButton("Samsung settings", Modifier.weight(1f)) {
                            context.startActivity(
                                SamsungBatterySettings.openPowerSettingsIntent(context),
                            )
                        }
                    }
                }
                if (SamsungBatterySettings.isSamsung()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "One UI kills background apps beyond Doze. Do these by hand:",
                        color = colors.textSecondary, fontSize = 11.5.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    SamsungBatterySettings.manualSteps.forEach {
                        Text(
                            "• $it",
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            // ── Audio capability ─────────────────────────────────────────────
            StepCard("Call audio", accent = colors.auroraIndigo) {
                val bridge = remember { CallAudioBridge(context) }
                val privileged = remember { bridge.hasCaptureAudioOutput() }
                val best = remember { bridge.availableStrategies().firstOrNull() }

                StatusLine(
                    ok = privileged,
                    okText = "Direct telephony capture available",
                    badText = "Telephony capture blocked — using loopback",
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    bridge.advisoryFor(best),
                    color = colors.textTertiary, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }

            // ── Danger zone ──────────────────────────────────────────────────
            if (store.isPaired) {
                StepCard("Reset", accent = colors.danger) {
                    Text(
                        "Removes every receiver, the encryption keys and the room on " +
                            "the server. Cannot be undone.",
                        color = colors.textTertiary, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    SecondaryButton("Unpair everything", tint = colors.danger) {
                        scope.launch {
                            com.relay.core.net.PairingApi(store.serverUrl).unpair(store.authToken)
                            store.unpair()
                            receivers = emptyList()
                            session = null
                            qr = null
                            status = "Unpaired. All keys wiped from this phone."
                        }
                    }
                }
            }

            if (status.isNotEmpty()) {
                Text(status, color = colors.accent, fontSize = 12.5.sp)
            }

            Text(
                "Role: Sender · ${Build.MODEL} · ${BuildConfig.RELAY_SERVER_URL.substringAfter("//")}",
                color = colors.textTertiary,
                fontSize = 10.5.sp,
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────

@Composable
private fun StepCard(
    title: String,
    accent: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Glass.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(9.dp))
                Text(
                    title,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(11.dp))
            content()
        }
    }
}

@Composable
private fun ReceiverRow(
    label: String,
    sas: String,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    onRemove: () -> Unit,
) {
    val colors = Glass.colors
    val shape = remember { SquircleShape(18.dp) }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.canvasRaised)
            .gradientRing(colors.auroraSweep, shape, 1.dp)
            .padding(14.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = colors.auroraViolet,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    label,
                    color = colors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text("Verification number", color = colors.textTertiary, fontSize = 10.5.sp)
            Text(
                sas.chunked(3).joinToString(" "),
                color = colors.accent,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (confirmed) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = colors.success,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Verified",
                            color = colors.success,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                } else {
                    PrimaryButton("Numbers match", Modifier.weight(1f), onClick = onConfirm)
                }
                SecondaryButton("Remove", Modifier.weight(1f), tint = colors.danger, onClick = onRemove)
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, okText: String, badText: String) {
    val colors = Glass.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Refresh,
            contentDescription = null,
            tint = if (ok) colors.success else colors.danger,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (ok) okText else badText,
            color = if (ok) colors.success else colors.danger,
            fontSize = 12.5.sp,
        )
    }
}

@Composable
private fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = Glass.colors
    Box(
        modifier
            .then(if (enabled) Modifier.glow(colors.accent, RoundedCornerShape(50), 12.dp) else Modifier)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) colors.accent else colors.glassDark)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) Color(0xFF04121C) else colors.textTertiary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    tint: Color = Glass.colors.textSecondary,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.13f))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

// ── Helpers ──────────────────────────────────────────────────────────────────

/**
 * Turn a thrown pairing error into something a person can act on.
 *
 * The raw text is usually an HTTP body or a socket exception; neither tells the
 * user whether to retry, re-pair, or check the network, which is the only
 * decision they can actually make from this screen.
 */
private fun friendlyError(t: Throwable): String {
    val text = (t.message ?: t::class.simpleName.orEmpty()).lowercase()
    return when {
        "401" in text || "unauthor" in text -> "this device is no longer paired — re-pair it"
        "404" in text -> "the receiver is not waiting any more — ask it to enter the code again"
        "timeout" in text || "timed out" in text -> "the relay did not answer in time"
        "unable to resolve" in text || "failed to connect" in text ->
            "cannot reach the relay server"
        else -> t.message ?: "unknown error"
    }
}

/**
 * Does this app currently hold the dialer role?
 *
 * Checked through RoleManager on Android 10+ and by comparing the package name
 * against the default-dialer package below that, because RoleManager did not
 * exist before Q.
 */
private fun holdsDialerRole(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        context.getSystemService(RoleManager::class.java)
            ?.isRoleHeld(RoleManager.ROLE_DIALER) == true
    } else {
        context.getSystemService(TelecomManager::class.java)
            ?.defaultDialerPackage == context.packageName
    }

private fun requiredPermissions(): Array<String> = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.READ_PHONE_STATE)
    add(Manifest.permission.READ_PHONE_NUMBERS)
    add(Manifest.permission.CALL_PHONE)
    add(Manifest.permission.ANSWER_PHONE_CALLS)
    // Without READ_CALL_LOG the PHONE_STATE broadcast omits the caller's
    // number, and every incoming call reaches the receiver as "unknown".
    add(Manifest.permission.READ_CALL_LOG)
    add(Manifest.permission.RECORD_AUDIO)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
}.toTypedArray()

/**
 * High error-correction QR — the payload includes a 122-character public key,
 * and level H keeps it scannable on a scratched or dim screen.
 */
private fun renderQr(content: String, size: Int = 720): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) AndroidColor.BLACK else AndroidColor.WHITE)
        }
    }
    return bitmap
}

@Suppress("unused")
private val roleHint = DeviceRole.GATEWAY
