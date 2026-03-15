package com.novelcharacter.app.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.novelcharacter.app.MainActivity
import com.novelcharacter.app.NovelCharacterApp
import com.novelcharacter.app.R

object NotificationHelper {

    private const val BIRTHDAY_NOTIFICATION_ID = 1001

    fun showBirthdayNotification(
        context: Context,
        characterNames: List<String>
    ) {
        if (characterNames.isEmpty()) return

        // Android 13+ requires POST_NOTIFICATIONS permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.birthday_notification_title)
        val text = if (characterNames.size == 1) {
            context.getString(R.string.birthday_single, characterNames[0])
        } else {
            context.getString(R.string.birthday_multiple, characterNames[0], characterNames.size - 1)
        }

        val notification = NotificationCompat.Builder(context, NovelCharacterApp.BIRTHDAY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_character_placeholder)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.birthday_list, characterNames.joinToString(", ")))
            )
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BIRTHDAY_NOTIFICATION_ID, notification)
    }
}
