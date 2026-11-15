package io.github.zhagalchin.evensound.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.zhagalchin.evensound.MainActivity
import io.github.zhagalchin.evensound.Prefs
import io.github.zhagalchin.evensound.R

/** Держит системный эффект включённым, пока приложение не на экране. */
class GlobalEffectService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Выравнивание звука", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_level)
            .setContentTitle("Ровный звук включён")
            .setContentText("Громкость выравнивается во всех приложениях")
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 1, notification, type)
        GlobalEffect.start(Prefs(this).settings)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        GlobalEffect.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "global_effect"

        fun setEnabled(context: Context, enabled: Boolean) {
            val intent = Intent(context, GlobalEffectService::class.java)
            if (enabled) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
        }
    }
}

/** Возвращает системный режим после перезагрузки устройства. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).globalEnabled) {
            runCatching { GlobalEffectService.setEnabled(context, true) }
        }
    }
}
