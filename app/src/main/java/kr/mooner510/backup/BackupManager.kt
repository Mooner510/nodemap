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
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupManager(
    private val context: Context,
    private val repository: NodeMapRepository,
    private val attachmentStore: AttachmentStore,
) {
    suspend fun export(
        destination: Uri,
        password: CharArray,
        onProgress: BackupProgressListener = {},
    ) = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "백업 비밀번호는 8자 이상이어야 합니다." }
        try {
            onProgress(BackupProgress("데이터 준비 중", 0f, "암호화 형식 정리"))
            repository.migrateLegacyEncryption()
            onProgress(BackupProgress("데이터 읽는 중", 0.05f, "위치 기록"))
            val points = repository.allTrackPoints()
            onProgress(BackupProgress("데이터 읽는 중", 0.15f, "이벤트"))
            val events = repository.allEvents()
            onProgress(BackupProgress("데이터 읽는 중", 0.2f, "설정과 첨부 목록"))
            val rules = repository.notificationRules()
            val templates = repository.pinTemplates()
            val pinTypes = repository.allPinTypes()
            val pinRules = repository.allPinRules()
            val attachments = repository.allAttachments()

            val sink = context.contentResolver.openOutputStream(destination, "w")
                ?: throw IOException("백업 파일을 열 수 없습니다.")
            // Zip → segmented encryption → destination in one pass: no plaintext temp file, no second copy.
            val encrypted = encryptingStream(BufferedOutputStream(sink, BUFFER), password)
            val zip = ZipOutputStream(BufferedOutputStream(encrypted, BUFFER))
            try {
                zip.setLevel(Deflater.BEST_SPEED)
                writeText(
                    zip,
                    "manifest.json",
                    JSONObject().apply {
                        put("format", "nodemap")
                        put("version", 2)
                        put("createdAt", Instant.now().toEpochMilli())
                    }.toString(),
                )
                writeLines(zip, "track_points.jsonl", points, { it.exportJson().toString() }) { done ->
                    onProgress(BackupProgress("암호화하여 저장 중", 0.22f + 0.3f * done / points.size, "위치 기록 $done / ${points.size}"))
                }
                writeLines(zip, "events.jsonl", events, { it.exportJson().toString() }) { done ->
                    onProgress(BackupProgress("암호화하여 저장 중", 0.52f + 0.08f * done / events.size, "이벤트 $done / ${events.size}"))
                }
                val idPayload = { id: String, payload: JSONObject -> JSONObject().apply { put("id", id); put("payload", payload) }.toString() }
                writeLines(zip, "notification_rules.jsonl", rules, { idPayload(it.id, it.toJson()) })
                writeLines(zip, "pin_templates.jsonl", templates, { idPayload(it.id, it.toJson()) })
                writeLines(zip, "pin_types.jsonl", pinTypes, { idPayload(it.id, it.toJson()) })
                writeLines(zip, "pin_rules.jsonl", pinRules, { idPayload(it.id, it.toJson()) })
                writeLines(zip, "attachments.jsonl", attachments, { it.exportJson().toString() })
                // Attachments are already-compressed media; deflating them only burns CPU.
                zip.setLevel(Deflater.NO_COMPRESSION)
                attachments.forEachIndexed { index, attachment ->
                    onProgress(
                        BackupProgress("첨부파일 담는 중", 0.6f + 0.4f * index / attachments.size, "첨부파일 ${index + 1} / ${attachments.size}"),
                    )
                    openAttachment(attachment)?.use { input ->
                        zip.putNextEntry(ZipEntry("attachments/${attachment.id}"))
                        input.copyTo(zip, BUFFER)
                        zip.closeEntry()
                    }
                }
                zip.close()
            } catch (e: Throwable) {
                // Leave no final segment, so a half-written file is rejected as truncated instead of
                // restoring as a valid-looking but incomplete backup.
                encrypted.abort()
                throw e
            }
            onProgress(BackupProgress("완료", 1f))
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun restore(
        source: Uri,
        password: CharArray,
        onProgress: BackupProgressListener = {},
    ) = withContext(Dispatchers.IO) {
        require(password.size >= 8)
        val unpack = File(context.cacheDir, "nodemap-restore-${System.nanoTime()}").apply { mkdirs() }
        try {
            val total = runCatching { context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } }.getOrNull() ?: -1L
            onProgress(BackupProgress("암호 해제 중", 0f, "비밀번호 키 생성"))
            val stream = context.contentResolver.openInputStream(source)
                ?: throw IOException("백업 파일을 열 수 없습니다.")
            // Decrypt straight into the unzipper: no decrypted temp archive written and read back.
            ProgressInputStream(stream, total) { read, size ->
                val f = if (size > 0) (read.toFloat() / size).coerceIn(0f, 1f) else 0f
                onProgress(BackupProgress("암호 해제 중", 0.4f * f, if (size > 0) "${formatBackupBytes(read)} / ${formatBackupBytes(size)}" else formatBackupBytes(read)))
            }.use { input -> unzipSafely(decryptingStream(BufferedInputStream(input, BUFFER), password), unpack) }
            onProgress(BackupProgress("데이터 분석 중", 0.45f))
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

            onProgress(BackupProgress("기존 데이터 교체 중", 0.5f, "기존 데이터 삭제"))
            repository.clearForRestore()
            points.chunked(RESTORE_CHUNK).forEachIndexed { index, chunk ->
                val done = minOf((index + 1) * RESTORE_CHUNK, points.size)
                onProgress(BackupProgress("위치 기록 복원 중", 0.5f + 0.3f * (index * RESTORE_CHUNK).toFloat() / points.size, "$done / ${points.size}"))
                repository.insertTrackPoints(chunk)
            }
            onProgress(BackupProgress("이벤트 복원 중", 0.8f, "${events.size}개"))
            repository.insertEvents(events)
            val eventIds = events.mapTo(HashSet()) { it.id }

            // Keep v1 compatibility first. v2 entities are restored afterwards so their exact
            // type/rule configuration wins over any legacy migration with the same IDs.
            onProgress(BackupProgress("설정 복원 중", 0.85f))
            legacyRules.forEach { repository.upsertNotificationRule(it) }
            legacyTemplates.forEach { repository.upsertPinTemplate(it) }
            pinTypes.forEach { repository.restorePinType(it) }
            pinRules.forEach { repository.restorePinRule(it) }

            // An attachment whose event is missing would violate the FK and abort a restore that has
            // already wiped the database, so skip such orphans.
            val restorable = attachments.filter { it.eventId in eventIds }
            restorable.forEachIndexed { index, record ->
                onProgress(BackupProgress("첨부파일 복원 중", 0.88f + 0.12f * index / restorable.size, "${index + 1} / ${restorable.size}"))
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
            onProgress(BackupProgress("완료", 1f))
        } finally {
            password.fill('\u0000')
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

    private fun encryptingStream(output: OutputStream, password: CharArray): GcmSegmentOutputStream {
        val random = SecureRandom()
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        output.write(MAGIC)
        output.write(FORMAT_SEGMENTED)
        output.write(salt.size)
        output.write(salt)
        output.write(nonce.size)
        output.write(nonce)
        return GcmSegmentOutputStream(output, deriveKey(password, salt), nonce)
    }

    private fun decryptingStream(input: InputStream, password: CharArray): InputStream {
        require(input.readExactly(MAGIC.size).contentEquals(MAGIC)) { "NodeMap 백업 파일이 아닙니다." }
        val format = input.read()
        require(format == FORMAT_SINGLE_GCM || format == FORMAT_SEGMENTED) { "지원하지 않는 백업 암호화 형식입니다." }
        val salt = input.readExactly(input.read().also { require(it in 16..64) })
        val iv = input.readExactly(input.read().also { require(it in 12..32) })
        val key = deriveKey(password, salt)
        if (format == FORMAT_SEGMENTED) return GcmSegmentInputStream(input, key, iv)
        // Old backups are one GCM message; Android buffers it whole before releasing any plaintext.
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return CipherInputStream(input, cipher)
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

    private fun <T> writeLines(
        zip: ZipOutputStream,
        name: String,
        items: List<T>,
        render: (T) -> String,
        onItems: (done: Int) -> Unit = {},
    ) {
        zip.putNextEntry(ZipEntry(name))
        items.forEachIndexed { index, item ->
            if (index > 0) zip.write('\n'.code)
            zip.write(render(item).toByteArray())
            if ((index + 1) % 1000 == 0) onItems(index + 1)
        }
        zip.closeEntry()
        onItems(items.size)
    }

    private fun readJsonLines(file: File) = if (!file.isFile) {
        emptyList()
    } else {
        file.useLines { lines -> lines.filter { it.isNotBlank() }.map(::JSONObject).toList() }
    }

    private fun unzipSafely(input: InputStream, destination: File) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val output = File(destination, entry.name).canonicalFile
                require(output.path.startsWith(destination.canonicalPath + File.separator))
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { zip.copyTo(it, BUFFER) }
                }
                zip.closeEntry()
            }
        }
    }

    companion object {
        private val MAGIC = "NODEMAP".toByteArray(Charsets.US_ASCII)
        private const val BUFFER = 256 * 1024
        private const val RESTORE_CHUNK = 2000
        private const val FORMAT_SINGLE_GCM = 1
        private const val FORMAT_SEGMENTED = 2
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
