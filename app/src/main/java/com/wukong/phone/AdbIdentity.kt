package com.wukong.phone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.json.JSONObject
import java.math.BigInteger
import java.security.*
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ADB needs an exportable RSA key for protocol signing; its stored form is Keystore-encrypted. */
data class AdbIdentity(val privateKey: PrivateKey, val certificate: Certificate) {
    companion object {
        @Synchronized fun load(context: Context): AdbIdentity {
            val preferences = context.getSharedPreferences("adb_identity", Context.MODE_PRIVATE)
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val wrappingKey = (keyStore.getKey("wukong.adb.identity", null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                    init(KeyGenParameterSpec.Builder("wukong.adb.identity", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    generateKey()
                }
            val existing = preferences.getString("encrypted", null)
            if (existing != null) {
                val bytes = Base64.decode(existing, Base64.NO_WRAP)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                val plaintext = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
                try {
                    val json = JSONObject(String(plaintext, Charsets.UTF_8))
                    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(json.getString("private"), Base64.NO_WRAP)))
                    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(Base64.decode(json.getString("certificate"), Base64.NO_WRAP).inputStream())
                    return AdbIdentity(privateKey, certificate)
                } finally { plaintext.fill(0) }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=Wukong ADB")
            val generator = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger(128, SecureRandom())))
                setSignature(algorithm); setIssuer(name); setSubject(name)
                setStartDate(Time(Date(System.currentTimeMillis() - 86_400_000)))
                setEndDate(Time(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
            }
            val tbs = generator.generateTBSCertificate()
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(tbs.encoded) }.sign()
            val certificateBytes = DERSequence(arrayOf(tbs, algorithm, DERBitString(signature))).encoded
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateBytes.inputStream())
            val plaintext = JSONObject().put("private", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
                .put("certificate", Base64.encodeToString(certificateBytes, Base64.NO_WRAP)).toString().toByteArray(Charsets.UTF_8)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrappingKey) }
                val encrypted = cipher.iv + cipher.doFinal(plaintext)
                check(preferences.edit().putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "无法保存无线调试身份" }
            } finally { plaintext.fill(0) }
            return AdbIdentity(pair.private, certificate)
        }
    }
}
