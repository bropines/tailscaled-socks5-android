package io.github.asutorufa.tailscaled

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import appctr.Appctr
import appctr.Closer
import appctr.LogCallback
import appctr.StartOptions

class TailscaledService : Service() {
    private val TAG = "TailscaledService"
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private lateinit var sharedPreferences: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null
    private var tailscaleIp: String = "0.0.0.0"

    override fun onCreate() {
        super.onCreate()
        mMessenger = Messenger(IncomingHandler(this))
        sharedPreferences = getSharedPreferences("appctr", Context.MODE_PRIVATE)
        
        // 1. Удерживаем процессор, чтобы туннель не падал при выключенном экране
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::WakeLock").apply {
            acquire()
        }
    }

    // Создает уведомление для Foreground Service
    private fun buildNotification(status: String): Notification {
        val channelId = "tailscaled_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, 
                "Tailscale Service Status", 
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val description = if (tailscaleIp != "0.0.0.0") "IP: $tailscaleIp" else "Connecting to network..."

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tailscaled: $status")
            .setContentText(description)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Appctr.isRunning()) {
            Log.d(TAG, "Starting Tailscaled engine...")
            startForeground(1, buildNotification("starting"))
            startTailscale()
        } else {
            // Если сервис уже запущен, просто обновляем уведомление
            startForeground(1, buildNotification("running"))
        }
        
        // START_STICKY приказывает системе перезапустить сервис, если он будет убит
        return START_STICKY
    }

    private val logCallback = object : LogCallback {
        override fun onLog(msg: String?) {
            msg?.let { text ->
                // Отправляем сырые логи во фрагмент для отображения
                val intent = Intent("LOG_UPDATE").apply {
                    putExtra("log", text)
                }
                sendBroadcast(intent)

                // Если мы еще не знаем свой IP, ищем его в логах
                if (tailscaleIp == "0.0.0.0" && text.contains("100.")) {
                    val match = Regex("100\\.\\d+\\.\\d+\\.\\d+").find(text)
                    if (match != null) {
                        tailscaleIp = match.value
                        // Обновляем уведомление (теперь там будет IP)
                        notificationManager.notify(1, buildNotification("connected"))
                    }
                }
            }
        }
    }

    private fun startTailscale() {
        val options = StartOptions().apply {
            socks5Server = sharedPreferences.getString("socks5", "127.0.0.1:1055")
            sshServer = sharedPreferences.getString("sshserver", "127.0.0.1:1056")
            authKey = sharedPreferences.getString("authkey", "")
            statePath = "${applicationInfo.dataDir}/tailscale-state"
            closeCallBack = Closer { stopMe() }
            logCallBack = logCallback
        }

        // Запуск Go-движка в отдельном потоке, так как это блокирующая операция
        Thread {
            try {
                Appctr.start(options)
                applicationContext.sendBroadcast(Intent("START"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Appctr: ${e.message}")
            }
        }.start()
    }

    private fun stopMe() {
        Log.d(TAG, "Stopping service and engine...")
        Appctr.stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
        applicationContext.sendBroadcast(Intent("STOP"))
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    /**
     * Взаимодействие с UI (MainActivity/FirstFragment)
     */
    private lateinit var mMessenger: Messenger

    override fun onBind(intent: Intent?): IBinder? {
        return mMessenger.binder
    }

    private class IncomingHandler(
        val service: TailscaledService
    ) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SAY_HELLO -> {
                    // При подключении UI сообщаем текущий статус
                    val status = if (Appctr.isRunning()) "START" else "STOP"
                    service.applicationContext.sendBroadcast(Intent(status))
                }
                MSG_STOP -> {
                    service.stopMe()
                }
                MSG_START -> {
                    service.onStartCommand(null, 0, 0)
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    companion object {
        const val MSG_SAY_HELLO = 0
        const val MSG_STOP = 1
        const val MSG_START = 2
    }
}