package com.actionalarm.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon

object Notifications {
    private const val CH_RING = "ringing"
    private const val CH_PENDING = "pending_action"
    const val FOREGROUND_ID = 1

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)

    fun ensureChannels(c: Context) {
        val ring = NotificationChannel(CH_RING, "알람", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "알람이 울릴 때 표시"
            setSound(null, null)          // 소리는 서비스가 직접 재생
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val pending = NotificationChannel(CH_PENDING, "행동 확인 대기", NotificationManager.IMPORTANCE_LOW).apply {
            description = "완료했어요를 누를 때까지 표시 (소리 없음)"
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm(c).createNotificationChannels(listOf(ring, pending))
    }

    private fun receiverIntent(c: Context, action: String, id: Int, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            c, requestCode,
            Intent(c, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
            PI_FLAGS
        )

    private fun icon(c: Context) = Icon.createWithResource(c, R.drawable.ic_alarm)

    /** 울리는 중 알림 (잠금화면 위로 알람 화면을 띄우는 전체화면 알림) */
    fun ringing(c: Context, alarm: Alarm?, id: Int): Notification {
        ensureChannels(c)
        val open = PendingIntent.getActivity(c, 30_000 + id, AlarmActivity.intent(c, id), PI_FLAGS)
        val silence = receiverIntent(c, AlarmReceiver.ACTION_SILENCE, id, 20_000 + id)
        return Notification.Builder(c, CH_RING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setContentTitle("⏰ ${alarm?.reason ?: "알람"}")
            .setContentText("해야 할 일: ${alarm?.action ?: ""}")
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon(c), "알람 끄기", silence).build())
            .build()
    }

    /**
     * 확인창이 떠 있는 동안의 알림 (포그라운드 서비스 유지용, 소리 없음).
     * 잠금화면처럼 확인창이 안 보이는 곳에서도 여기서 완료할 수 있음.
     */
    fun pendingSummary(c: Context, alarms: List<Alarm>): Notification {
        ensureChannels(c)
        val open = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PI_FLAGS
        )
        val b = Notification.Builder(c, CH_PENDING)
            .setSmallIcon(R.drawable.ic_alarm)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)

        if (alarms.isEmpty()) {
            return b.setContentTitle("할일 알람").setContentText("확인할 항목이 없어요").build()
        }
        b.setContentTitle("⏳ 행동 확인 대기 ${alarms.size}건")
            .setContentText(alarms.joinToString(", ") { it.reason })
            .setStyle(
                Notification.BigTextStyle().bigText(
                    alarms.joinToString("\n") { "• ${it.reason}: ${it.action}" }
                )
            )
        // 알림 버튼은 최대 3개
        alarms.take(3).forEach { a ->
            val done = receiverIntent(c, AlarmReceiver.ACTION_COMPLETE, a.id, 50_000 + a.id)
            val label = if (alarms.size == 1) "✔ 완료했어요" else "✔ ${a.reason}"
            b.addAction(Notification.Action.Builder(icon(c), label, done).build())
        }
        return b.build()
    }
}
