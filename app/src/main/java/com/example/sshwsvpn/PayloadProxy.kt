package com.example.sshwsvpn

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Sends a hand-crafted HTTP/WebSocket-style "payload" over the raw socket before
 * handing the connection to JSch to continue the normal SSH handshake.
 * This is how "payload" based SSH-over-WS tunnels talk to a front server
 * (nginx/stunnel) that switches the connection through to the real SSH/Dropbear port.
 */
class PayloadProxy(
    private val host: String,
    private val port: Int,
    private val payload: String,
    private val sni: String,
    private val useTls: Boolean
) : Proxy {

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(
        socketFactory: SocketFactory?,
        targetHost: String?,
        targetPort: Int,
        timeout: Int
    ) {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), if (timeout > 0) timeout else 15000)

        val active: Socket = if (useTls) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, sni, port, true)
        } else {
            raw
        }

        val out = active.getOutputStream()
        val inp = active.getInputStream()

        // Send the payload (with [crlf] already expanded to \r\n by VpnConfig).
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()

        // Drain the HTTP-style response headers (until blank line) before SSH banner starts.
        val buf = StringBuilder()
        val single = ByteArray(1)
        var seenEmptyLine = false
        while (!seenEmptyLine) {
            val n = inp.read(single)
            if (n <= 0) break
            buf.append(single[0].toInt().toChar())
            if (buf.endsWith("\r\n\r\n")) seenEmptyLine = true
        }

        socket = active
        input = inp
        output = out
    }

    override fun getInputStream(): InputStream = input!!
    override fun getOutputStream(): OutputStream = output!!
    override fun getSocket(): Socket = socket!!

    override fun close() {
        try { socket?.close() } catch (_: Exception) {}
    }
}
