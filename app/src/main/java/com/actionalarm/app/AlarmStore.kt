package com.actionalarm.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val EXTRA_ID = "alarm_id"
const val EXTRA_KIND = "kind"
const val KIND_MAIN = 0        // 설정한 시각의 알람

fun formatTime(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

/** 알람 하나: 울릴 시각 + 울리는 이유 + 해야 할 행동 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val reason: String,
    val action: String,
    val enabled: Boolean = true,
    val repeatDaily: Boolean = true,
) {
    fun timeText(): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hour", hour)
        .put("minute", minute)
        .put("reason", reason)
        .put("action", action)
        .put("enabled", enabled)
        .put("repeatDaily", repeatDaily)

    companion object {
        fun fromJson(o: JSONObject) = Alarm(
            id = o.getInt("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            reason = o.optString("reason"),
            action = o.optString("action"),
            enabled = o.optBoolean("enabled", true),
            repeatDaily = o.optBoolean("repeatDaily", true),
        )
    }
}

/**
 * 알람이 울렸지만 아직 사용자가 "완료했어요"를 누르지 않은 상태.
 * (오른쪽 위 작은 확인창이 떠 있는 동안)
 * @param since 처음 울린 시각
 * @param nextAt (사용 안 함, 이전 버전 호환용)
 * @param count 울린 횟수
 */
data class Pending(val since: Long, val nextAt: Long, val count: Int)

/** SharedPreferences 에 JSON 으로 저장 (외부 라이브러리 없음) */
object AlarmStore {
    private const val PREFS = "action_alarm"
    private const val KEY_ALARMS = "alarms"
    private const val KEY_PENDING = "pending"
    private const val KEY_NEXT_ID = "next_id"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): List<Alarm> {
        val arr = JSONArray(prefs(c).getString(KEY_ALARMS, "[]") ?: "[]")
        return (0 until arr.length())
            .map { Alarm.fromJson(arr.getJSONObject(it)) }
            .sortedWith(compareBy({ it.hour }, { it.minute }))
    }

    fun get(c: Context, id: Int): Alarm? = all(c).firstOrNull { it.id == id }

    @Synchronized
    private fun saveAll(c: Context, list: List<Alarm>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(KEY_ALARMS, arr.toString()).commit()
    }

    @Synchronized
    fun upsert(c: Context, alarm: Alarm) {
        saveAll(c, all(c).filter { it.id != alarm.id } + alarm)
    }

    @Synchronized
    fun delete(c: Context, id: Int) {
        saveAll(c, all(c).filter { it.id != id })
    }

    @Synchronized
    fun newId(c: Context): Int {
        val p = prefs(c)
        val id = p.getInt(KEY_NEXT_ID, 1)
        p.edit().putInt(KEY_NEXT_ID, id + 1).commit()
        return id
    }

    // ---- 완료 대기 상태 ----

    private fun pendingJson(c: Context) = JSONObject(prefs(c).getString(KEY_PENDING, "{}") ?: "{}")

    @Synchronized
    fun pending(c: Context, id: Int): Pending? {
        val o = pendingJson(c).optJSONObject(id.toString()) ?: return null
        return Pending(o.getLong("since"), o.optLong("nextAt", 0L), o.optInt("count", 0))
    }

    @Synchronized
    fun allPending(c: Context): Map<Int, Pending> {
        val root = pendingJson(c)
        val result = mutableMapOf<Int, Pending>()
        root.keys().forEach { key ->
            val o = root.getJSONObject(key)
            result[key.toInt()] = Pending(o.getLong("since"), o.optLong("nextAt", 0L), o.optInt("count", 0))
        }
        return result
    }

    @Synchronized
    fun setPending(c: Context, id: Int, p: Pending) {
        val root = pendingJson(c)
        root.put(
            id.toString(),
            JSONObject().put("since", p.since).put("nextAt", p.nextAt).put("count", p.count)
        )
        prefs(c).edit().putString(KEY_PENDING, root.toString()).commit()
    }

    @Synchronized
    fun clearPending(c: Context, id: Int) {
        val root = pendingJson(c)
        root.remove(id.toString())
        prefs(c).edit().putString(KEY_PENDING, root.toString()).commit()
    }

    /** 알람이 울리기 시작할 때 호출: 완료 대기 상태로 만들고 횟수 +1 */
    @Synchronized
    fun markRinging(c: Context, id: Int) {
        val cur = pending(c, id)
        setPending(c, id, Pending(cur?.since ?: System.currentTimeMillis(), 0L, (cur?.count ?: 0) + 1))
    }
}
