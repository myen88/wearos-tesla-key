package com.myen88.wearkey.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.myen88.wearkey.protocol.crypto.EcPoints
import com.myen88.wearkey.protocol.crypto.ProtocolException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyAgreement

/**
 * P-256 key that never leaves Android Keystore. PURPOSE_AGREE_KEY is enough
 * for the vehicle-command handshake, which uses ECDH rather than a client ECDSA
 * signature. [isPrivateKeyExportable] is false on a real watch: Keystore
 * returns a null encoding for the private key.
 */
class AndroidKeystoreKey {
    private val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    fun exists(): Boolean = keyStore.containsAlias(ALIAS)

    fun generate() {
        if (exists()) return
        try {
            create(strongBox = true)
        } catch (first: Exception) {
            if (exists()) delete()
            try {
                create(strongBox = false)
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw ProtocolException(
                    "This watch could not create a hardware key (${second.message ?: "keystore error"}).",
                    second,
                )
            }
        }
    }

    fun delete() {
        if (exists()) keyStore.deleteEntry(ALIAS)
    }

    fun publicUncompressed(): ByteArray {
        val cert = keyStore.getCertificate(ALIAS)
            ?: throw ProtocolException("The watch key is missing. Create it again.")
        val publicKey = cert.publicKey as? ECPublicKey
            ?: throw ProtocolException("The watch key is not an elliptic-curve key.")
        return EcPoints.encode(publicKey)
    }

    fun isPrivateKeyExportable(): Boolean {
        val key = keyStore.getKey(ALIAS, null) ?: return false
        return key.encoded != null
    }

    /** Raw ECDH secret from the keystore. [EcPoints.sharedX] normalizes it to 32 bytes. */
    fun ecdhSecret(peerUncompressed: ByteArray): ByteArray {
        val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
            ?: throw ProtocolException("The watch key is missing. Create it again.")
        val peer = EcPoints.toPublicKey(peerUncompressed)
        val agreement = try {
            KeyAgreement.getInstance("ECDH", ANDROID_KEY_STORE)
        } catch (_: Exception) {
            KeyAgreement.getInstance("ECDH")
        }
        return try {
            agreement.init(privateKey)
            agreement.doPhase(peer, true)
            agreement.generateSecret()
        } catch (error: Exception) {
            throw ProtocolException(
                "This watch could not run the key agreement in Android Keystore (${error.message ?: "ECDH failed"}).",
                error,
            )
        }
    }

    private fun create(strongBox: Boolean) {
        val builder = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setUserAuthenticationRequired(false)
        if (strongBox) builder.setIsStrongBoxBacked(true)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE)
        generator.initialize(builder.build())
        generator.generateKeyPair()
    }

    companion object {
        const val ALIAS = "tesla-watch-key-p256"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}
