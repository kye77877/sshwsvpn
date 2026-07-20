package com.example.sshwsvpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etImportLink: EditText
    private lateinit var btnParseLink: Button
    private lateinit var tvParsedServer: TextView
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvExpiry: TextView
    private lateinit var dotExpiry: TextView

    private var parsedHost: String? = null
    private var parsedPort: Int = 443
    private var parsedPayload: String = ""
    private var loadedConfig: VpnConfig? = null

    private val expiryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(MyVpnService.EXTRA_EXPIRY_TEXT) ?: return
            val ok = intent.getBooleanExtra(MyVpnService.EXTRA_EXPIRY_OK, true)
            tvExpiry.text = text
            val color = if (ok) getColor(R.color.success) else getColor(R.color.danger)
            tvExpiry.setTextColor(color)
            dotExpiry.setTextColor(color)
            tvStatus.text = if (ok) "Connected" else "Connection issue"
        }
    }

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpn()
            } else {
                toast("VPN permission လိုအပ်ပါတယ်")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etImportLink = findViewById(R.id.etImportLink)
        btnParseLink = findViewById(R.id.btnParseLink)
        tvParsedServer = findViewById(R.id.tvParsedServer)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        tvStatus = findViewById(R.id.tvStatus)
        tvExpiry = findViewById(R.id.tvExpiry)
        dotExpiry = findViewById(R.id.dotExpiry)

        btnParseLink.setOnClickListener {
            val raw = etImportLink.text.toString().trim()
            val parsed = parseSshLink(raw)
            if (parsed == null) {
                toast("Link format မှားနေပါတယ်")
                tvParsedServer.text = "Server: --"
                parsedHost = null
                parsedPayload = ""
                return@setOnClickListener
            }
            parsedHost = parsed.host
            parsedPort = parsed.port
            parsedPayload = parsed.payload
            tvParsedServer.text = "Server: ${parsed.host}:${parsed.port}"
            if (parsed.username.isNotEmpty()) etUser.setText(parsed.username)
            if (parsed.password.isNotEmpty()) etPass.setText(parsed.password)
            toast("Server + Payload ရရှိပါပြီ — Username/Password ကို ကိုယ်တိုင်ဖြည့်ပါ")
        }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            if (!buildConfigFromFields()) return@setOnClickListener
            requestVpnPermission()
        }

        findViewById<Button>(R.id.btnDisconnect).setOnClickListener {
            stopService(Intent(this, MyVpnService::class.java))
            tvStatus.text = "Not connected"
            tvExpiry.text = "Account expiry: --"
        }
    }

    private data class ParsedLink(val host: String, val port: Int, val payload: String, val username: String, val password: String)

    private fun parseSshLink(raw: String): ParsedLink? {
        if (raw.isEmpty()) return null
        return try {
            val withoutScheme = raw.substringAfter("://", raw)
            val userInfo = if (withoutScheme.contains("@")) {
                withoutScheme.substringBefore("@")
            } else {
                ""
            }
            val username = userInfo.substringBefore(":", "")
            val password = if (userInfo.contains(":")) userInfo.substringAfter(":") else ""
            val afterAt = if (withoutScheme.contains("@")) {
                withoutScheme.substringAfter("@")
            } else {
                withoutScheme
            }
            val hostPortPlusRest = afterAt
            val hostPortPart = hostPortPlusRest.substringBefore("?")
            val host = hostPortPart.substringBefore(":")
            val portStr = hostPortPart.substringAfter(":", "443")
            val port = portStr.toIntOrNull() ?: 443

            val payload = if (hostPortPlusRest.contains("?")) {
                hostPortPlusRest.substringAfter("?").substringBefore("#")
            } else {
                ""
            }

            if (host.isEmpty()) null else ParsedLink(host, port, payload, username, password)
        } catch (e: Exception) {
            null
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MyVpnService.ACTION_EXPIRY_UPDATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(expiryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(expiryReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(expiryReceiver) } catch (_: Exception) {}
    }

    private fun buildConfigFromFields(): Boolean {
        val host = parsedHost
        if (host == null) {
            toast("Server link ကို အရင် PARSE LINK နှိပ်ပါ")
            return false
        }

        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()

        if (user.isEmpty() || pass.isEmpty()) {
            toast("Username နဲ့ Password ဖြည့်ပါ")
            return false
        }

        loadedConfig = VpnConfig(
            remark = host,
            host = host,
            port = parsedPort,
            username = user,
            password = pass,
            payload = parsedPayload,
            sni = host,
            useTls = true
        )
        return true
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermission.launch(intent)
        } else {
            startVpn()
        }
    }

    private fun startVpn() {
        val cfg = loadedConfig ?: return
        val intent = Intent(this, MyVpnService::class.java).apply {
            putExtra(MyVpnService.EXTRA_HOST, cfg.host)
            putExtra(MyVpnService.EXTRA_PORT, cfg.port)
            putExtra(MyVpnService.EXTRA_USER, cfg.username)
            putExtra(MyVpnService.EXTRA_PASS, cfg.password)
            putExtra(MyVpnService.EXTRA_PAYLOAD, cfg.payload)
            putExtra(MyVpnService.EXTRA_SNI, cfg.sni)
            putExtra(MyVpnService.EXTRA_TLS, cfg.useTls)
        }
        startForegroundService(intent)
        tvStatus.text = "Connecting to ${cfg.remark}..."
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
