package kr.mooner510.backup

import android.content.Context
import android.net.Uri
import kr.mooner510.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.*
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupManager(
    private val context: Context,
    private val repository: NodeMapRepository,
    private val attachmentStore: AttachmentStore,
) {
    suspend fun export(destination: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "백업 비밀번호는 8자 이상이어야 합니다." }
        val temp = File.createTempFile("nodemap-export-", ".zip", context.cacheDir)
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(temp))).use { zip ->
                writeText(
                    zip,
                    "manifest.json",
                    JSONObject().apply {
                        put("format", "nodemap")
                        put("version", 2)
                        put("createdAt", Instant.now().toEpochMilli())
                    }.toString(),
                )
                writeLines(zip, "track_points.jsonl", repository.allTrackPoints().map { it.exportJson().toString() })
                writeLines(zip, "events.jsonl", repository.allEvents().map { it.exportJson().toString() })
                writeLines(
                    zip,
                    "notification_rules.jsonl",
                    repository.notificationRules().map {
                        JSONObject().apply { put("id", it.id); put("payload", it.toJson()) }.toString()
                    },
                )
                writeLines(
                    zip,
                    "pin_templates.jsonl",
                    repository.pinTemplates().map {
                        JSONObject().apply { put("id", it.id); put("payload", it.toJson()) }.toString()
                    },
                )
                writeLines(
                    zip,
                    "pin_types.jsonl",
                    repository.allPinTypes().map {
                        JSONObject().apply { put("id", it.id); put("payload", it.toJson()) }.toString()
                    },
                )
                writeLines(
                    zip,
                    "pin_rules.jsonl",
                    repository.allPinRules().map {
                        JSONObject().apply { put("id", it.id); put("payload", it.toJson()) }.toString()
                    },
                )
                val attachments = repository.allAttachments()
                writeLines(zip, "attachments.jsonl", attachments.map { it.exportJson().toString() })
                attachments.forEach { attachment ->
                    openAttachment(attachment)?.use { input ->
                        zip.putNextEntry(ZipEntry("attachments/${attachment.id}"))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
            context.contentResolver.openOutputStream(destination, "w")!!.use { output ->
                FileInputStream(temp).use { input -> encryptPortable(input, output, password) }
            }
        } finally {
            password.fill('\u0000')
            temp.delete()
        }
    }

    suspend fun restore(source: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= 8)
        val temp = File.createTempFile("nodemap-restore-", ".zip", context.cacheDir)
        val unpack = File(context.cacheDir, "nodemap-restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(source)!!.use { input ->
                FileOutputStream(temp).use { output -> decryptPortable(input, output, password) }
            }
            unzipSafely(temp, unpack)
            val manifest = JSONObject(File(unpack, "manifest.json").readText())
            val version = manifest.optInt("version")
            require(manifest.optString("format") == "nodemap" && version in 1..2) {
                "지원하지 않는 NodeMap 백업입니다."
            }

            val points = readJsonLines(File(unpack, "track_points.jsonl")).map(::trackPointFromExport)
            val events = readJsonLines(File(unpack, "events.jsonl")).map(::eventFromExport)
            val legacyRules = readJsonLines(File(unpack, "notification_rules.jsonl")).map {
                NotificationRule.fromJson(it.getString("id"), it.getJSONObject("payload"))
            }
            val legacyTemplates = readJsonLines(File(unpack, "pin_templates.jsonl")).map {
                PinTemplate.fromJson(it.getString("id"), it.getJSONObject("payload"))
            }
            val pinTypes = if (version >= 2) {
                readJsonLines(File(unpack, "pin_types.jsonl")).map {
                    PinType.fromJson(it.getString("id"), it.getJSONObject("payload"))
                }
            } else {
                emptyList()
            }
            val pinRules = if (version >= 2) {
                readJsonLines(File(unpack, "pin_rules.jsonl")).map {
                    PinRule.fromJson(it.getString("id"), it.getJSONObject("payload"))
                }
            } else {
                emptyList()
            }
            val attachments = readJsonLines(File(unpack, "attachments.jsonl")).map(::attachmentFromExport)

            repository.clearForRestore()
            points.forEach { repository.insertTrackPoint(it.copy(id = 0)) }
            events.forEach { repository.insertEvent(it) }

            // Keep v1 compatibility first. v2 entities are restored afterwards so their exact
            // type/rule configuration wins over any legacy migration with the same IDs.
            legacyRules.forEach { repository.upsertNotificationRule(it) }
            legacyTemplates.forEach { repository.upsertPinTemplate(it) }
            pinTypes.forEach { repository.restorePinType(it) }
            pinRules.forEach { repository.restorePinRule(it) }

            attachments.forEach { record ->
                val file = File(unpack, "attachments/${record.id}")
                if (file.isFile) {
                    file.inputStream().use {
                        repository.addEncryptedAttachment(record.eventId, record.kind, record.mimeType, it, record.id)
                    }
                } else if (!record.externalUri.isNullOrBlank()) {
                    repository.addExternalAttachment(
                        record.eventId,
                        record.kind,
                        record.mimeType,
                        record.externalUri,
                        record.id,
                    )
                }
            }
        } finally {
            password.fill('\u0000')
            temp.delete()
            unpack.deleteRecursively()
        }
    }

    private fun openAttachment(record: AttachmentRecord): InputStream? = when {
        record.encryptedPath.isNotBlank() -> runCatching { attachmentStore.open(record.encryptedPath) }.getOrNull()
        !record.externalUri.isNullOrBlank() -> runCatching {
            context.contentResolver.openInputStream(Uri.parse(record.externalUri))
        }.getOrNull()
        else -> null
    }

    private fun encryptPortable(input: InputStream, output: OutputStream, password: CharArray) {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        }
        output.write(MAGIC)
        output.write(1)
        output.write(salt.size)
        output.write(salt)
        output.write(iv.size)
        output.write(iv)
        CipherOutputStream(output, cipher).use { input.copyTo(it) }
    }

    private fun decryptPortable(input: InputStream, output: OutputStream, password: CharArray) {
        val buffered = BufferedInputStream(input)
        require(buffered.readNBytes(MAGIC.size).contentEquals(MAGIC))
        require(buffered.read() == 1)
        val salt = buffered.readNBytes(buffered.read().also { require(it in 16..64) })
        val iv = buffered.readNBytes(buffered.read().also { require(it in 12..32) })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        }
        CipherInputStream(buffered, cipher).use { it.copyTo(output) }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(bytes, "AES")
    }

    private fun writeText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray())
        zip.closeEntry()
    }

    private fun writeLines(zip: ZipOutputStream, name: String, lines: List<String>) =
        writeText(zip, name, lines.joinToString("\n"))

    private fun readJsonLines(file: File) = if (!file.isFile) {
        emptyList()
    } else {
        file.useLines { lines -> lines.filter { it.isNotBlank() }.map(::JSONObject).toList() }
    }

    private fun unzipSafely(zipFile: File, destination: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                require(output.path.startsWith(destination.canonicalPath + File.separator))
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private val MAGIC = "NODEMAP".toByteArray(Charsets.US_ASCII)
    }
}

