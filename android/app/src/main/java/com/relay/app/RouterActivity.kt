package com.relay.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapCalls
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.client.ReceiverActivity
import com.relay.client.ui.theme.Glass
import com.relay.client.ui.theme.RelayGlassTheme
import com.relay.core.model.DeviceRole
import com.relay.gateway.ui.GatewayActivity
import kotlinx.coroutines.delay

/**
 * The only launcher entry point, and the splash.
 *
 * Forwarding is instant when the app is opened from a notification: tapping a
 * message to read it should not cost three seconds of branding, and a deep link
 * that pauses to introduce itself is worse than no splash at all. The animation
 * plays only on a plain launch from the home screen.
 *
 * Declared `noHistory` in the manifest, so pressing Back from the destination
 * exits the app instead of bouncing through this router.
 */
class RouterActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val threadId = intent?.getStringExtra(EXTRA_THREAD_ID)

        if (threadId != null) {
            forward(threadId)
            return
        }

        enableEdgeToEdge()
        setContent {
            RelayGlassTheme {
                SplashScreen(onFinished = { forward(null) })
            }
        }
    }

    private fun forward(threadId: String?) {
        val store = RelayApp.instance.secureStore

        val destination = when (store.role) {
            DeviceRole.GATEWAY -> Intent(this, GatewayActivity::class.java)
            DeviceRole.RECEIVER -> Intent(this, ReceiverActivity::class.java)
            DeviceRole.UNSET -> Intent(this, RoleSelectActivity::class.java)
        }
        threadId?.let { destination.putExtra(EXTRA_THREAD_ID, it) }

        startActivity(destination.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_THREAD_ID = "thread_id"
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Three seconds, in three beats.
 *
 * The mark settles first, the name arrives under it, and a rule draws between
 * them — staggered rather than simultaneous, because everything appearing at
 * once reads as a static image that happened to fade in. The whole thing then
 * fades out before the next screen rather than cutting, so the hand-off does
 * not flash.
 */
@Composable
private fun SplashScreen(onFinished: () -> Unit) {
    val colors = Glass.colors

    var markVisible by remember { mutableStateOf(false) }
    var nameVisible by remember { mutableStateOf(false) }
    var ruleVisible by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        markVisible = true
        delay(450)
        ruleVisible = true
        delay(250)
        nameVisible = true
        delay(1_700)
        leaving = true
        delay(EXIT_MS.toLong())
        onFinished()
    }

    val markAlpha by animateFloatAsState(
        targetValue = if (markVisible) 1f else 0f,
        animationSpec = tween(600, easing = LinearOutSlowInEasing),
        label = "markAlpha",
    )
    // Settles from slightly oversized rather than growing from nothing: a mark
    // that shrinks into place reads as arriving, one that grows reads as a
    // loading spinner.
    val markScale by animateFloatAsState(
        targetValue = if (markVisible) 1f else 1.18f,
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "markScale",
    )
    val ruleWidth by animateFloatAsState(
        targetValue = if (ruleVisible) 1f else 0f,
        animationSpec = tween(700, easing = LinearOutSlowInEasing),
        label = "ruleWidth",
    )
    val nameAlpha by animateFloatAsState(
        targetValue = if (nameVisible) 1f else 0f,
        animationSpec = tween(600),
        label = "nameAlpha",
    )
    val pageAlpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(EXIT_MS),
        label = "pageAlpha",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.canvas)
            .alpha(pageAlpha),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .scale(markScale)
                    .alpha(markAlpha)
                    .clip(RoundedCornerShape(24.dp))
                    .background(colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.SwapCalls,
                    contentDescription = null,
                    tint = colors.textPrimary,
                    modifier = Modifier.size(46.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "RelayBridge",
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(markAlpha),
            )

            Spacer(Modifier.height(16.dp))

            Box(
                Modifier
                    .fillMaxWidth(0.5f)
                    .height(1.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        // fillMaxWidth rejects a zero fraction, and the rule
                        // starts at zero width by design, so it is clamped to a
                        // hairline rather than allowed to reach 0f.
                        .fillMaxWidth(ruleWidth.coerceAtLeast(0.001f))
                        .height(1.dp)
                        .background(colors.accent),
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                "Built by",
                color = colors.textSecondary,
                fontSize = 12.sp,
                letterSpacing = 1.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(nameAlpha),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Mehran Hatami",
                color = colors.textPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(nameAlpha),
            )
        }

        Text(
            "hatamidev.com",
            color = colors.textTertiary,
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .alpha(nameAlpha),
        )
    }
}

/** Fade-out before the hand-off, so the next screen does not cut in. */
private const val EXIT_MS = 320
