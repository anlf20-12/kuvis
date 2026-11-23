package com.t.kuvis

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** 한 학기 시간표 (제자훈련): 오전·오후·모임 × 월~금, 칸마다 이름 하나와 '고정' 표시 */
object Timetable {
    val DAYS = listOf("월", "화", "수", "목", "금")
    val SLOTS = listOf("오전", "오후", "모임")

    data class Cell(val name: String, val fixed: Boolean)

    fun load(ctx: Context): Array<Array<Cell>> {
        val out = Array(SLOTS.size) { Array(DAYS.size) { Cell("", false) } }
        try {
            val a = JSONArray(Prefs(ctx).p.getString("timetable", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val r = o.optInt("r", -1); val c = o.optInt("c", -1)
                if (r in SLOTS.indices && c in DAYS.indices) out[r][c] = Cell(o.optString("name"), o.optBoolean("fixed"))
            }
        } catch (_: Exception) {}
        return out
    }

    fun set(ctx: Context, r: Int, c: Int, cell: Cell) {
        val t = load(ctx); t[r][c] = cell
        val a = JSONArray()
        for (rr in t.indices) for (cc in t[rr].indices) {
            val x = t[rr][cc]
            if (x.name.isNotBlank() || x.fixed) a.put(JSONObject().put("r", rr).put("c", cc).put("name", x.name).put("fixed", x.fixed))
        }
        Prefs(ctx).p.edit().putString("timetable", a.toString()).apply()
    }

    /** 오늘 요일 번호 (월=0 … 금=4, 주말은 -1) */
    fun today(): Int = when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> 0; Calendar.TUESDAY -> 1; Calendar.WEDNESDAY -> 2
        Calendar.THURSDAY -> 3; Calendar.FRIDAY -> 4; else -> -1
    }

    /** 쿠비스가 읽을 글 */
    fun text(ctx: Context): String {
        val t = load(ctx)
        val sb = StringBuilder("제자훈련 시간표 (오늘: ${DAYS.getOrNull(today()) ?: "주말"})\n")
        for (c in DAYS.indices) {
            sb.append(DAYS[c]).append(": ")
            sb.append(SLOTS.indices.joinToString(", ") { r ->
                val x = t[r][c]; "${SLOTS[r]} " + (if (x.name.isBlank()) "비어 있음" else x.name + if (x.fixed) "(고정)" else "")
            })
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}

/** 3번 화면 아래 시간표 카드 */
class TimetableCard(private val home: HomeActivity) : LinearLayout(home) {
    private val todayFill = Color.argb(80, 250, 199, 117)
    private val todayFixed = Color.rgb(201, 128, 32)
    private val normalFill = Color.argb(38, 255, 255, 255)
    private val fixedFill = Color.rgb(47, 86, 150)

    init {
        orientation = VERTICAL
        background = rounded(C.card, home.dpf(22f))
        setPadding(home.dp(12), home.dp(12), home.dp(12), home.dp(12))
        refresh()
    }

    fun refresh() {
        removeAllViews()
        addView(home.txt("제자훈련", 16f, C.text, bold = true).apply { setPadding(home.dp(4), 0, 0, home.dp(8)) })
        val t = Timetable.load(home)
        val today = Timetable.today()
        // 요일 머리
        val head = LinearLayout(home).apply { orientation = HORIZONTAL }
        head.addView(TextView(home), LayoutParams(home.dp(44), WRAP_CONTENT))
        Timetable.DAYS.forEachIndexed { c, d ->
            head.addView(home.txt(d, 13f, if (c == today) Color.rgb(250, 199, 117) else C.dim, bold = c == today, gravity = Gravity.CENTER),
                LayoutParams(0, WRAP_CONTENT, 1f))
        }
        addView(head, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = home.dp(4) })
        Timetable.SLOTS.forEachIndexed { r, slot ->
            val row = LinearLayout(home).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(home.txt(slot, 13f, C.dim, gravity = Gravity.CENTER_VERTICAL), LayoutParams(home.dp(44), home.dp(42)))
            Timetable.DAYS.indices.forEach { c ->
                val cell = t[r][c]
                val isToday = c == today
                val fill = when {
                    cell.fixed && isToday -> todayFixed
                    cell.fixed -> fixedFill
                    isToday -> todayFill
                    else -> normalFill
                }
                val tv = TextView(home).apply {
                    text = cell.name; textSize = 13f; gravity = Gravity.CENTER
                    setTextColor(if (cell.fixed) Color.WHITE else C.text)
                    if (cell.fixed) typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    background = rounded(fill, home.dpf(10f))
                    setPadding(home.dp(2), 0, home.dp(2), 0)
                    setOnClickListener { edit(r, c) }
                }
                row.addView(tv, LayoutParams(0, home.dp(42), 1f).apply { leftMargin = home.dp(3); rightMargin = home.dp(3) })
            }
            addView(row, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = home.dp(5) })
        }
    }

    private fun edit(r: Int, c: Int) {
        val cur = Timetable.load(home)[r][c]
        val box = LinearLayout(home).apply { orientation = VERTICAL; setPadding(home.dp(22), home.dp(8), home.dp(22), 0) }
        val et = EditText(home).apply {
            setText(cur.name); setSelection(cur.name.length); hint = "이름"
            inputType = InputType.TYPE_CLASS_TEXT; setSingleLine()
        }
        val fixed = CheckBox(home).apply { text = "고정 (진한 색으로 칠하기)"; isChecked = cur.fixed }
        box.addView(et); box.addView(fixed)
        val dlg = AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${Timetable.DAYS[c]}요일 ${Timetable.SLOTS[r]}")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                Timetable.set(home, r, c, Timetable.Cell(et.text.toString().trim(), fixed.isChecked)); refresh()
            }
            .setNeutralButton("비우기") { _, _ -> Timetable.set(home, r, c, Timetable.Cell("", false)); refresh() }
            .setNegativeButton("취소", null)
            .create()
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        et.requestFocus()
    }
}
