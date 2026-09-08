package kr.mooner510.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.time.Instant

class BackupServerClient(rawBaseUrl: String) {
    private val baseUrl = rawBaseUrl.trim().trimEnd('/')

    val configured: Boolean = runCatching {
        if (baseUrl.isBlank()) return@runCatching false
        val uri = URI(baseUrl)
        uri.scheme.equals("https", ignoreCase = true) && uri.host?.isNotBlank() == true && uri.userInfo == null
    }.getOrDefault(false)

    suspend fun metadata(idToken: String): RemoteBackupMetadata? = withContext(Dispatchers.IO) {
        val connection = open("/v1/backup", "GET", idToken)
        connection.useResponse { code, body ->
            requireSuccess(code, body)
            val json = JSONObject(body)
            if (!json.optBoolean("exists")) null else parseMetadata(json.getJSONObject("backup"))
        }
    }

    suspend fun upload(idToken: String, file: File, formatVersion: Int): RemoteBackupMetadata = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L)
        val connection = open("/v1/backup", "PUT", idToken).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("X-Backup-Format-Version", formatVersion.toString())
            setFixedLengthStreamingMode(file.length())
            readTimeout = 60_000
        }
        connection.outputStream.buffered().use { output ->
            file.inputStream().buffered().use { input -> input.copyTo(output) }
        }
        connection.useResponse { code, body ->
            requireSuccess(code, body)
            parseMetadata(JSONObject(body).getJSONObject("backup"))
        }
    }

    suspend fun download(
        idToken: String,
        destination: File,
        metadata: RemoteBackupMetadata,
    ) = withContext(Dispatchers.IO) {
        val connection = open("/v1/backup/content", "GET", idToken).apply {
            readTimeout = 5 * 60_000
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw errorFor(code, body)
        }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(destination.length() == metadata.sizeBytes) { "다운로드 크기가 서버 메타데이터와 다릅니다." }
            val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
            require(sha256.equals(metadata.sha256, ignoreCase = true)) { "다운로드 무결성 검증에 실패했습니다." }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun delete(idToken: String) = withContext(Dispatchers.IO) {
        val connection = open("/v1/backup", "DELETE", idToken)
        connection.useResponse { code, body -> requireSuccess(code, body) }
    }

    private fun open(path: String, method: String, idToken: String): HttpURLConnection {
        checkConfigured()
        return (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Authorization", "Bearer $idToken")
            setRequestProperty("Accept", "application/json")
        }
    }

    private fun checkConfigured() {
        require(configured) { "온라인 백업 서버 URL이 설정되지 않았거나 HTTPS URL이 아닙니다." }
    }

    private inline fun <T> HttpURLConnection.useResponse(block: (Int, String) -> T): T {
        return try {
            val code = responseCode
            val stream = if (code in 200..299) inputStream else errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            block(code, body)
        } finally {
            disconnect()
        }
    }

    private fun requireSuccess(code: Int, body: String) {
        if (code !in 200..299) throw errorFor(code, body)
    }

    private fun errorFor(code: Int, body: String): BackupServerException {
        val message = runCatching { JSONObject(body).optString("error") }
            .getOrNull()
            .takeUnless { it.isNullOrBlank() }
            ?: "백업 서버 요청에 실패했습니다. (HTTP $code)"
        return BackupServerException(code, message)
    }

    private fun parseMetadata(json: JSONObject) = RemoteBackupMetadata(
        id = json.getString("id"),
        createdAt = Instant.parse(json.getString("createdAt")),
        expiresAt = Instant.parse(json.getString("expiresAt")),
        sizeBytes = json.getLong("sizeBytes"),
        sha256 = json.getString("sha256"),
        formatVersion = json.getInt("formatVersion"),
    )
}

data class RemoteBackupMetadata(
    val id: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val sizeBytes: Long,
    val sha256: String,
    val formatVersion: Int,
)

class BackupServerException(
    val statusCode: Int,
    message: String,
) : IOException(message)
