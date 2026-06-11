package com.example.testwatch

object KioskConfig {
    // TODO: replace with the real backend endpoint that records watch reboots.
    const val BOOT_NOTIFY_URL = "https://example.com/api/watch/boot"

    const val BOOT_NOTIFY_TIMEOUT_MS = 4000
    const val BOOT_NOTIFY_MAX_ATTEMPTS = 3
    const val BOOT_NOTIFY_RETRY_DELAY_MS = 1500L

    const val PIN_LENGTH = 4
    const val PIN = "2365"
}
