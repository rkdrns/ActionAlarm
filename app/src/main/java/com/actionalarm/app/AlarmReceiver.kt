package com.actionalarm.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 알람 시각 도래 / 알림의 "알람 끄기"·"완료했어요" 버튼을 처리 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(c: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id < 0) return
        when (intent.action) {
            ACTION_FIRE -> {
                // 이전 버전의 10분 반복 알람이 남아 있으면 무시
                if (intent.getIntExtra(EXTRA_KIND, KIND_MAIN) != KIND_MAIN) return
                onFire(c, id)
            }
            ACTION_SILENCE -> AlarmActions.silence(c, id)
            ACTION_COMPLETE -> AlarmActions.complete(c, id)
        }
    }

    private fun onFire(c: Context, id: Int) {
        val alarm = AlarmStore.get(c, id) ?: return
        // 매일 반복이면 내일 같은 시각 예약, 아니면 알람 끔
        if (alarm.repeatDaily) AlarmScheduler.schedule(c, alarm)
        else AlarmStore.upsert(c, alarm.copy(enabled = false))

        AlarmStore.markRinging(c, id)
        c.startForegroundService(
            Intent(c, AlarmService::class.java)
                .setAction(AlarmService.ACTION_RING)
                .putExtra(EXTRA_ID, id)
        )
    }

    companion object {
        const val ACTION_FIRE = "com.actionalarm.app.FIRE"
        const val ACTION_SILENCE = "com.actionalarm.app.SILENCE"
        const val ACTION_COMPLETE = "com.actionalarm.app.COMPLETE"
    }
}

/** 재부팅/업데이트/시간 변경 시 예약 복구 + 완료 안 한 확인창 다시 띄우기 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(c)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            AlarmService.restoreIfNeeded(c)
        }
    }
}

/** 알람 끄기 / 행동 완료 — 알람 화면·작은 확인창·알림 어디서 눌러도 같은 동작 */
object AlarmActions {

    /** 소리만 끔 → 오른쪽 위 작은 확인창으로 전환 */
    @Suppress("UNUSED_PARAMETER")
    fun silence(c: Context, id: Int) {
        AlarmService.instance?.silence(id)
    }

    /** 사용자가 행동을 완료했다고 터치 → 확인창 사라짐 */
    fun complete(c: Context, id: Int) {
        AlarmStore.clearPending(c, id)
        AlarmService.instance?.onCompleted(id)
    }
}
