package io.github.asutorufa.tailscaled

import android.app.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.service.quicksettings.TileService
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
        sharedPreferences = getSharedPreferences("appctr", Context.MODE_PRIVATE)
        
        // WakeLock
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Tailscaled::WakeLock").apply {
            acquire(10*60*1000L /* 10 minutes timeout safety */)
        }

        // Авто-рестарт, если сервис был убит, но "Force Background" включен
        if (ProxyState.isUserLetRunning(this) && !Appctr.isRunning()) {
             if (sharedPreferences.getBoolean("force_bg", false)) {
                 startTailscale()
             } else {
                 ProxyState.setUserState(this, false)
             }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        
        if (action == "STOP_ACTION") {
            stopMe()
            return START_NOT_STICKY
        }

        // Запуск
        ProxyState.setUserState(this, true)
        updateTile()
        
        if (!Appctr.isRunning()) {
            startForeground(1, buildNotification("Active"))
            startTailscale()
        } else {
            // Если уже запущен, просто обновляем уведомление
            notificationManager.notify(1, buildNotification("Active"))
        }
        
        applicationContext.sendBroadcast(Intent("START"))
        return START_STICKY
    }

    private fun startTailscale() {
        val options = StartOptions().apply {
            socks5Server = sharedPreferences.getString("socks5", "127.0.0.1:1055")
            sshServer = sharedPreferences.getString("sshserver", "127.0.0.1:1056")
            authKey = sharedPreferences.getString("authkey", "")
            
            execPath = "${applicationInfo.nativeLibraryDir}/libtailscaled.so"
            socketPath = "${applicationInfo.dataDir}/tailscaled.sock"
            statePath = "${applicationInfo.dataDir}/state"
            
            closeCallBack = Closer { 
                stopMe() 
            }
        }

        Thread {
            try {
                Appctr.start(options)
                applicationContext.sendBroadcast(Intent("START"))
            } catch (e: Exception) {
                Log.e(TAG, "Start error: ${e.message}")
                stopMe()
            }
        }.start()
    }

    private fun stopMe() {
        ProxyState.setUserState(this, false)
        Appctr.stop()
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        updateTile()
        applicationContext.sendBroadcast(Intent("STOP"))
    }
    
    private fun updateTile() {
        TileService.requestListeningState(this, ComponentName(this, ProxyTileService::class.java))
    }

    private fun buildNotification(status: String): Notification {
        val channelId = "tailscaled_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Tailscale Service", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tailscaled")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Замени на нормальную иконку уведомления если есть
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}