private fun TrackPoint.exportJson() = JSONObject().apply {
    put("timestamp", timestamp)
    put("payload", toJson())
}

private fun trackPointFromExport(json: JSONObject) =
    TrackPoint.fromJson(0, json.getLong("timestamp"), json.getJSONObject("payload"))

private fun TimelineEvent.exportJson() = JSONObject().apply {
    put("id", id)
    put("timestamp", timestamp)
    put("type", type.name)
    put("payload", toJson())
}

private fun eventFromExport(json: JSONObject) = TimelineEvent.fromJson(
    json.getString("id"),
    json.getLong("timestamp"),
    EventType.valueOf(json.getString("type")),
    json.getJSONObject("payload"),
)

private fun AttachmentRecord.exportJson() = JSONObject().apply {
    put("id", id)
    put("eventId", eventId)
    put("kind", kind)
    mimeType?.let { put("mimeType", it) }
    put("createdAt", createdAt)
    externalUri?.let { put("externalUri", it) }
}

private fun attachmentFromExport(json: JSONObject) = AttachmentRecord(
    json.getString("id"),
    json.getString("eventId"),
    json.getString("kind"),
    json.optString("mimeType").takeIf { it.isNotBlank() },
    "",
    json.optLong("createdAt"),
    json.optString("externalUri").takeIf { it.isNotBlank() },
)
