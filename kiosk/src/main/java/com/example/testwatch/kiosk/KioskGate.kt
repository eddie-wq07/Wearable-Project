package com.example.testwatch.kiosk

/** Invisible top-center hotspot wrapped around the app UI: 5 quick taps open the passcode
 *  unlock screen. Participants have no visible affordance to discover it. */

import android.content.Intent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val TAPS_TO_OPEN = 5
private const val WINDOW_MS = 3_000L

@Composable
fun KioskGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val taps = remember { ArrayDeque<Long>() }

    Box(modifier = Modifier.fillMaxSize()) {
        content()
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .size(width = 84.dp, height = 30.dp)
                .pointerInput(Unit) {
                    detectTapGestures {
                        val now = System.currentTimeMillis()
                        taps.addLast(now)
                        while (taps.size > TAPS_TO_OPEN) taps.removeFirst()
                        if (taps.size == TAPS_TO_OPEN && now - taps.first() <= WINDOW_MS) {
                            taps.clear()
                            context.startActivity(Intent(context, KioskUnlockActivity::class.java))
                        }
                    }
                },
        )
    }
}
