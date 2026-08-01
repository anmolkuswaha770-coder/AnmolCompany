package com.anmolcompany.mesh

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SecurityHelper provides ECDH-based key agreement and AES-GCM encryption utilities.
 *
 * Usage:
 *  - Call SecurityHelper.getPublicKey() to obtain this device's public key bytes.
 *  - After exchanging public keys with a peer, call deriveSharedSecret(peerPubBytes)
 *    to obtain a SecretKey that can be used with encrypt()/decrypt().
 *
 * Implementation notes:
 *  - Uses curve secp256r1 (prime256v1).
 *  - Derives a 256-bit AES key using HKDF(SHA-256) over the ECDH shared secret.
 *  - Uses AES/GCM/NoPadding with 12-byte IV and 128-bit auth tag.
 */
object SecurityHelper {
    private val secureRandom = SecureRandom()
    private val keyPair: KeyPair = generateECKeyPair()

    private fun generateECKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        val ecSpec = ECGenParameterSpec("secp256r1")
        kpg.initialize(ecSpec, secureRandom)
        return kpg.generateKeyPair()
    }

    fun getPublicKey(): ByteArray = keyPair.public.encoded

    /**
     * Derives a 256-bit AES key using ECDH and HKDF-SHA256.
     */
    fun deriveSharedSecret(peerPublicKeyBytes: ByteArray): SecretKey {
        val kf = KeyFactory.getInstance("EC")
        val pubSpec = X509EncodedKeySpec(peerPublicKeyBytes)
        val peerPub: PublicKey = kf.generatePublic(pubSpec)

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(keyPair.private)
        ka.doPhase(peerPub, true)
        val shared = ka.generateSecret() // raw shared secret bytes

        // HKDF extract-and-expand (SHA-256) to derive 32 bytes
        val salt = ByteArray(32) // zeros
        val prk = hkdfExtract(salt, shared)
        val okm = hkdfExpand(prk, "AES-GCM key".toByteArray(Charsets.UTF_8), 32)
        return SecretKeySpec(okm, "AES")
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(salt, "HmacSHA256")
        mac.init(key)
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var copied = 0
        var counter = 1
        while (copied < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            val output = mac.doFinal()
            val toCopy = minOf(output.size, length - copied)
            System.arraycopy(output, 0, result, copied, toCopy)
            copied += toCopy
            previous = output
            counter++
        }
        return result
    }

    /**
     * Encrypts plaintext and returns nonce||ciphertext (nonce is 12 bytes).
     */
    fun encrypt(aesKey: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey, spec)
        val ciphertext = cipher.doFinal(plaintext)
        // return iv + ciphertext
        val out = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
        return out
    }

    /**
     * Decrypts data produced by encrypt(). Expects first 12 bytes to be IV.
     */
    fun decrypt(aesKey: SecretKey, data: ByteArray): ByteArray {
        if (data.size < 13) throw IllegalArgumentException("Encrypted payload too short")
        val iv = ByteArray(12)
        System.arraycopy(data, 0, iv, 0, 12)
        val ciphertext = ByteArray(data.size - 12)
        System.arraycopy(data, 12, ciphertext, 0, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, aesKey, spec)
        return cipher.doFinal(ciphertext)
    }
}
