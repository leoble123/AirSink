package com.airsink.airplay

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class RtspResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val ok get() = status in 200..299
    fun plist(): Map<*, *>? =
        if (body.isNotEmpty() && headers["content-type"]?.contains("plist") == true) BPlist.decode(body) as? Map<*, *> else null
}

class RtspException(val status: Int, message: String) : Exception(message)

/**
 * RTSP/HTTP-style request channel used for AirPlay control. After pairing, [enableEncryption]
 * switches it to HomeKit's framed ChaCha20-Poly1305 encryption.
 */
class RtspConnection(host: String, port: Int, timeoutMs: Int = 5000) {
    private val socket = Socket().apply {
        tcpNoDelay = true
        connect(InetSocketAddress(host, port), timeoutMs)
        soTimeout = 10_000
    }
    val localAddress: String = socket.localAddress.hostAddress ?: "0.0.0.0"

    private var input: InputStream = socket.getInputStream().buffered()
    private var output: OutputStream = socket.getOutputStream()
    private var cseq = 0
    var extraHeaders: Map<String, String> = emptyMap()

    fun enableEncryption(writeKey: ByteArray, readKey: ByteArray) {
        output = EncryptedOutputStream(socket.getOutputStream(), writeKey)
        input = DecryptedInputStream(input, readKey).buffered()
    }

    @Synchronized
    fun request(
        method: String,
        uri: String,
        body: ByteArray? = null,
        contentType: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): RtspResponse {
        val sb = StringBuilder()
        // AirPlay receivers speak RTSP/1.0 for everything, including the HTTP-like endpoints.
        sb.append("$method $uri RTSP/1.0\r\n")
        sb.append("CSeq: ${++cseq}\r\n")
        (extraHeaders + headers).forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body?.size ?: 0}\r\n\r\n")
        val packet = ByteArrayOutputStream()
        packet.write(sb.toString().toByteArray())
        if (body != null) packet.write(body)
        output.write(packet.toByteArray())
        output.flush()
        return readResponse()
    }

    private fun readResponse(): RtspResponse {
        val statusLine = readLine()
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: throw RtspException(-1, "Bad status line: $statusLine")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return RtspResponse(status, headers, body)
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw EOFException("Connection closed")
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }

    fun close() {
        try { socket.close() } catch (_: Exception) {}
    }
}

/** HomeKit framing: [len:2 LE][ciphertext:len][tag:16], AAD = the length bytes. */
class EncryptedOutputStream(private val out: OutputStream, private val key: ByteArray) : OutputStream() {
    private var counter = 0L

    override fun write(b: Int) = write(byteArrayOf(b.toByte()))

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (o < end) {
            val n = minOf(1024, end - o)
            val aad = byteArrayOf((n and 0xFF).toByte(), (n shr 8).toByte())
            val sealed = Crypto.seal(key, Crypto.nonce(counter++), aad, b, o, n)
            out.write(aad)
            out.write(sealed)
            o += n
        }
    }

    override fun flush() = out.flush()
}

class DecryptedInputStream(private val input: InputStream, private val key: ByteArray) : InputStream() {
    private var counter = 0L
    private var block = ByteArray(0)
    private var pos = 0

    override fun read(): Int {
        if (!fill()) return -1
        return block[pos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!fill()) return -1
        val n = minOf(len, block.size - pos)
        System.arraycopy(block, pos, b, off, n)
        pos += n
        return n
    }

    private fun fill(): Boolean {
        while (pos >= block.size) {
            val header = readFully(2) ?: return false
            val len = (header[0].toInt() and 0xFF) or ((header[1].toInt() and 0xFF) shl 8)
            val sealed = readFully(len + 16) ?: return false
            block = Crypto.open(key, Crypto.nonce(counter++), header, sealed)
            pos = 0
        }
        return true
    }

    private fun readFully(n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r < 0) return null
            read += r
        }
        return buf
    }
}
