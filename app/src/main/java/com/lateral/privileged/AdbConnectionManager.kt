package com.lateral.privileged

import android.content.Context
import android.os.Build
import android.util.Base64
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * UxSpace's ADB identity for the local wireless-debugging connection.
 *
 * Generates an RSA-2048 key pair and a self-signed X509 certificate on first use and
 * persists them under the app's `files/adb/` directory. After a one-time pairing with the
 * device's wireless-debugging dialog, the key is trusted permanently — subsequent
 * connections go straight through without another pairing.
 *
 * Single instance per process; obtained via [getInstance] and held by the
 * [ShizukuManager]/`PrivilegedService`.
 */
class AdbConnectionManager private constructor(
    context: Context,
) : AbsAdbConnectionManager() {

    private val keyFile = File(context.filesDir, "$DIR/$KEY_FILE")
    private val certFile = File(context.filesDir, "$DIR/$CERT_FILE")

    private lateinit var privateKey: PrivateKey
    private lateinit var certificate: X509Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        keyFile.parentFile?.mkdirs()
        if (keyFile.exists() && certFile.exists()) {
            load()
            if (certificate.subjectX500Principal.name.contains("UxSpace", ignoreCase = true)) {
                generate()
                store()
            }
        } else {
            generate()
            store()
        }
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = DEVICE_NAME

    private fun load() {
        val keyFactory = KeyFactory.getInstance("RSA")
        privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val pem = certFile.readText()
        val b64 = pem.lineSequence()
            .filterNot { it.startsWith("-----") || it.isBlank() }
            .joinToString("")
        val der = Base64.decode(b64, Base64.DEFAULT)
        val factory = CertificateFactory.getInstance("X.509")
        certificate = factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    /** Generate a fresh key pair and self-signed certificate. */
    private fun generate() {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val keyPair = generator.generateKeyPair()
        privateKey = keyPair.private

        val now = Date()
        val expiry = Date(now.time + VALIDITY_MS)
        val owner = X500Name(CERT_SUBJECT)
        val serial = BigInteger(64, SecureRandom())

        val info = X509CertInfo()
        info.set(X509CertInfo.VALIDITY, CertificateValidity(now, expiry))
        info.set(X509CertInfo.SERIAL_NUMBER, CertificateSerialNumber(serial))
        info.set(X509CertInfo.SUBJECT, CertificateSubjectName(owner))
        info.set(X509CertInfo.ISSUER, CertificateIssuerName(owner))
        info.set(X509CertInfo.KEY, CertificateX509Key(keyPair.public))
        info.set(X509CertInfo.VERSION, CertificateVersion(CertificateVersion.V3))
        info.set(
            X509CertInfo.ALGORITHM_ID,
            CertificateAlgorithmId(AlgorithmId.get(SIGNATURE_ALGORITHM)),
        )

        val extensions = CertificateExtensions()
        extensions.set(
            SubjectKeyIdentifierExtension.NAME,
            SubjectKeyIdentifierExtension(KeyIdentifier(keyPair.public).identifier),
        )
        extensions.set(
            PrivateKeyUsageExtension.NAME,
            PrivateKeyUsageExtension(now, expiry),
        )
        info.set(X509CertInfo.EXTENSIONS, extensions)

        val cert = X509CertImpl(info)
        cert.sign(privateKey, SIGNATURE_ALGORITHM)
        certificate = cert
    }

    /** Write the freshly generated key and certificate to disk. */
    private fun store() {
        keyFile.writeBytes(privateKey.encoded)
        val pem = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(Base64.encodeToString(certificate.encoded, Base64.DEFAULT))
            append("-----END CERTIFICATE-----\n")
        }
        certFile.writeText(pem)
    }

    companion object {
        private const val DIR = "adb"
        private const val KEY_FILE = "private.key"
        private const val CERT_FILE = "cert.pem"

        private const val DEVICE_NAME = "LATERAL_"
        private const val CERT_SUBJECT = "CN=LATERAL_, OU=ADB, O=LATERAL_"
        private const val SIGNATURE_ALGORITHM = "SHA256withRSA"
        private const val VALIDITY_MS = 30L * 365L * 24L * 60L * 60L * 1000L // ~30 years

        @Volatile
        private var instance: AdbConnectionManager? = null

        fun getInstance(context: Context): AdbConnectionManager =
            instance ?: synchronized(this) {
                instance ?: AdbConnectionManager(context.applicationContext)
                    .also { instance = it }
            }
    }
}
