package com.airsink.cast

import android.util.Log
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tiny HTTP server that serves one endless live audio stream to any number of listeners
 * on the LAN (used for Sonos, which pulls audio rather than having it pushed).
 */
class StreamServer(
    private val contentType: String,
    /** Bytes every new listener gets first (e.g. a WAV header). */
    private val preamble: () -> ByteArray = { ByteArray(0) },
) {
    private val server = ServerSocket(0, 8, InetAddress.getByName("0.0.0.0"))
    val port: Int get() = server.localPort
    private val clients = CopyOnWriteArrayList<ArrayBlockingQueue<ByteArray>>()
    @Volatile private var running = true

    init {
        thread(name = "stream-server", isDaemon = true) {
            while (running) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                thread(name = "stream-client", isDaemon = true) { serve(socket) }
            }
        }
    }

    fun broadcast(chunk: ByteArray) {
        for (q in clients) {
            // A slow listener loses audio rather than stalling everyone else.
            if (!q.offer(chunk)) { q.clear(); q.offer(chunk) }
        }
    }

    val listenerCount get() = clients.size

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                s.tcpNoDelay = true
                val reader = s.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return
                while (reader.readLine()?.isNotEmpty() == true) { /* skip headers */ }
                val out = s.getOutputStream()
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nCache-Control: no-cache\r\n" +
                    "Connection: close\r\nicy-name: AirSink\r\nServer: AirSink\r\n\r\n"
                out.write(headers.toByteArray())
                if (requestLine.startsWith("HEAD")) { out.flush(); return }
                out.write(preamble())
                out.flush()

                val queue = ArrayBlockingQueue<ByteArray>(512)
                clients += queue
                try {
                    while (running && !s.isClosed) {
                        val chunk = queue.poll(2, TimeUnit.SECONDS) ?: continue
                        out.write(chunk)
                    }
                } finally {
                    clients -= queue
                }
            } catch (e: Exception) {
                Log.d("StreamServer", "listener left: ${e.message}")
            }
        }
    }

    fun close() {
        running = false
        runCatching { server.close() }
        clients.clear()
    }
}
