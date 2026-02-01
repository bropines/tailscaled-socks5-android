package io.github.asutorufa.tailscaled

import android.content.Context
import androidx.core.content.edit
import appctr.Appctr

object ProxyState {
    private const val PREF = "proxy_state"
    private const val KEY_DESIRED = "desired_running"

    // Пользователь хочет, чтобы сервис работал?
    fun setUserState(context: Context, running: Boolean) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit {
                putBoolean(KEY_DESIRED, running)
            }
    }

    // Должен ли сервис работать по мнению пользователя?
    fun isUserLetRunning(context: Context): Boolean {
        return context.applicationContext
            .getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_DESIRED, false)
    }

    // Работает ли процесс на самом деле?
    fun isActualRunning(): Boolean = Appctr.isRunning()
}