package com.example.sshwsvpn

import org.json.JSONObject

/**
 * Holds one server profile, loaded from an imported .json config file.
 *
 * Expected JSON shape:
 * {
 *   "remark": "My Server",
 *   "host": "vpn.yourdomain.com",
 *   "port": 443,
 *   "username": "user1",
 *   "password": "pass123",
 *   "payload": "GET wss://[host]/ HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
 *   "sni": "vpn.yourdomain.com",
 *   "use_tls": true
 * }
 */
data class VpnConfig(
    val remark: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val payload: String,
    val sni: String,
    val useTls: Boolean
) {
    companion object {
        fun fromJson(text: String): VpnConfig {
            val o = JSONObject(text)
            return VpnConfig(
                remark = o.optString("remark", "Server"),
                host = o.getString("host"),
                port = o.optInt("port", 443),
                username = o.getString("username"),
                password = o.optString("password", ""),
                payload = o.optString(
                    "payload",
                    "GET wss://[host]/ HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"
                ),
                sni = o.optString("sni", o.getString("host")),
                useTls = o.optBoolean("use_tls", true)
            )
        }
    }

    /** Replaces [host], [port], [crlf] placeholders with real values. */
    fun renderedPayload(): String =
        payload.replace("[host]", host)
            .replace("[port]", port.toString())
            .replace("[crlf]", "\r\n")
}
