package com.dallas.cabletv.data.companion

import android.annotation.SuppressLint
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import javax.security.auth.x500.X500Principal

/** Which identity a connection presents. Each has its own key in the Android keystore. */
enum class IdentityRole(val alias: String, val rsa: Boolean, val commonName: String) {
    /** The TV app's server. */
    TV("cabletv_companion_tv", rsa = false, commonName = "Cable TV"),

    /** The phone companion, toward the TV app. */
    PHONE("cabletv_companion_phone", rsa = false, commonName = "Cable TV phone"),

    /** The phone companion, toward the TV's Android TV Remote Service, which needs an RSA key. */
    ATV_CLIENT("cabletv_atv_remote_client", rsa = true, commonName = "Cable TV remote")
}

/**
 * Keys and TLS settings for the phone companion. Each role's key is made once in the Android
 * keystore, where it cannot be copied out, with a self-signed certificate. No certificate
 * authority is trusted: both sides accept any certificate during the handshake and then compare
 * its fingerprint with the one pairing stored. That check is the caller's job, right after the
 * handshake, before anything else is sent or acted on.
 */
object CompanionIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val YEAR_MS = 365L * 24 * 60 * 60 * 1000

    /** This app's certificate for [role], made on first use. */
    @Synchronized
    fun certificate(role: IdentityRole): X509Certificate {
        ensureKey(role)
        return keyStore().getCertificate(role.alias) as X509Certificate
    }

    /** SHA-256 fingerprint of a certificate, as pairing stores it. */
    fun fingerprint(certificate: Certificate): ByteArray = CompanionProtocol.fingerprint(certificate.encoded)

    /**
     * TLS settings that present [role]'s certificate and accept any certificate from the other
     * side. Use it for one socket or server socket at a time; it is cheap to make another.
     */
    @Synchronized
    fun sslContext(role: IdentityRole): SSLContext {
        ensureKey(role)
        val ks = keyStore()
        val key = ks.getKey(role.alias, null) as PrivateKey
        val chain = ks.getCertificateChain(role.alias).map { it as X509Certificate }.toTypedArray()
        val context = SSLContext.getInstance("TLS")
        context.init(arrayOf(FixedKeyManager(role.alias, key, chain)), arrayOf(AcceptAnyCertificate), SecureRandom())
        return context
    }

    /** Removes [role]'s key, so the next use makes a new one. Every pairing that used it stops working. */
    @Synchronized
    fun reset(role: IdentityRole) {
        runCatching { keyStore().deleteEntry(role.alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey(role: IdentityRole) {
        if (keyStore().containsAlias(role.alias)) return
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(role.alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setCertificateSubject(X500Principal("CN=${role.commonName}"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 24L * 60 * 60 * 1000))
            .setCertificateNotAfter(Date(now + 30 * YEAR_MS))
            // TLS signs with several digests depending on the version, so all of them are allowed.
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
        val generator = if (role.rsa) {
            spec.setKeySize(2048)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE)
        } else {
            spec.setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        }
        generator.initialize(spec.build())
        generator.generateKeyPair()
    }

    /** Always presents one key, so other keys in the keystore are never offered by mistake. */
    private class FixedKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>
    ) : X509ExtendedKeyManager() {
        private fun fits(keyType: String?): Boolean {
            if (keyType == null) return true
            val type = keyType.uppercase()
            return when (key.algorithm.uppercase()) {
                "EC" -> type.startsWith("EC")
                "RSA" -> type.contains("RSA")
                else -> true
            }
        }

        private fun pick(keyTypes: Array<out String>?): String? =
            if (keyTypes == null || keyTypes.any { fits(it) }) alias else null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = if (fits(keyType)) arrayOf(alias) else null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = pick(keyType)
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = pick(keyType)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = if (fits(keyType)) arrayOf(alias) else null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (fits(keyType)) alias else null
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = if (fits(keyType)) alias else null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == this.alias) chain else null
        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == this.alias) key else null
    }

    /**
     * Accepts any certificate during the handshake. Safe only because every caller compares the
     * other side's certificate fingerprint with the paired one before trusting the connection.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptAnyCertificate : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
