package kr.mooner510.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoManager {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private val encryptionKey: SecretKey get() = (keyStore.getKey(AES_ALIAS, null) as? SecretKey) ?: createAesKey()
    private val hmacKey: SecretKey get() = (keyStore.getKey(HMAC_ALIAS, null) as? SecretKey) ?: createHmacKey()

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream().use { output -> output.write(FORMAT_VERSION); output.write(cipher.iv.size); output.write(cipher.iv); output.write(encrypted); output.toByteArray() }
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val input = ByteArrayInputStream(blob); require(input.read() == FORMAT_VERSION)
        val ivLength = input.read(); require(ivLength in 12..32); val iv = input.readNBytes(ivLength)
        return Cipher.getInstance(TRANSFORMATION).run { init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv)); doFinal(input.readBytes()) }
    }

    fun encryptStream(input: InputStream, output: OutputStream) {
        val cipher = Cipher.getInstance(TRANSFORMATION); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        output.write(FORMAT_VERSION); output.write(cipher.iv.size); output.write(cipher.iv)
        CipherOutputStream(output, cipher).use { input.copyTo(it) }
    }
    fun decryptingStream(input: InputStream): InputStream {
        require(input.read() == FORMAT_VERSION); val ivLength = input.read(); require(ivLength in 12..32); val iv = input.readNBytes(ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv)) }
        return CipherInputStream(input, cipher)
    }
    fun encryptFile(source: InputStream, destination: File) { destination.parentFile?.mkdirs(); FileOutputStream(destination).use { encryptStream(source, it) } }
    fun openDecryptedFile(file: File): InputStream = decryptingStream(FileInputStream(file))
    fun keyedHash(value: String): String { val mac = Mac.getInstance("HmacSHA256"); mac.init(hmacKey); return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) } }
    private fun createAesKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
        init(KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()); generateKey()
    }
    private fun createHmacKey(): SecretKey = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).run {
        init(KeyGenParameterSpec.Builder(HMAC_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build()); generateKey()
    }
    companion object { private const val KEYSTORE="AndroidKeyStore"; private const val AES_ALIAS="nodemap-local-aes-v1"; private const val HMAC_ALIAS="nodemap-cache-hmac-v1"; private const val TRANSFORMATION="AES/GCM/NoPadding"; private const val FORMAT_VERSION=1 }
}
