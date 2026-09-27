package com.actionalarm.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 알람 화면.
 * 1단계(울리는 중): [알람 끄기] → 소리 멈춤 + 이 화면은 닫히고 오른쪽 위 작은 확인창으로 전환
 * (확인창 권한이 없으면 이 화면에서 [완료했어요]를 보여줌)
 */
class AlarmActivity : Activity() {

    private var alarmId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var tvClock: TextView
    private lateinit var tvReason: TextView
    private lateinit var tvAction: TextView
    private lateinit var tvState: TextView
    private lateinit var btnSilence: Button
    private lateinit var confirmGroup: View
    private lateinit var tvInfo: TextView
    private lateinit var btnDone: Button
    private lateinit var btnLater: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alarmId = intent.getIntExtra(EXTRA_ID, -1)
        // 이미 알람이 꺼졌으면 화면을 그리지도 않고 바로 닫음 (깜빡임 방지)
        if (shouldCloseImmediately()) {
            finish()
            return
        }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_alarm)

        tvClock = findViewById(R.id.tvClock)
        tvReason = findViewById(R.id.tvReason)
        tvAction = findViewById(R.id.tvAction)
        tvState = findViewById(R.id.tvState)
        btnSilence = findViewById(R.id.btnSilence)
        confirmGroup = findViewById(R.id.confirmGroup)
        tvInfo = findViewById(R.id.tvInfo)
        btnDone = findViewById(R.id.btnDone)
        btnLater = findViewById(R.id.btnLater)

        btnSilence.setOnClickListener {
            AlarmActions.silence(this, alarmId)
            if (Settings.canDrawOverlays(this)) {
                // 큰 화면은 닫고, 오른쪽 위 작은 확인창으로 전환
                Toast.makeText(this, "오른쪽 위 확인창에서 완료를 눌러 주세요", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                render()
            }
        }
        btnDone.setOnClickListener {
            AlarmActions.complete(this, alarmId)
            Toast.makeText(this, "완료! 확인창을 닫았어요 👍", Toast.LENGTH_SHORT).show()
            finish()
        }
        btnLater.setOnClickListener { finish() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alarmId = intent.getIntExtra(EXTRA_ID, alarmId)
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    /**
     * 작은 확인창을 쓸 수 있는 폰에서는 이 큰 화면은 "울리는 동안"에만 보여줌.
     * (화면이 꺼져 있다 울린 경우, 늦게 도착한 화면 열기 요청 때문에
     *  알람을 끈 뒤 이 화면이 다시 뜨는 것을 막음)
     */
    private fun shouldCloseImmediately(): Boolean =
        AlarmService.ringingId != alarmId && Settings.canDrawOverlays(this)

    private fun render() {
        val alarm = AlarmStore.get(this, alarmId)
        if (alarm == null || shouldCloseImmediately()) {
            finish()
            return
        }
        tvClock.text = formatTime(System.currentTimeMillis())
        tvReason.text = alarm.reason
        tvAction.text = alarm.action

        val ringing = AlarmService.ringingId == alarmId
        val pending = AlarmStore.pending(this, alarmId)

        when {
            ringing -> {
                tvState.text = "🔔 알람이 울리고 있어요"
                btnSilence.visibility = View.VISIBLE
                confirmGroup.visibility = View.GONE
            }
            pending != null -> {
                tvState.text = "위 행동을 하셨나요?"
                btnSilence.visibility = View.GONE
                confirmGroup.visibility = View.VISIBLE
                btnDone.visibility = View.VISIBLE
                tvInfo.text = if (Settings.canDrawOverlays(this))
                    "닫아도 화면 오른쪽 위 작은 확인창에 계속 떠 있어요."
                else
                    "완료를 누를 때까지 알림창에 계속 남아 있어요."
                btnLater.text = "아직 안 했어요 (닫기)"
            }
            else -> {
                tvState.text = "✅ 이미 완료된 알람이에요"
                btnSilence.visibility = View.GONE
                confirmGroup.visibility = View.VISIBLE
                btnDone.visibility = View.GONE
                tvInfo.text = ""
                btnLater.text = "닫기"
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 울리는 중에는 뒤로가기로 닫히지 않게 (반드시 '알람 끄기'를 누르도록)
        if (AlarmService.ringingId == alarmId) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        fun intent(c: Context, id: Int): Intent =
            Intent(c, AlarmActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
