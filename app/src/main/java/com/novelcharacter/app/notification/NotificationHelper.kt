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
    private const val BACKUP_FAILED_NOTIFICATION_ID = 1002

    /**
     * 자동 백업 실패를 시스템 알림으로 능동 통지한다(설정 화면을 열지 않아도 인지).
     * 조용한 백업 실패로 데이터 보호가 뚫리는 것을 방지 — 변수 제어.
     */
    fun showBackupFailedNotification(context: Context, reason: String) {
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
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NovelCharacterApp.BACKUP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_character_placeholder)
            .setContentTitle(context.getString(R.string.backup_failed_notification_title))
            .setContentText(context.getString(R.string.backup_failed_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.backup_failed_notification_detail, reason))
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(BACKUP_FAILED_NOTIFICATION_ID, notification)
    }

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
