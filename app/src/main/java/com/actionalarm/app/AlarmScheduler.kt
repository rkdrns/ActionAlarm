package com.actionalarm.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/** AlarmManager 로 정확한 시각에 알람 예약 */
object AlarmScheduler {

    private const val PI_FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun am(c: Context) = c.getSystemService(AlarmManager::class.java)

    private fun firePendingIntent(c: Context, id: Int): PendingIntent {
        val intent = Intent(c, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_KIND, KIND_MAIN)
        return PendingIntent.getBroadcast(c, id * 2, intent, PI_FLAGS)
    }

    private fun setExact(c: Context, triggerAt: Long, op: PendingIntent) {
        val show = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PI_FLAGS)
        try {
            // setAlarmClock: 절전(Doze) 상태에서도 정확히 울림 + 상태표시줄에 알람 아이콘
            am(c).setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), op)
        } catch (e: SecurityException) {
            // 정확한 알람 권한이 없을 때의 대비책 (약간 늦게 울릴 수 있음)
            am(c).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
        }
    }

    fun canScheduleExact(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am(c).canScheduleExactAlarms()

    fun nextTrigger(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** 설정한 시각의 알람 예약. 꺼진 알람이면 취소. 예약된 시각을 반환 */
    fun schedule(c: Context, alarm: Alarm): Long? {
        if (!alarm.enabled) {
            cancel(c, alarm.id)
            return null
        }
        val at = nextTrigger(alarm.hour, alarm.minute)
        setExact(c, at, firePendingIntent(c, alarm.id))
        return at
    }

    fun cancel(c: Context, id: Int) {
        am(c).cancel(firePendingIntent(c, id))
    }

    /** 재부팅·앱 업데이트·시간 변경 후 알람 예약 복구 */
    fun rescheduleAll(c: Context) {
        AlarmStore.all(c).forEach { schedule(c, it) }
        AlarmStore.allPending(c).keys.forEach { id ->
            if (AlarmStore.get(c, id) == null) AlarmStore.clearPending(c, id)
        }
    }
}
