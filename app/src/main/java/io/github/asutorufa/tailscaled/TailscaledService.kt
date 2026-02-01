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
import appctr.StartOptions

class TailscaledService : Service() {
    private val TAG = "TailscaledService"
    private val notificationManager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private lateinit var sharedPreferences: SharedPreferences
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        mMessenger = Messenger(IncomingHandler(this))
        sharedPreferences = getSharedPreferences("appctr", Context.MODE_PRIVATE)
        
        // WakeLock для Xiaomi и прочих, чтобы процессор не засыпал
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::WakeLock").apply {
            acquire()
        }
    }

    private fun buildNotification(status: String): Notification {
        val channelId = "tailscaled_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, 
                "Tailscale Status", 
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tailscaled is $status")
            .setContentText("Userspace node is active")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Appctr.isRunning()) {
            startForeground(1, buildNotification("running"))
            startTailscale()
        }
        return START_STICKY
    }

    private fun startTailscale() {
        // Используем оригинальный набор параметров StartOptions
        val options = StartOptions().apply {
            socks5Server = sharedPreferences.getString("socks5", "127.0.0.1:1055")
            sshServer = sharedPreferences.getString("sshserver", "127.0.0.1:1056")
            authKey = sharedPreferences.getString("authkey", "")
            // В оригинальной версии нужны пути к бинарнику и сокету
            execPath = "${applicationInfo.nativeLibraryDir}/libtailscaled.so"
            socketPath = "${applicationInfo.dataDir}/tailscaled.sock"
            statePath = "${applicationInfo.dataDir}/state"
            closeCallBack = Closer { stopMe() }
            // ПОЛЕ logCallBack УДАЛЕНО, так как в старом Go его нет
        }

        Thread {
            try {
                Appctr.start(options)
                applicationContext.sendBroadcast(Intent("START"))
            } catch (e: Exception) {
                Log.e(TAG, "Start error: ${e.message}")
            }
        }.start()
    }

    fun stopMe() {
        Appctr.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        applicationContext.sendBroadcast(Intent("STOP"))
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private lateinit var mMessenger: Messenger
    override fun onBind(intent: Intent?): IBinder? = mMessenger.binder

    private class IncomingHandler(val service: TailscaledService) : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_SAY_HELLO -> {
                    service.applicationContext.sendBroadcast(Intent(if (Appctr.isRunning()) "START" else "STOP"))
                }
                MSG_STOP -> service.stopMe()
                MSG_START -> service.onStartCommand(null, 0, 0)
            }
        }
    }

    companion object {
        const val MSG_SAY_HELLO = 0
        const val MSG_STOP = 1
        const val MSG_START = 2
    }
}