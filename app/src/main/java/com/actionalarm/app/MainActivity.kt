package com.actionalarm.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.TimePicker
import android.widget.Toast

/** 알람 목록 / 추가·수정·삭제 / 권한 안내 */
class MainActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var tvEmpty: TextView
    private lateinit var tvBanner: TextView
    private val adapter = AlarmAdapter()
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 5000)
        }
    }
    private var bannerAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Notifications.ensureChannels(this)

        listView = findViewById(R.id.listView)
        tvEmpty = findViewById(R.id.tvEmpty)
        tvBanner = findViewById(R.id.tvBanner)
        listView.adapter = adapter

        findViewById<Button>(R.id.btnAdd).setOnClickListener { showEditor(null) }
        listView.setOnItemClickListener { _, _, pos, _ -> showEditor(adapter.getItem(pos)) }
        listView.setOnItemLongClickListener { _, _, pos, _ ->
            confirmDelete(adapter.getItem(pos))
            true
        }
        tvBanner.setOnClickListener { bannerAction?.invoke() }

        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateBanner()
        // 완료 안 한 항목이 있으면 확인창 다시 띄우기 (권한을 방금 허용한 경우 포함)
        AlarmService.restoreIfNeeded(this)
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateBanner()
    }

    private fun refresh() {
        adapter.alarms = AlarmStore.all(this)
        adapter.pending = AlarmStore.allPending(this)
        adapter.notifyDataSetChanged()
        tvEmpty.visibility = if (adapter.alarms.isEmpty()) View.VISIBLE else View.GONE
    }

    // ---------- 권한 안내 ----------

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun updateBanner() {
        val pkg = Uri.parse("package:$packageName")
        val nm = getSystemService(NotificationManager::class.java)
        when {
            !hasNotificationPermission() -> {
                tvBanner.text = "⚠ 알림 권한이 필요해요. 여기를 눌러 허용해 주세요."
                bannerAction = {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ||
                        Build.VERSION.SDK_INT < 33
                    ) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    } else {
                        startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                        )
                    }
                }
            }
            !AlarmScheduler.canScheduleExact(this) -> {
                tvBanner.text = "⚠ '알람 및 리마인더' 권한이 필요해요. 여기를 눌러 허용해 주세요."
                bannerAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg))
                    }
                }
            }
            !Settings.canDrawOverlays(this) -> {
                tvBanner.text = "⚠ 오른쪽 위 확인창을 띄우려면 '다른 앱 위에 표시' 권한이 필요해요. 여기를 눌러 허용해 주세요."
                bannerAction = {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg))
                }
            }
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() -> {
                tvBanner.text = "⚠ 잠금화면에 알람 화면을 띄우려면 '전체 화면 알림' 권한을 허용해 주세요."
                bannerAction = {
                    if (Build.VERSION.SDK_INT >= 34) {
                        startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkg))
                    }
                }
            }
            else -> {
                bannerAction = null
            }
        }
        tvBanner.visibility = if (bannerAction == null) View.GONE else View.VISIBLE
    }

    // ---------- 추가 / 수정 ----------

    private fun showEditor(existing: Alarm?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_alarm, null)
        val picker = view.findViewById<TimePicker>(R.id.timePicker)
        val etReason = view.findViewById<EditText>(R.id.etReason)
        val etAction = view.findViewById<EditText>(R.id.etAction)
        val cbRepeat = view.findViewById<CheckBox>(R.id.cbRepeat)

        picker.setIs24HourView(true)
        if (existing != null) {
            picker.hour = existing.hour
            picker.minute = existing.minute
            etReason.setText(existing.reason)
            etAction.setText(existing.action)
            cbRepeat.isChecked = existing.repeatDaily
        } else {
            cbRepeat.isChecked = true
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "알람 추가" else "알람 수정")
            .setView(view)
            .setPositiveButton("저장") { _, _ ->
                val alarm = Alarm(
                    id = existing?.id ?: AlarmStore.newId(this),
                    hour = picker.hour,
                    minute = picker.minute,
                    reason = etReason.text.toString().trim().ifEmpty { "알람" },
                    action = etAction.text.toString().trim().ifEmpty { "할 일 확인하기" },
                    enabled = true,
                    repeatDaily = cbRepeat.isChecked,
                )
                AlarmStore.upsert(this, alarm)
                val at = AlarmScheduler.schedule(this, alarm)
                if (at != null) toastRemaining(at)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun toastRemaining(at: Long) {
        val totalMin = ((at - System.currentTimeMillis()) / 60_000L).toInt() + 1
        val h = totalMin / 60
        val m = totalMin % 60
        val text = if (h > 0) "${h}시간 ${m}분 후에 알람이 울립니다" else "${m}분 후에 알람이 울립니다"
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDelete(alarm: Alarm) {
        AlertDialog.Builder(this)
            .setTitle("알람 삭제")
            .setMessage("'${alarm.reason}' (${alarm.timeText()}) 알람을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                AlarmScheduler.cancel(this, alarm.id)
                AlarmActions.complete(this, alarm.id)
                AlarmStore.delete(this, alarm.id)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun setEnabled(alarm: Alarm, enabled: Boolean) {
        val updated = alarm.copy(enabled = enabled)
        AlarmStore.upsert(this, updated)
        val at = AlarmScheduler.schedule(this, updated)
        if (at != null) toastRemaining(at)
        refresh()
    }

    // ---------- 목록 ----------

    inner class AlarmAdapter : BaseAdapter() {
        var alarms: List<Alarm> = emptyList()
        var pending: Map<Int, Pending> = emptyMap()

        override fun getCount() = alarms.size
        override fun getItem(position: Int): Alarm = alarms[position]
        override fun getItemId(position: Int) = alarms[position].id.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_alarm, parent, false)
            val alarm = alarms[position]
            val p = pending[alarm.id]

            v.findViewById<TextView>(R.id.tvTime).text = alarm.timeText()
            v.findViewById<TextView>(R.id.tvReason).text =
                alarm.reason + if (alarm.repeatDaily) "  · 매일" else "  · 1회"
            v.findViewById<TextView>(R.id.tvAction).text = "해야 할 일: ${alarm.action}"
            v.alpha = if (alarm.enabled || p != null) 1f else 0.5f

            val tvPending = v.findViewById<TextView>(R.id.tvPending)
            val btnDone = v.findViewById<Button>(R.id.btnDone)
            if (p != null) {
                tvPending.visibility = View.VISIBLE
                btnDone.visibility = View.VISIBLE
                tvPending.text = if (AlarmService.ringingId == alarm.id)
                    "🔔 지금 울리는 중"
                else
                    "⏳ 행동 확인 대기 중 (${formatTime(p.since)}부터)"
                btnDone.setOnClickListener {
                    AlarmActions.complete(this@MainActivity, alarm.id)
                    Toast.makeText(this@MainActivity, "완료 처리했어요 👍", Toast.LENGTH_SHORT).show()
                    refresh()
                }
            } else {
                tvPending.visibility = View.GONE
                btnDone.visibility = View.GONE
                btnDone.setOnClickListener(null)
            }

            val sw = v.findViewById<Switch>(R.id.swEnabled)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = alarm.enabled
            sw.setOnCheckedChangeListener { _, checked -> setEnabled(alarm, checked) }
            return v
        }
    }
}
