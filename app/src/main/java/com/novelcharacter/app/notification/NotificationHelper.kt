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
    private const val IMPORT_RESULT_NOTIFICATION_ID = 1003

    /**
     * **전송(가져오기·내보내기) 결과를 앱 밖으로 알린다** (B-56 · B-228).
     *
     * 끝났을 때 결과 창을 띄울 자리가 없으면 종전에는 토스트로 물러섰다 —
     * **앱이 앞에 없으면 안드로이드가 그 토스트를 막고**
     * (API 30+), 떠도 사라져 오류·경고 상세가 통째로 없어진다.
     *
     * **이것 하나에 고지를 걸지는 않는다** — 알림 권한은 사용자가 거절할 수 있고(API 33+)
     * 거절은 정당한 선택이다. 그래서 [com.novelcharacter.app.util.TransferNoticeRelay]가
     * 같은 고지를 보관해 다음 진입에서 한 번 더 낸다. 둘은 대체재가 아니라 **서로의
     * 사각을 메우는 짝**이다.
     *
     * **가져오기 전용이 아니다**(B-228) — 화면이 사라져 끊긴 내보내기의 중단 고지도 이 길로
     * 나간다. 알림 id를 그대로 두는 것은 일부러다: 전송의 종결 고지는 한 번에 하나여야
     * 하고, 새 것이 옛 것을 덮는 것이 맞다.
     *
     * @param body 요약 한 줄. 알림은 상세를 담을 자리가 아니므로 상세는 앱 안에서 본다.
     */
    fun showTransferResultNotification(context: Context, title: String, body: String) {
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
            context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, NovelCharacterApp.TRANSFER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_character_placeholder)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.import_notice_notification_text))
            // 요약이 한 줄을 넘기므로 펼침 형식을 함께 둔다 — 잘리면 정작 건수가 안 보인다.
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(IMPORT_RESULT_NOTIFICATION_ID, notification)
    }

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
