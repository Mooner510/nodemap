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
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** API 31/32 safe replacement for InputStream.readNBytes(int) (API 33). */
fun InputStream.readExactly(length: Int): ByteArray {
    val bytes = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val read = read(bytes, offset, length - offset)
        require(read >= 0) { "Unexpected end of stream" }
        offset += read
    }
    return bytes
}

/**
 * Envelope encryption: a random AES data key is wrapped once by the Android Keystore key and cached in
 * memory, so per-row / per-stream crypto runs in-process instead of costing a Keystore IPC each time.
 * Blobs written before this (FORMAT_VERSION 1) are still decrypted with the Keystore key directly.
 */
class CryptoManager(private val dataKeyFile: File) {
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    private val encryptionKey: SecretKey by lazy { (keyStore.getKey(AES_ALIAS, null) as? SecretKey) ?: createAesKey() }
    private val hmacKey: SecretKey by lazy { (keyStore.getKey(HMAC_ALIAS, null) as? SecretKey) ?: createHmacKey() }
    private val random = SecureRandom()
    private val dataKey: SecretKey by lazy { loadOrCreateDataKey() }

    fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(128, iv)) }
        val encrypted = cipher.doFinal(plain)
        return ByteArrayOutputStream(encrypted.size + iv.size + 2).use { output -> output.write(FORMAT_SOFTWARE); output.write(iv.size); output.write(iv); output.write(encrypted); output.toByteArray() }
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val input = ByteArrayInputStream(blob); val version = input.read(); require(version == FORMAT_KEYSTORE || version == FORMAT_SOFTWARE)
        val ivLength = input.read(); require(ivLength in 12..32); val iv = input.readExactly(ivLength)
        return Cipher.getInstance(TRANSFORMATION).run { init(Cipher.DECRYPT_MODE, keyFor(version), GCMParameterSpec(128, iv)); doFinal(input.readBytes()) }
    }

    fun encryptStream(input: InputStream, output: OutputStream) {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, dataKey, GCMParameterSpec(128, iv)) }
        output.write(FORMAT_SOFTWARE); output.write(iv.size); output.write(iv)
        CipherOutputStream(output, cipher).use { input.copyTo(it) }
    }
    fun decryptingStream(input: InputStream): InputStream {
        val version = input.read(); require(version == FORMAT_KEYSTORE || version == FORMAT_SOFTWARE); val ivLength = input.read(); require(ivLength in 12..32); val iv = input.readExactly(ivLength)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, keyFor(version), GCMParameterSpec(128, iv)) }
        return CipherInputStream(input, cipher)
    }
    private fun keyFor(version: Int): SecretKey = if (version == FORMAT_SOFTWARE) dataKey else encryptionKey

    @Synchronized
    private fun loadOrCreateDataKey(): SecretKey {
        if (dataKeyFile.isFile) {
            val wrapped = dataKeyFile.readBytes()
            val input = ByteArrayInputStream(wrapped); require(input.read() == FORMAT_KEYSTORE)
            val iv = input.readExactly(input.read())
            val raw = Cipher.getInstance(TRANSFORMATION).run { init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv)); doFinal(input.readBytes()) }
            return SecretKeySpec(raw, "AES")
        }
        val raw = ByteArray(32).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, encryptionKey) }
        val encrypted = cipher.doFinal(raw)
        val blob = ByteArrayOutputStream().use { out -> out.write(FORMAT_KEYSTORE); out.write(cipher.iv.size); out.write(cipher.iv); out.write(encrypted); out.toByteArray() }
        dataKeyFile.parentFile?.mkdirs()
        val temp = File(dataKeyFile.parentFile, dataKeyFile.name + ".tmp")
        temp.writeBytes(blob); require(temp.renameTo(dataKeyFile)) { "Unable to persist data key" }
        return SecretKeySpec(raw, "AES")
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
    companion object { private const val KEYSTORE="AndroidKeyStore"; private const val AES_ALIAS="nodemap-local-aes-v1"; private const val HMAC_ALIAS="nodemap-cache-hmac-v1"; private const val TRANSFORMATION="AES/GCM/NoPadding"; private const val FORMAT_KEYSTORE=1; private const val FORMAT_SOFTWARE=2; private const val IV_LENGTH=12 }
}
