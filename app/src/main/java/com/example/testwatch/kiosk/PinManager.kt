package com.example.testwatch.kiosk

import com.example.testwatch.KioskConfig

object PinManager {
    fun verify(pin: String): Boolean = pin == KioskConfig.PIN
}
