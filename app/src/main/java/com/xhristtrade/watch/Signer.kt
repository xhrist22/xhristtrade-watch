package com.xhristtrade.watch

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

// ⌚🔐 _QT_WATCH_ORDERS_SIGNED_2026_10_22 — this watch's own order key. An EC P-256 pair made INSIDE Android Keystore (StrongBox chip when
// the watch has one): the private half never leaves the hardware — not readable by this app, not in a backup, not in the APK. The watch
// viewer code in BuildConfig can only READ the desk; placing, closing or even seeing a trade needs a request signed here, from a key Sam
// approved on his QT page after matching the code below with the one QT shows.
object Signer {
    private const val ALIAS = "xt-orders-v1"
    private const val B32 = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"   // same alphabet as the server (lib/watchorders.js)
    private val rng = SecureRandom()

    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun create(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    @Synchronized
    private fun ensure(): KeyStore {
        val ks = store()
        if (!ks.containsAlias(ALIAS)) {
            try { create(true) } catch (e: StrongBoxUnavailableException) { create(false) } catch (e: Exception) { create(false) }
        }
        return ks
    }

    /** X.509 SubjectPublicKeyInfo bytes of the public half (safe to send). */
    fun publicDer(): ByteArray = ensure().getCertificate(ALIAS).publicKey.encoded

    fun publicB64(): String = Base64.encodeToString(publicDer(), Base64.NO_WRAP)

    /** The id the server files this watch under: sha256(public key) → first 16 hex. */
    fun deviceId(): String = sha256(publicDer()).joinToString("") { "%02x".format(it) }.take(16)

    /** The pairing code, worked out HERE from our own key — Sam approves only if QT shows the same one. */
    fun pairCode(): String {
        val h = MessageDigest.getInstance("SHA-256").apply { update("xt-pair:".toByteArray()); update(publicDer()) }.digest()
        val s = (0 until 8).joinToString("") { B32[(h[it].toInt() and 0xff) % B32.length].toString() }
        return s.take(4) + "-" + s.drop(4)
    }

    /** base64 DER ECDSA-SHA256 signature over the exact payload bytes the server will verify. */
    fun sign(payload: String): String {
        val key = ensure().getKey(ALIAS, null) as PrivateKey
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(key); update(payload.toByteArray(Charsets.UTF_8)) }.sign()
        return Base64.encodeToString(sig, Base64.NO_WRAP)
    }

    fun nonce(): String {
        val a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (0 until 24).joinToString("") { a[rng.nextInt(a.length)].toString() }
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
}
