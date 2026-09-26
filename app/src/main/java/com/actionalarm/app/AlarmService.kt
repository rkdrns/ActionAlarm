package com.actionalarm.app

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 알람 소리·진동을 울리고, 알람을 끈 뒤에는
 * 화면 오른쪽 위에 "행동했나요?" 작은 확인창을 계속 띄워 두는 포그라운드 서비스.
 * 확인창이 모두 사라지면(모두 완료) 서비스도 종료됩니다.
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var ringingAlarm = -1
    private lateinit var overlay: PendingOverlay

    // 아무도 끄지 않으면 일정 시간 후 자동으로 소리를 멈추고 확인창으로 전환
    private val autoSilence = Runnable {
        if (ringingAlarm >= 0) silence(ringingAlarm)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        overlay = PendingOverlay(this) { id -> AlarmActions.complete(this, id) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RING) {
            startRinging(intent.getIntExtra(EXTRA_ID, -1))
        } else {
            // 재부팅 후 / 앱 실행 시: 완료 안 된 확인창 복구
            startForegroundCompat(Notifications.pendingSummary(this, pendingAlarms()), ringing = false)
            refreshPendingUi()
        }
        return START_NOT_STICKY
    }

    // ---------------- 울리기 ----------------

    private fun startRinging(id: Int) {
        val alarm = AlarmStore.get(this, id)
        // startForegroundService 후에는 반드시 startForeground 를 먼저 호출해야 함
        startForegroundCompat(Notifications.ringing(this, alarm, id), ringing = true)
        if (alarm == null) {
            refreshPendingUi()
            return
        }

        // 다른 알람이 울리던 중이면 그 알람은 확인창으로 넘김
        if (ringingAlarm >= 0 && ringingAlarm != id) stopRingingSound()

        ringingAlarm = id
        ringingId = id
        refreshPendingUi(updateNotification = false) // 울리는 알람은 확인창 목록에서 잠시 제외

        startSound()
        startVibration()
        handler.removeCallbacks(autoSilence)
        handler.postDelayed(autoSilence, RING_DURATION_MS)

        // 화면이 켜져 있을 때를 대비해 알람 화면 직접 열기 시도 (안 되면 전체화면 알림이 대신 띄움)
        try {
            startActivity(AlarmActivity.intent(this, id))
        } catch (_: Exception) {
        }
    }

    /** 알람 끄기: 소리만 멈추고 오른쪽 위 작은 확인창 표시 */
    fun silence(id: Int) {
        if (ringingAlarm != id) return
        stopRingingSound()
        refreshPendingUi()
    }

    /** 완료: 해당 확인창 제거. 남은 게 없으면 서비스 종료 */
    fun onCompleted(id: Int) {
        if (ringingAlarm == id) stopRingingSound()
        refreshPendingUi()
    }

    private fun stopRingingSound() {
        handler.removeCallbacks(autoSilence)
        stopSound()
        vibrator?.cancel()
        vibrator = null
        ringingAlarm = -1
        ringingId = -1
    }

    // ---------------- 확인창 / 알림 갱신 ----------------

    /** 완료 대기 중인 알람 (지금 울리는 알람 제외) */
    private fun pendingAlarms(): List<Alarm> =
        AlarmStore.allPending(this).entries
            .sortedBy { it.value.since }
            .mapNotNull { (id, _) ->
                val a = AlarmStore.get(this, id)
                if (a == null) AlarmStore.clearPending(this, id)
                a
            }
            .filter { it.id != ringingAlarm }

    private fun refreshPendingUi(updateNotification: Boolean = true) {
        val list = pendingAlarms()
        overlay.show(list)

        if (ringingAlarm < 0 && list.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (updateNotification && ringingAlarm < 0) {
            startForegroundCompat(Notifications.pendingSummary(this, list), ringing = false)
        }
    }

    private fun startForegroundCompat(n: Notification, ringing: Boolean) {
        when {
            Build.VERSION.SDK_INT >= 34 -> startForeground(
                Notifications.FOREGROUND_ID, n,
                if (ringing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
            else -> startForeground(Notifications.FOREGROUND_ID, n)
        }
    }

    // ---------------- 소리 / 진동 ----------------

    private fun startSound() {
        stopSound()
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: return
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setWakeMode(this@AlarmService, PowerManager.PARTIAL_WAKE_LOCK)
                setDataSource(this@AlarmService, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            player?.release()
            player = null
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        val found: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            getSystemService(Vibrator::class.java)
        }
        val v = found ?: return
        vibrator = v
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), attrs)
    }

    private fun stopSound() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
    }

    override fun onDestroy() {
        stopRingingSound()
        overlay.hide()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_RING = "com.actionalarm.app.RING"
        const val ACTION_RESTORE = "com.actionalarm.app.RESTORE"

        /** 지금 울리고 있는 알람 id (-1: 없음) */
        @Volatile
        var ringingId: Int = -1

        /** 실행 중인 서비스 (같은 프로세스 안에서 직접 호출용) */
        @Volatile
        var instance: AlarmService? = null

        const val RING_DURATION_MS = 3 * 60 * 1000L

        /** 완료 안 한 알람이 있는데 확인창이 없으면 다시 띄움 */
        fun restoreIfNeeded(c: Context) {
            if (instance != null) {
                instance?.refreshPendingUi()
                return
            }
            if (AlarmStore.allPending(c).isEmpty()) return
            try {
                c.startForegroundService(Intent(c, AlarmService::class.java).setAction(ACTION_RESTORE))
            } catch (_: Exception) {
                // 백그라운드에서 시작이 막힌 경우: 다음에 앱을 열 때 복구됨
            }
        }
    }
}
