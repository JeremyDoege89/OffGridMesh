package dev.offgridmesh.crypto

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * "Team passphrase encryption" (Build Guide §10) — the construction, documented in README:
 *
 *  key   = PBKDF2-HMAC-SHA256(NFC(passphrase), salt = "dev.offgridmesh/team-key/v1", 600 000 iterations, 256 bit)
 *  tag   = first 4 bytes of HMAC-SHA256(key, "dev.offgridmesh/team-tag/v1")  (advertised; picks who to connect to)
 *  frame = 0x01 ‖ nonce(12, random) ‖ AES-256-GCM(key, nonce, plaintext, aad = 0x01)
 *
 * Platform JCA primitives only; nothing hand-rolled. The salt is fixed because every member must derive the
 * same key from the passphrase alone, so passphrase strength is the whole story — onboarding asks for a long one.
 * What it buys: non-members can't read or inject traffic. What it doesn't: forward secrecy, per-person
 * identity, or protection from anyone who knows the passphrase. Never call it "end-to-end encrypted".
 */
object TeamCrypto {
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 8
    private val SALT = "dev.offgridmesh/team-key/v1".toByteArray()
    private val TAG_INFO = "dev.offgridmesh/team-tag/v1".toByteArray()

    fun normalize(passphrase: String): String = Normalizer.normalize(passphrase.trim(), Normalizer.Form.NFC)

    /** Slow on purpose (≈ 1 s on a phone): run off the main thread. */
    fun deriveKey(passphrase: String, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(normalize(passphrase).toCharArray(), SALT, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Never 0 — 0 is reserved for "open mesh". */
    fun teamTag(key: ByteArray): Int {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val t = ByteBuffer.wrap(mac.doFinal(TAG_INFO)).int
        return if (t == 0) 1 else t
    }
}

/** Seals every frame that leaves the phone; [open] returns null for anything not sealed with our key. */
interface FrameCrypto {
    /** Advertised so only phones on the same team connect. 0 = open mesh. */
    val teamTag: Int
    val encrypted: Boolean
    fun seal(plain: ByteArray): ByteArray
    fun open(frame: ByteArray): ByteArray?
}

/** Open mesh (decision D4's secondary mode): visibly distinct in the UI, never the silent default. */
object OpenFrames : FrameCrypto {
    private const val MODE: Byte = 0
    override val teamTag = 0
    override val encrypted = false
    override fun seal(plain: ByteArray) = byteArrayOf(MODE) + plain
    override fun open(frame: ByteArray) = if (frame.isNotEmpty() && frame[0] == MODE) frame.copyOfRange(1, frame.size) else null
}

class AesGcmFrames(key: ByteArray) : FrameCrypto {
    private val secret = SecretKeySpec(key.copyOf(), "AES")
    private val random = SecureRandom()
    override val teamTag = TeamCrypto.teamTag(key)
    override val encrypted = true

    override fun seal(plain: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        c.updateAAD(AAD)
        return byteArrayOf(MODE) + nonce + c.doFinal(plain)
    }

    override fun open(frame: ByteArray): ByteArray? {
        if (frame.size < 1 + NONCE + 16 || frame[0] != MODE) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, frame, 1, NONCE))
        c.updateAAD(AAD)
        return try {
            c.doFinal(frame, 1 + NONCE, frame.size - 1 - NONCE)
        } catch (_: AEADBadTagException) {
            null
        }
    }

    private companion object {
        const val MODE: Byte = 1
        const val NONCE = 12
        val AAD = byteArrayOf(MODE)
    }
}
