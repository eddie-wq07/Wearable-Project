package com.example.testwatch.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.testwatch.KioskConfig
import com.example.testwatch.presentation.theme.TestWatchTheme

private const val BACKSPACE = "<"
private const val CANCEL = "X"

@Composable
fun PinPromptScreen(
    title: String,
    subtitle: String? = null,
    pinLength: Int = KioskConfig.PIN_LENGTH,
    showCancel: Boolean,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var entered by remember { mutableStateOf("") }

    TestWatchTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth(),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            PinDots(entered.length, pinLength)
            Spacer(Modifier.height(2.dp))
            Keypad(
                onDigit = { d ->
                    if (entered.length < pinLength) {
                        val next = entered + d
                        entered = next
                        if (next.length == pinLength) {
                            onSubmit(next)
                            entered = ""
                        }
                    }
                },
                onBackspace = { if (entered.isNotEmpty()) entered = entered.dropLast(1) },
                onCancel = onCancel,
                showCancel = showCancel,
            )
        }
    }
}

@Composable
private fun PinDots(filled: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(total) { i ->
            val char = if (i < filled) "●" else "○"
            Text(
                text = char,
                fontSize = 18.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Keypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    onCancel: () -> Unit,
    showCancel: Boolean,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(if (showCancel) CANCEL else "", "0", BACKSPACE),
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
            ) {
                row.forEach { label ->
                    KeypadKey(
                        label = label,
                        onClick = {
                            when (label) {
                                "" -> Unit
                                BACKSPACE -> onBackspace()
                                CANCEL -> onCancel()
                                else -> onDigit(label)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, onClick: () -> Unit) {
    if (label.isEmpty()) {
        Spacer(modifier = Modifier.size(width = 44.dp, height = 36.dp))
        return
    }
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 44.dp, height = 36.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            text = label,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun PinSetupScreen(
    pinLength: Int = KioskConfig.PIN_LENGTH,
    onPinChosen: (String) -> Unit,
) {
    var first by remember { mutableStateOf<String?>(null) }
    var mismatch by remember { mutableStateOf(false) }

    PinPromptScreen(
        title = if (first == null) "Set kiosk PIN" else "Confirm PIN",
        subtitle = when {
            mismatch -> "Didn't match. Try again."
            first == null -> "$pinLength digits"
            else -> "Re-enter"
        },
        pinLength = pinLength,
        showCancel = false,
        onSubmit = { entry ->
            val firstSnapshot = first
            if (firstSnapshot == null) {
                first = entry
                mismatch = false
            } else if (firstSnapshot == entry) {
                onPinChosen(entry)
            } else {
                first = null
                mismatch = true
            }
        },
        onCancel = {},
    )
}

@Composable
fun PinUnlockScreen(
    onCorrectPin: () -> Unit,
    onCancel: () -> Unit,
    verify: (String) -> Boolean,
) {
    var error by remember { mutableStateOf(false) }
    PinPromptScreen(
        title = "Enter PIN to exit",
        subtitle = if (error) "Wrong PIN" else null,
        showCancel = true,
        onSubmit = { entry ->
            if (verify(entry)) {
                error = false
                onCorrectPin()
            } else {
                error = true
            }
        },
        onCancel = onCancel,
    )
}
