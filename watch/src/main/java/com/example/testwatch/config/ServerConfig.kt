package com.example.testwatch.config

/** Direct-upload server config: MISR host, pinned host-key fingerprints, the shared account the
 *  watch authenticates as, and the Keystore alias of this watch's own SSH keypair. No password —
 *  auth is per-watch public key (see upload/WatchKeys.kt). */

object ServerConfig {
    const val HOST = "misr.sauder.ubc.ca"
    const val PORT = 16800

    /** Shared-account model (docs/new-arch-build.md, "Server access reality"): every watch
     *  authenticates as this user; per-watch identity comes from its own key in the account's
     *  authorized_keys and its own subdirectory under [REMOTE_BASE_DIR]. */
    const val USER = "edward"

    /** Base inbox on the server (group-writable). Each watch uploads into
     *  "[REMOTE_BASE_DIR]/<participantId>/", created on first upload. */
    const val REMOTE_BASE_DIR = "/data1/wearables"

    /** Android Keystore alias of this watch's non-exportable EC P-256 keypair. */
    const val KEYSTORE_ALIAS = "watch_ssh_key"

    /** SHA256 fingerprints of the server's host keys (ssh-keyscan, 2026-08-29).
     *  All three pinned so any negotiated host-key algorithm verifies. */
    val HOST_KEY_FINGERPRINTS = listOf(
        "SHA256:ggNscecIXhow6MfQcGwdaSUXj6N6k/2V38OgAvXD0uc", // ED25519
        "SHA256:AbAJ43Yw5iTJIXpHRhNB1fYV/mQW0Qzq4M6kQrTV5YE", // ECDSA
        "SHA256:0wMDYM7uf4fbVnQHGZLYzZr3FiAwFw7Cad3mECS0Wm8", // RSA
    )
}
