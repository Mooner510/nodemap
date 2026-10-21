package kr.mooner510.backup

import kr.mooner510.data.readExactly
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/*
 * Streaming AES-GCM in independent segments. Android's AES/GCM Cipher (Conscrypt) buffers the whole
 * stream until doFinal, so a single-shot GCM over a multi-hundred-MB backup holds it all in memory and
 * stalls at the end. Here every segment is sealed on its own:
 *   segment = [final flag: 1 byte][ciphertext length: int][ciphertext + tag]
 * nonce = base nonce XOR segment index (reordering fails), and the final flag is AAD (truncation fails).
 */

internal const val GCM_SEGMENT_SIZE = 1024 * 1024
private const val TAG_BITS = 128
private const val TAG_BYTES = TAG_BITS / 8

private fun segmentNonce(base: ByteArray, index: Long): ByteArray = base.copyOf().also { nonce ->
    for (i in 0 until 8) {
        val pos = nonce.size - 1 - i
        nonce[pos] = (nonce[pos].toInt() xor (index ushr (8 * i)).toInt()).toByte()
    }
}

internal class GcmSegmentOutputStream(
    output: OutputStream,
    private val key: SecretKey,
    private val baseNonce: ByteArray,
) : OutputStream() {
    private val out = DataOutputStream(output)
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private val buffer = ByteArray(GCM_SEGMENT_SIZE)
    private var filled = 0
    private var index = 0L
    private var closed = false

    override fun write(b: Int) {
        if (filled == buffer.size) flushSegment(final = false)
        buffer[filled++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var offset = off
        var remaining = len
        while (remaining > 0) {
            if (filled == buffer.size) flushSegment(final = false)
            val n = minOf(remaining, buffer.size - filled)
            System.arraycopy(b, offset, buffer, filled, n)
            filled += n
            offset += n
            remaining -= n
        }
    }

    private fun flushSegment(final: Boolean) {
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, segmentNonce(baseNonce, index++)))
        cipher.updateAAD(byteArrayOf(if (final) 1 else 0))
        val sealed = cipher.doFinal(buffer, 0, filled)
        out.writeByte(if (final) 1 else 0)
        out.writeInt(sealed.size)
        out.write(sealed)
        filled = 0
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        out.use { flushSegment(final = true) }
    }

    /** Closes without the final segment; readers will reject the stream as truncated. */
    fun abort() {
        if (closed) return
        closed = true
        runCatching { out.close() }
    }
}

internal class GcmSegmentInputStream(
    input: InputStream,
    private val key: SecretKey,
    private val baseNonce: ByteArray,
) : InputStream() {
    private val source = DataInputStream(input)
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    private var plain = ByteArray(0)
    private var position = 0
    private var index = 0L
    private var finished = false

    override fun read(): Int {
        if (!fill()) return -1
        return plain[position++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, plain.size - position)
        System.arraycopy(plain, position, b, off, n)
        position += n
        return n
    }

    override fun available(): Int = plain.size - position

    /** Returns false only at the authenticated end of the stream. */
    private fun fill(): Boolean {
        while (position == plain.size) {
            if (finished) return false
            val flag = try {
                source.readUnsignedByte()
            } catch (_: EOFException) {
                throw IOException("백업 파일이 잘렸습니다.")
            }
            if (flag > 1) throw IOException("손상된 백업 파일입니다.")
            val length = source.readInt()
            if (length < TAG_BYTES || length > GCM_SEGMENT_SIZE + TAG_BYTES) throw IOException("손상된 백업 파일입니다.")
            val sealed = source.readExactly(length)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, segmentNonce(baseNonce, index++)))
            cipher.updateAAD(byteArrayOf(flag.toByte()))
            plain = try {
                cipher.doFinal(sealed)
            } catch (e: javax.crypto.AEADBadTagException) {
                throw IOException("비밀번호가 틀렸거나 백업 파일이 손상되었습니다.", e)
            }
            position = 0
            finished = flag == 1
        }
        return true
    }

    override fun close() = source.close()
}
