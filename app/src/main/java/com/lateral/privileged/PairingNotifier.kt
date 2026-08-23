package com.lateral.privileged

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.lateral.R

/**
 * The notification UxSpace posts while pairing is needed, with a `RemoteInput` text field so
 * the user can type the 6-digit code straight from the notification shade — over the still-
 * open Wireless Debugging pair dialog, which keeps the mDNS pairing service alive long
 * enough for libadb to look it up.
 *
 * This is the trick Shizuku uses, and it sidesteps the catch-22 of "to enter the code into
 * the app, you have to leave the dialog, and the moment you do the mDNS service is gone".
 */
object PairingNotifier {

    /** Channel id — created lazily on first post. */
    private const val CHANNEL_ID = "lateral_pairing"

    /** Notification id; one at a time. */
    private const val NOTIFICATION_ID = 1

    /** RemoteInput bundle key for the typed code; read by [PairingInputReceiver]. */
    const val KEY_PAIRING_CODE = "pairing_code"

    /** Broadcast action [PairingInputReceiver] listens for. */
    const val ACTION_SUBMIT_CODE = "com.lateral.privileged.SUBMIT_PAIRING_CODE"

    fun canPost(context: Context): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** Post the pairing notification. Returns false when notification permission is absent. */
    fun showPairingPrompt(context: Context): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val remoteInput = RemoteInput.Builder(KEY_PAIRING_CODE)
            .setLabel(context.getString(R.string.privilege_pairing_hint))
            .build()
        val replyIntent = Intent(ACTION_SUBMIT_CODE)
            .setPackage(context.packageName)
            .setClass(context, PairingInputReceiver::class.java)
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_edit,
            context.getString(R.string.privilege_action_pair),
            replyPendingIntent,
        ).addRemoteInput(remoteInput).build()
        val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        val settingsPendingIntent = PendingIntent.getActivity(
            context,
            1,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.privilege_notification_title))
            .setContentText(context.getString(R.string.privilege_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.privilege_notification_text)),
            )
            .addAction(action)
            .setContentIntent(settingsPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        return runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        }.getOrDefault(false)
    }

    /** Take the notification down — call when state moves out of NEEDS_PAIRING. */
    fun cancel(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.privilege_notification_channel),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.privilege_notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}
