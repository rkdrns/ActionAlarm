package com.actionalarm.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 오른쪽 위에 떠 있는 작은 확인창 ("다른 앱 위에 표시" 권한 필요).
 * 완료 대기 중인 알람마다 카드 하나. [완료했어요]를 누르면 그 카드가 사라집니다.
 * 카드 윗부분을 끌면 창 위치를 옮길 수 있습니다.
 */
class PendingOverlay(
    context: Context,
    private val onDone: (Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val ctx = ContextThemeWrapper(context, R.style.AppTheme)
    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null

    // 사용자가 옮긴 위치 기억 (서비스가 살아있는 동안)
    private var posX = dp(8)
    private var posY = dp(40)

    fun canShow(): Boolean = Settings.canDrawOverlays(appContext)

    fun show(alarms: List<Alarm>) {
        if (alarms.isEmpty() || !canShow()) {
            hide()
            return
        }
        val r = root ?: create() ?: return
        r.removeAllViews()
        val inflater = LayoutInflater.from(ctx)
        alarms.forEach { alarm ->
            val card = inflater.inflate(R.layout.overlay_card, r, false)
            // 한 줄 질문: "혈압약 1알 복용하기 완료?"  [YES]
            card.findViewById<TextView>(R.id.tvOverlayQuestion).text = "${alarm.action} 완료?"
            card.findViewById<View>(R.id.btnOverlayYes).setOnClickListener { onDone(alarm.id) }
            attachDrag(card.findViewById(R.id.tvOverlayQuestion))
            r.addView(card)
        }
    }

    fun hide() {
        root?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) {
            }
        }
        root = null
        params = null
    }

    private fun create(): LinearLayout? {
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.END   // 오른쪽 정렬로 쌓기
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 확인창 밖의 터치는 뒤에 있는 앱으로 그대로 전달
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            x = posX
            y = posY
        }
        return try {
            wm.addView(r, p)
            root = r
            params = p
            r
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, e ->
            val p = params ?: return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // gravity 가 END 이므로 x 는 오른쪽 끝에서의 거리
                    p.x = (startX - (e.rawX - downX)).toInt().coerceAtLeast(0)
                    p.y = (startY + (e.rawY - downY)).toInt().coerceAtLeast(0)
                    posX = p.x; posY = p.y
                    root?.let { wm.updateViewLayout(it, p) }
                    true
                }
                else -> true
            }
        }
    }

    private fun dp(v: Int): Int = (v * appContext.resources.displayMetrics.density).toInt()
}
