package io.github.asutorufa.tailscaled

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Невидимая активити-трамплин для запуска сервиса из TileService (Android 14+).
 * Она открывается, посылает команду и сразу закрывается.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val intent = Intent(this, TailscaledService::class.java)
        // Если сервис работает -> Стоп, иначе -> Старт
        if (ProxyState.isActualRunning()) {
            intent.action = "STOP_ACTION" // Обработаем это в onStartCommand
        } else {
            intent.action = "START_ACTION"
        }
        
        startForegroundService(intent)
        finish()
    }
}