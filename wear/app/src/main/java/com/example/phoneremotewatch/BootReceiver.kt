package com.example.phoneremotewatch

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Starts the phone link again after the watch restarts or the app is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("watch_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("link_enabled", true)) return
        if (Build.VERSION.SDK_INT >= 31) {
            val ok = context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            if (!ok) return
        }
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LinkService::class.java)
                    .setAction(LinkService.ACTION_START)
                    .putExtra(LinkService.EXTRA_BOOT, true)
            )
        } catch (e: Exception) {
            // The system may not allow starting right now. The app starts the link when it is opened.
        }
    }
}
