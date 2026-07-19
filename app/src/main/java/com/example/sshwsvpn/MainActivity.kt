package com.example.sshwsvpn

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.sshwsvpn.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnImport.setOnClickListener {
            pickFile.launch("application/json")
        }

        binding.btnConnect.setOnClickListener {
            if (loadedConfig == null) {
                toast("Config file အရင် Import လုပ်ပါ")
                return@setOnClickListener
            }
            requestVpnPermission()
        }

        binding.btnDisconnect.setOnClickListener {
            stopService(Intent(this, MyVpnService::class.java))
            toast("VPN ကို ပိတ်လိုက်ပါပြီ")
        }
    }

    private fun readConfigFile(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val text = stream.bufferedReader().readText()
                val cfg = VpnConfig.fromJson(text)
                loadedConfig = cfg
                binding.tvStatus.text = "Loaded: ${cfg.remark} (${cfg.host}:${cfg.port})"
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
        binding.tvStatus.text = "Connecting to ${cfg.host}..."
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
