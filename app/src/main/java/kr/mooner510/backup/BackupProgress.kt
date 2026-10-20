package kr.mooner510.backup

import java.io.FilterInputStream
import java.io.InputStream

/** What a backup/restore is doing right now. [fraction] is overall progress in 0..1. */
data class BackupProgress(val phase: String, val fraction: Float, val detail: String? = null)

typealias BackupProgressListener = (BackupProgress) -> Unit

/** Maps a sub-task's 0..1 progress onto [from]..[to] of the overall operation. */
fun BackupProgressListener.scaled(from: Float, to: Float): BackupProgressListener = { p ->
    this(p.copy(fraction = from + (to - from) * p.fraction.coerceIn(0f, 1f)))
}

/** Reports bytes read through the stream; [total] <= 0 means unknown (reports no fraction movement). */
internal class ProgressInputStream(
    input: InputStream,
    private val total: Long,
    private val onBytes: (read: Long, total: Long) -> Unit,
) : FilterInputStream(input) {
    private var count = 0L
    private var reported = 0L

    override fun read(): Int = super.read().also { if (it >= 0) advance(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) advance(it.toLong()) }

    private fun advance(n: Long) {
        count += n
        if (count - reported >= STEP || (total > 0 && count >= total)) {
            reported = count
            onBytes(count, total)
        }
    }

    private companion object { const val STEP = 128 * 1024L }
}

fun formatBackupBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "${bytes / 1024L} KB"
}
