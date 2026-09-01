package kr.mooner510.data

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

class AttachmentStore(context: Context, private val crypto: CryptoManager) {
    private val directory = File(context.filesDir, "attachments").apply { mkdirs() }
    fun put(bytes: ByteArray, id: String = UUID.randomUUID().toString()): String = put(ByteArrayInputStream(bytes), id)
    fun put(input: InputStream, id: String = UUID.randomUUID().toString()): String { val file = File(directory, "$id.enc"); crypto.encryptFile(input, file); return file.absolutePath }
    fun open(encryptedPath: String): InputStream = crypto.openDecryptedFile(File(encryptedPath))
    fun delete(encryptedPath: String) { File(encryptedPath).delete() }
}
