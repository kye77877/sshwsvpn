package com.example.sshwsvpn

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.sshwsvpn.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private var connected = false
    private var loadedConfig: VpnConfig? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = getSharedPreferences("account", MODE_PRIVATE)

        loadSavedAccount()
        refreshExpiryChip()

        binding.btnSave.setOnClickListener { saveAccount() }

        binding.btnImport.setOnClickListener { pickFile.launch("application/json") }

        binding.btnConnect.setOnClickListener {
            if (connected) {
                stopVpn()
            } else {
                if (!buildConfigFromFields()) {
                    toast("Host / Username / Password ဖြည့်ပါ")
                    return@setOnClickListener
                }
                requestVpnPermission()
            }
        }

        binding.btnDisconnect.setOnClickListener { stopVpn() }
    }

    private fun loadSavedAccount() {
        binding.etHost.setText(prefs.getString("host", ""))
        binding.etPort.setText(prefs.getString("port", "443"))
        binding.etUsername.setText(prefs.getString("username", ""))
        binding.etPassword.setText(prefs.getString("password", ""))
        binding.etExpiry.setText(prefs.getString("expiry", ""))
    }

    private fun saveAccount() {
        prefs.edit()
            .putString("host", binding.etHost.text.toString().trim())
            .putString("port", binding.etPort.text.toString().trim())
            .putString("username", binding.etUsername.text.toString().trim())
            .putString("password", binding.etPassword.text.toString())
            .putString("expiry", binding.etExpiry.text.toString().trim())
            .apply()
        refreshExpiryChip()
        toast("Account information ကို သိမ်းလိုက်ပါပြီ")
    }

    private fun refreshExpiryChip() {
        val expiryStr = binding.etExpiry.text?.toString()?.trim()
        if (expiryStr.isNullOrEmpty()) {
            binding.tvExpiry.text = "Set expiry date"
            binding.tvExpiry.setBackgroundResource(R.drawable.bg_chip_green)
            return
        }
        try {
            val expiryDate = dateFormat.parse(expiryStr) ?: return
            val diffMs = expiryDate.time - System.currentTimeMillis()
            val days = TimeUnit.MILLISECONDS.toDays(diffMs)
            if (days < 0) {
                binding.tvExpiry.text = "Expired"
                binding.tvExpiry.setBackgroundResource(R.drawable.bg_chip_red)
            } else {
                binding.tvExpiry.text = "$days days remaining"
                binding.tvExpiry.setBackgroundResource(R.drawable.bg_chip_green)
            }
        } catch (e: Exception) {
            binding.tvExpiry.text = "Invalid date"
            binding.tvExpiry.setBackgroundResource(R.drawable.bg_chip_red)
        }
    }

    /** Builds loadedConfig from the manual entry fields (used when the user didn't import a .json). */
    private fun buildConfigFromFields(): Boolean {
        val host = binding.etHost.text.toString().trim()
        val portStr = binding.etPort.text.toString().trim()
        val user = binding.etUsername.text.toString().trim()
        val pass = binding.etPassword.text.toString()
        if (host.isEmpty() || user.isEmpty() || pass.isEmpty()) return false

        val port = portStr.toIntOrNull() ?: 443
        loadedConfig = VpnConfig(
            remark = "My Account",
            host = host,
            port = port,
            username = user,
            password = pass,
            payload = "GET wss://[host]/ HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf]Connection: Upgrade[crlf][crlf]",
            sni = host,
            useTls = true
        )
        return true
    }

    private fun readConfigFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                val cfg = VpnConfig.fromJson(text)
                loadedConfig = cfg
                binding.etHost.setText(cfg.host)
                binding.etPort.setText(cfg.port.toString())
                binding.etUsername.setText(cfg.username)
                binding.etPassword.setText(cfg.password)
                toast("Config Import ပြီးပါပြီ: ${cfg.remark}")
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
        setConnectedUi(true)
        binding.tvStatus.text = "Connecting..."
    }

    private fun stopVpn() {
        stopService(Intent(this, MyVpnService::class.java))
        setConnectedUi(false)
    }

    private fun setConnectedUi(isConnected: Boolean) {
        connected = isConnected
        if (isConnected) {
            binding.btnConnect.setBackgroundResource(R.drawable.bg_ring_inner_connected)
            binding.tvStatus.text = "Connected"
            binding.tvStatus.setTextColor(getColor(R.color.status_green))
            binding.btnDisconnect.text = "Disconnect"
        } else {
            binding.btnConnect.setBackgroundResource(R.drawable.bg_ring_inner_disconnected)
            binding.tvStatus.text = "Disconnected"
            binding.tvStatus.setTextColor(getColor(R.color.status_red))
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
