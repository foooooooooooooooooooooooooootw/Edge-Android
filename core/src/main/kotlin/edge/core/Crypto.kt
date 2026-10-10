package edge.core

import java.math.BigInteger
import java.security.*
import java.security.spec.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Session crypto matching Node's createECDH('prime256v1') + AES-256-GCM framing. */
class Handshake {
    private val kp: KeyPair
    /** 65-byte uncompressed point (04||X||Y) as lowercase hex, like Node's getPublicKey('hex'). */
    val pubKeyHex: String

    init {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        kp = g.generateKeyPair()
        val w = (kp.public as java.security.interfaces.ECPublicKey).w
        pubKeyHex = (byteArrayOf(4) + fixed32(w.affineX) + fixed32(w.affineY)).hex()
    }

    /** Returns (aesKey, fingerprint). */
    fun finish(peerPubHex: String, peerKdf: Int = 1): Pair<ByteArray, String> {
        val raw = peerPubHex.unhex()
        require(raw.size == 65 && raw[0].toInt() == 4) { "bad peer key" }
        val params = AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1")); getParameterSpec(ECParameterSpec::class.java)
        }
        val pt = ECPoint(BigInteger(1, raw.copyOfRange(1, 33)), BigInteger(1, raw.copyOfRange(33, 65)))
        val peerKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(pt, params))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(kp.private)
        ka.doPhase(peerKey, true)
        val secret = ka.generateSecret()                       // 32-byte X coordinate
        if (peerKdf >= 2) {   // v2: separate labels, so the fingerprint says nothing about the key
            return hkdf(secret, "edge-aes-key", 32) to hkdf(secret, "edge-fingerprint", 16).hex()
        }
        val key = MessageDigest.getInstance("SHA-256").digest(secret)
        return key to key.hex().substring(0, 32)               // desktop: fingerprint = sha256(secret)[0:32 hex]
    }

    /** RFC 5869 HKDF-SHA256 with empty salt, single output block (len <= 32). Matches Node's hkdfSync. */
    private fun hkdf(ikm: ByteArray, info: String, len: Int): ByteArray {
        fun mac(k: ByteArray, d: ByteArray) = javax.crypto.Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(k, "HmacSHA256")); doFinal(d) }
        val prk = mac(ByteArray(32), ikm)
        return mac(prk, info.toByteArray() + byteArrayOf(1)).copyOf(len)
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }
}

class Aes(key: ByteArray) {
    private val k = SecretKeySpec(key, "AES")
    private val rnd = SecureRandom()

    /** -> IV(12) || TAG(16) || CIPHERTEXT, as Node writes it. */
    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        val out = c.doFinal(plain)                              // ciphertext || tag
        val ct = out.size - 16
        val r = ByteArray(12 + 16 + ct)
        System.arraycopy(iv, 0, r, 0, 12)
        System.arraycopy(out, ct, r, 12, 16)
        System.arraycopy(out, 0, r, 28, ct)
        return r
    }

    fun open(inner: ByteArray, off: Int, len: Int): ByteArray {
        require(len >= 28) { "short frame" }
        val iv = inner.copyOfRange(off, off + 12)
        val ctTag = ByteArray(len - 12)                         // JCE wants ciphertext || tag
        System.arraycopy(inner, off + 28, ctTag, 0, len - 28)
        System.arraycopy(inner, off + 12, ctTag, len - 28, 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv))
        return c.doFinal(ctTag)
    }
}
