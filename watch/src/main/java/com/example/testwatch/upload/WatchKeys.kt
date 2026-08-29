package com.example.testwatch.upload

/** This watch's SSH identity: a hardware-backed EC P-256 keypair in the Android Keystore
 *  (non-exportable — signing happens in the TEE), bridged to sshj's KeyProvider, plus the
 *  OpenSSH-format public key line to append to the server account's authorized_keys. */

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object WatchKeys {

    /** Returns the watch's keypair, generating it on first use. The private half never leaves
     *  the Keystore; only an opaque handle comes back, usable solely for signing.
     *
     *  P-256 (ecdsa-sha2-nistp256), not Ed25519: sshj's Ed25519 path needs raw key bytes,
     *  which Keystore keys by design cannot expose. ECDSA signing goes through JCA
     *  delayed-provider selection, which routes Keystore handles to the TEE. */
    fun getOrCreate(alias: String): KeyPair {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val entry = ks.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (entry != null) {
            return KeyPair(entry.certificate.publicKey, entry.privateKey)
        }
        val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        return kpg.generateKeyPair()
    }

    /** sshj bridge. sshj only ever calls getPrivate() to hand the key to a JCA Signature,
     *  so an opaque Keystore reference satisfies the interface. */
    fun keyProvider(alias: String): KeyProvider {
        val pair = getOrCreate(alias)
        return object : KeyProvider {
            override fun getPrivate(): PrivateKey = pair.private
            override fun getPublic(): PublicKey = pair.public
            override fun getType(): KeyType = KeyType.ECDSA256
        }
    }

    /** The single line to append to ~USER/.ssh/authorized_keys on the server, e.g.
     *  "ecdsa-sha2-nistp256 AAAA... watch-P-a6a11810". */
    fun authorizedKeysLine(alias: String, comment: String): String {
        val pub = getOrCreate(alias).public
        val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
        return "${KeyType.ECDSA256} ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
