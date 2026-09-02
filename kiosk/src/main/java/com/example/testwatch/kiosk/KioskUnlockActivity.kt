package com.example.testwatch.kiosk

/** Passcode entry screen reached via the hidden KioskGate gesture; a correct passcode drops
 *  lock-task mode and disarms the kiosk until KIOSK_ENABLE re-arms it. */

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text

class KioskUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UnlockScreen() }
    }

    @androidx.compose.runtime.Composable
    private fun UnlockScreen() {
        var input by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }
        var attempts by remember { mutableIntStateOf(0) }

        MaterialTheme {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Study lock", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                BasicTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = false
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp, textAlign = TextAlign.Center),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF202020), RoundedCornerShape(12.dp))
                        .padding(vertical = 8.dp, horizontal = 12.dp),
                )
                if (error) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Wrong passcode",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFE57373),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (KioskManager.verifyPasscode(input)) {
                            unlock()
                        } else {
                            error = true
                            input = ""
                            attempts++
                            if (attempts >= MAX_ATTEMPTS) finish()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Unlock") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { finish() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Back") }
            }
        }
    }

    private fun unlock() {
        KioskManager.setEnabled(this, false)
        KioskManager.releaseHomePin(this)
        try {
            stopLockTask()
        } catch (t: Throwable) {
            // Not in lock task (e.g. dev watch); disarming the flag is enough.
        }
        Toast.makeText(this, "Kiosk unlocked", Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
