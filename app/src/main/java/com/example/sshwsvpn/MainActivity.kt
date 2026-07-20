package com.example.sshwsvpn

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var etHost: EditText
    private lateinit var etPort: EditText
    private lateinit var etUser: EditText
    private lateinit var etPass: EditText
    private lateinit var etExpiry: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvExpiry: TextView

    private var loadedConfig: VpnConfig? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { readConfigFile(it) }
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

        etHost = findViewById(R.id.etHost)
        etPort = findViewById(R.id.etPort)
        etUser = findViewById(R.id.etUser)
        etPass = findViewById(R.id.etPass)
        etExpiry = findViewById(R.id.etExpiry)
        tvStatus = findViewById(R.id.tvStatus)
        tvExpiry = findViewById(R.id.tvExpiry)

        findViewById<android.widget.Button>(R.id.btnConnect).setOnClickListener {
            if (!buildConfigFromFields()) return@setOnClickListener
            requestVpnPermission()
        }

        findViewById<android.widget.Button>(R.id.btnDisconnect).setOnClickListener {
            stopService(Intent(this, MyVpnService::class.java))
            tvStatus.text = "Not connected"
        }

        findViewById<TextView>(R.id.btnImport).setOnClickListener {
            pickFile.launch("application/json")
        }
    }

    private fun buildConfigFromFields(): Boolean {
        val host = etHost.text.toString().trim()
        val portStr = etPort.text.toString().trim()
        val user = etUser.text.toString().trim()
        val pass = etPass.text.toString()
        val expiry = etExpiry.text.toString().trim()

        if (host.isEmpty() || user.isEmpty()) {
            toast("Host နဲ့ Username ဖြည့်ပါ")
            return false
        }
        val port = portStr.toIntOrNull() ?: 443

        val defaultPayload =
            "GET wss://[host]/ HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]"

        loadedConfig = VpnConfig(
            remark = "AIS NO PRO",
            host = host,
            port = port,
            username = user,
            password = pass,
            payload = defaultPayload,
            sni = host,
            useTls = true
        )

        if (expiry.isNotEmpty()) {
            tvExpiry.text = "Account expiry: $expiry"
        }
        return true
    }

    private fun readConfigFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                val cfg = VpnConfig.fromJson(text)
                loadedConfig = cfg
                etHost.setText(cfg.host)
                etPort.setText(cfg.port.toString())
                etUser.setText(cfg.username)
                etPass.setText(cfg.password)
                tvStatus.text = "Loaded: ${cfg.remark} (${cfg.host}:${cfg.port})"
            }
        } catch (e: Exception) {
            toast("Config file မှားနေပါတယ်: ${e.message}")
        }
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
            putExtra(MyVpnService.EXTRA_PAYLOAD, cfg.renderedPayload())
            putExtra(MyVpnService.EXTRA_SNI, cfg.sni)
            putExtra(MyVpnService.EXTRA_TLS, cfg.useTls)
        }
        startForegroundService(intent)
        tvStatus.text = "Connecting to ${cfg.host}..."
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
