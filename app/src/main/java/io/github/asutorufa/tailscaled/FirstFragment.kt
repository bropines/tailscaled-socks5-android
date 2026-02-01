package io.github.asutorufa.tailscaled

import android.content.*
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import io.github.asutorufa.tailscaled.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {
    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!
    private lateinit var sharedPreferences: SharedPreferences
    private var isRunning = false

    private val bReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "START" -> updateUi(true)
                "STOP" -> updateUi(false)
            }
        }
    }

    private fun updateUi(running: Boolean) {
        isRunning = running
        binding.button_first.text = if (running) "Stop" else "Start"
        binding.status_label.text = if (running) "Status: Running" else "Status: Stopped"
        
        binding.socks5.isEnabled = !running
        binding.sshserver.isEnabled = !running
        binding.authkey.isEnabled = !running
    }

    private var mService: Messenger? = null
    private var bound: Boolean = false

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mService = Messenger(service)
            bound = true
            mService?.send(Message.obtain(null, TailscaledService.MSG_SAY_HELLO, 0, 0))
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mService = null
            bound = false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedPreferences = requireActivity().getSharedPreferences("appctr", Context.MODE_PRIVATE)
    }

    override fun onResume() {
        super.onResume()
        binding.socks5.setText(sharedPreferences.getString("socks5", "0.0.0.0:1055"))
        binding.sshserver.setText(sharedPreferences.getString("sshserver", "0.0.0.0:1056"))
        binding.authkey.setText(sharedPreferences.getString("authkey", ""))
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val intentFilter = IntentFilter().apply {
            addAction("START")
            addAction("STOP")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            activity?.registerReceiver(bReceiver, intentFilter, Context.RECEIVER_EXPORTED)
        else activity?.registerReceiver(bReceiver, intentFilter)

        Intent(activity, TailscaledService::class.java).also { intent ->
            activity?.bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        }

        // Авто-сохранение полей
        binding.socks5.doAfterTextChanged { sharedPreferences.edit().putString("socks5", it.toString()).apply() }
        binding.sshserver.doAfterTextChanged { sharedPreferences.edit().putString("sshserver", it.toString()).apply() }
        binding.authkey.doAfterTextChanged { sharedPreferences.edit().putString("authkey", it.toString()).apply() }

        // Кнопка получения ключа
        binding.btn_get_key.setOnClickListener {
            val url = "https://login.tailscale.com/admin/settings/keys"
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        binding.button_first.setOnClickListener {
            if (isRunning) {
                mService?.send(Message.obtain(null, TailscaledService.MSG_STOP, 0, 0))
            } else {
                activity?.startService(Intent(activity, TailscaledService::class.java))
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.unregisterReceiver(bReceiver)
        if (bound) {
            activity?.unbindService(mConnection)
            bound = false
        }
        _binding = null
    }
}