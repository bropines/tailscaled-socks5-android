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

        // Load values
        binding.authKey.setText(prefs.getString("authkey", ""))
        binding.socks5Addr.setText(prefs.getString("socks5", "127.0.0.1:1055"))
        binding.sshAddr.setText(prefs.getString("sshserver", "127.0.0.1:1056"))
        binding.switchForceBg.isChecked = prefs.getBoolean("force_bg", false)

        // Save values automatically
        binding.authKey.doAfterTextChanged { prefs.edit().putString("authkey", it.toString()).apply() }
        binding.socks5Addr.doAfterTextChanged { prefs.edit().putString("socks5", it.toString()).apply() }
        binding.sshAddr.doAfterTextChanged { prefs.edit().putString("sshserver", it.toString()).apply() }
        
        binding.switchForceBg.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("force_bg", isChecked).apply()
        }
    }
}