package com.example.sshwsvpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.jcraft.jsch.Channel
import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

class MyVpnService : VpnService() {

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USER = "user"
        const val EXTRA_PASS = "pass"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_SNI = "sni"
        const val EXTRA_TLS = "tls"

        const val LOCAL_SOCKS_PORT = 1080
        const val CHANNEL_ID = "vpn_channel"
        const val NOTIF_ID = 1
    }

    private var session: Session? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var socksServer: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        startForeground(NOTIF_ID, buildNotification())

        val host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY
        val port = intent.getIntExtra(EXTRA_PORT, 443)
        val user = intent.getStringExtra(EXTRA_USER) ?: ""
        val pass = intent.getStringExtra(EXTRA_PASS) ?: ""
        val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""
        val sni = intent.getStringExtra(EXTRA_SNI) ?: host
        val useTls = intent.getBooleanExtra(EXTRA_TLS, true)

        pool.execute {
            try {
                connectSsh(host, port, user, pass, payload, sni, useTls)
                establishTun()
                startSocksServer()
            } catch (e: Exception) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun connectSsh(
        host: String, port: Int, user: String, pass: String,
        payload: String, sni: String, useTls: Boolean
    ) {
        val jsch = JSch()
        val s = jsch.getSession(user, host, port)
        s.setPassword(pass)
        s.setConfig("StrictHostKeyChecking", "no")
        s.proxy = PayloadProxy(host, port, payload, sni, useTls)
        s.connect(20000)
        session = s
    }

    /**
     * Brings up the TUN interface. NOTE: routing raw IP packets read from this
     * interface into the SOCKS proxy below (a "tun2socks" packet converter) is
     * the remaining native piece — see README in the repo for wiring in
     * hev-socks5-tunnel (open source) to finish full system-wide routing.
     * Until that is wired in, this VPN interface is established but idle.
     */
    private fun establishTun() {
        val builder = Builder()
            .setSession("SSHWSVpn")
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
        tunFd = builder.establish()
        running = true
    }

    /** Local SOCKS5 server; each accepted connection is forwarded over an SSH direct-tcpip channel. */
    private fun startSocksServer() {
        val server = ServerSocket(LOCAL_SOCKS_PORT, 50, java.net.InetAddress.getByName("127.0.0.1"))
        socksServer = server
        while (running) {
            val client = try { server.accept() } catch (e: Exception) { break }
            pool.execute { handleSocksClient(client) }
        }
    }

    private fun handleSocksClient(client: Socket) {
        try {
            val din = DataInputStream(client.getInputStream())
            val dout = DataOutputStream(client.getOutputStream())

            // Greeting: VER, NMETHODS, METHODS
            val ver = din.readByte().toInt()
            if (ver != 0x05) { client.close(); return }
            val nMethods = din.readByte().toInt()
            din.skipBytes(nMethods)
            dout.write(byteArrayOf(0x05, 0x00)) // no-auth
            dout.flush()

            // Request: VER CMD RSV ATYP ADDR PORT
            din.readByte() // ver
            val cmd = din.readByte().toInt()
            din.readByte() // rsv
            val atyp = din.readByte().toInt()

            val destHost: String = when (atyp) {
                0x01 -> { // IPv4
                    val b = ByteArray(4); din.readFully(b)
                    b.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
                0x03 -> { // domain
                    val len = din.readByte().toInt() and 0xFF
                    val b = ByteArray(len); din.readFully(b)
                    String(b, Charsets.UTF_8)
                }
                else -> { client.close(); return } // IPv6 not handled
            }
            val destPort = din.readUnsignedShort()

            if (cmd != 0x01) { // only CONNECT supported
                dout.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }

            val sess = session
            if (sess == null || !sess.isConnected) {
                dout.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                client.close(); return
            }

            val channel = sess.openChannel("direct-tcpip") as ChannelDirectTCPIP
            channel.setHost(destHost)
            channel.setPort(destPort)
            channel.connect(15000)

            // Success reply
            dout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            dout.flush()

            pipe(client, channel)
        } catch (e: Exception) {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun pipe(client: Socket, channel: Channel) {
        val chIn = channel.inputStream
        val chOut = channel.outputStream
        val cIn = client.getInputStream()
        val cOut = client.getOutputStream()

        val t1 = pool.submit {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = cIn.read(buf); if (n < 0) break
                    chOut.write(buf, 0, n); chOut.flush()
                }
            } catch (_: Exception) {}
        }
        try {
            val buf = ByteArray(8192)
            while (true) {
                val n = chIn.read(buf); if (n < 0) break
                cOut.write(buf, 0, n); cOut.flush()
            }
        } catch (_: Exception) {}
        t1.cancel(true)
        try { channel.disconnect() } catch (_: Exception) {}
        try { client.close() } catch (_: Exception) {}
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SSHWSVpn")
            .setContentText("VPN ချိတ်ဆက်နေသည်")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    override fun onDestroy() {
        running = false
        try { socksServer?.close() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        try { tunFd?.close() } catch (_: Exception) {}
        pool.shutdownNow()
        super.onDestroy()
    }
}
