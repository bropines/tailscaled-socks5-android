package io.github.asutorufa.tailscaled

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import io.github.asutorufa.tailscaled.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("appctr", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        binding.authKey.setText(prefs.getString("authkey", ""))
        binding.hostname.setText(prefs.getString("hostname", ""))
        binding.loginServer.setText(prefs.getString("login_server", ""))
        
        binding.socks5Addr.setText(prefs.getString("socks5", "127.0.0.1:1055"))
        binding.sshAddr.setText(prefs.getString("sshserver", "127.0.0.1:1056"))
        
        binding.swAcceptRoutes.isChecked = prefs.getBoolean("accept_routes", false)
        binding.swAcceptDns.isChecked = prefs.getBoolean("accept_dns", true)
        
        binding.swAdvertiseExitNode.isChecked = prefs.getBoolean("advertise_exit_node", false)
        binding.exitNodeIp.setText(prefs.getString("exit_node_ip", ""))
        binding.swAllowLan.isChecked = prefs.getBoolean("exit_node_allow_lan", false)
        
        binding.extraArgs.setText(prefs.getString("extra_args_raw", ""))
        binding.switchForceBg.isChecked = prefs.getBoolean("force_bg", false)
    }

    private fun setupListeners() {
        // Text Fields
        binding.authKey.doAfterTextChanged { saveStr("authkey", it.toString()) }
        binding.hostname.doAfterTextChanged { saveStr("hostname", it.toString()) }
        binding.loginServer.doAfterTextChanged { saveStr("login_server", it.toString()) }
        binding.socks5Addr.doAfterTextChanged { saveStr("socks5", it.toString()) }
        binding.sshAddr.doAfterTextChanged { saveStr("sshserver", it.toString()) }
        binding.exitNodeIp.doAfterTextChanged { saveStr("exit_node_ip", it.toString()) }
        binding.extraArgs.doAfterTextChanged { saveStr("extra_args_raw", it.toString()) }

        // Switches
        binding.swAcceptRoutes.setOnCheckedChangeListener { _, v -> saveBool("accept_routes", v) }
        binding.swAcceptDns.setOnCheckedChangeListener { _, v -> saveBool("accept_dns", v) }
        binding.swAdvertiseExitNode.setOnCheckedChangeListener { _, v -> saveBool("advertise_exit_node", v) }
        binding.swAllowLan.setOnCheckedChangeListener { _, v -> saveBool("exit_node_allow_lan", v) }
        binding.switchForceBg.setOnCheckedChangeListener { _, v -> saveBool("force_bg", v) }
    }

    private fun saveStr(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    private fun saveBool(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}