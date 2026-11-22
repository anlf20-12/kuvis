package com.t.kuvis

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 날씨 위젯 */
class WeatherWidget(home: HomeActivity) : Page(home) {
    private val weatherBox = card()

    init {
        body.addView(weatherBox, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    override fun refresh() { setWeather(Weather.cached(home)) }

    fun setWeather(w: WInfo?) {
        weatherBox.removeAllViews()
        if (w == null) {
            weatherBox.addView(home.txt("날씨를 불러오는 중…", 14f, C.dim))
            return
        }
        val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL }
        left.addView(home.txt("${Weather.icon(w.code)} ${w.temp.toInt()}°", 34f, C.text, bold = true))
        left.addView(home.txt(Weather.desc(w.code), 15f, C.text))
        val tomorrow = w.days.getOrNull(1)
        if (tomorrow != null && tomorrow.rain >= 50) left.addView(home.txt("내일 비 올 수 있어요 (${tomorrow.rain}%)", 12f, C.dim))
        left.addView(home.txt("📍 ${w.place}", 12f, C.dim))
        row.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))

        val right = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL }
        val df = DateTimeFormatter.ofPattern("E", Locale.KOREAN)
        for (d in w.days.take(4)) {
            val name = try { LocalDate.parse(d.date).format(df) } catch (_: Exception) { "" }
            val r = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(home.txt(name, 14f, C.dim), LinearLayout.LayoutParams(home.dp(24), WRAP))
            r.addView(home.txt(Weather.icon(d.code), 14f), LinearLayout.LayoutParams(home.dp(30), WRAP))
            r.addView(home.txt("${d.max.toInt()}° ${d.min.toInt()}°", 14f, C.text, gravity = Gravity.END),
                LinearLayout.LayoutParams(home.dp(70), WRAP))
            right.addView(r)
        }
        row.addView(right)
        weatherBox.addView(row)
    }

}

/** 시계 위젯 */
class ClockWidget(home: HomeActivity) : Page(home) {
    init {
        val clock = card().apply { gravity = Gravity.CENTER }
        clock.addView(TextClock(home).apply {
            format12Hour = "a h:mm"; format24Hour = "HH:mm"
            textSize = 46f; setTextColor(C.text); gravity = Gravity.CENTER
            textLocale = Locale.KOREAN
        })
        clock.addView(TextClock(home).apply {
            format12Hour = "M월 d일 EEEE"; format24Hour = "M월 d일 EEEE"
            textSize = 15f; setTextColor(C.dim); gravity = Gravity.CENTER
            textLocale = Locale.KOREAN
        })
        body.addView(clock, LinearLayout.LayoutParams(MATCH, MATCH))
        body.gravity = Gravity.CENTER
    }
    override fun refresh() {}
}

/** 쿠비스 부르기 버튼 위젯 (작은 ⚙는 쿠비스 설정) */
class KuvisButtonWidget(home: HomeActivity) : FrameLayout(home) {
    init {
        val call = home.txt("🎙  쿠비스", 17f, Color.parseColor("#04121F"), bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(C.accent, home.dpf(28f))
            setPadding(home.dp(16), home.dp(10), home.dp(16), home.dp(10))
            setOnClickListener { home.callKuvis() }
        }
        addView(call, LayoutParams(MATCH, MATCH).apply { setMargins(home.dp(4), home.dp(6), home.dp(4), home.dp(6)) })
    }
}

/** 할 일 위젯 */
class TodoWidget(home: HomeActivity) : Page(home) {

    private val store = Store(home)
    private val todoBox = card()

    init {
        body.addView(todoBox, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    override fun refresh() {
        refreshTodos()
    }

    fun refreshTodos() {
        todoBox.removeAllViews()
        val head = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(home.txt("할 일", 17f, C.text, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(iconBtn("+") { editTodo(null) })
        todoBox.addView(head)

        val today = LocalDate.now().toString()
        val all = store.todos()
        val open = all.filter { !it.done }
        val sections = listOf(
            "지난 일" to open.filter { it.due != null && it.due < today }.sortedBy { it.due },
            "오늘" to open.filter { it.due == today },
            "다가오는 일" to open.filter { it.due != null && it.due > today }.sortedBy { it.due },
            "언제든" to open.filter { it.due == null },
            "오늘 끝낸 일" to all.filter { it.done && Instant.ofEpochMilli(it.doneAt).atZone(ZoneId.systemDefault()).toLocalDate().toString() == today }
        )
        if (open.isEmpty() && sections.last().second.isEmpty()) {
            todoBox.addView(home.txt("할 일이 없어요. \"내일까지 ○○ 할 일 넣어 줘\"처럼 말해 보세요.", 14f, C.dim).apply {
                setPadding(0, home.dp(8), 0, 0)
            })
        }
        val df = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN)
        for ((title, list) in sections) {
            if (list.isEmpty()) continue
            todoBox.addView(home.txt(title, 12f, if (title == "지난 일") C.sun else C.dim).apply { setPadding(0, home.dp(10), 0, home.dp(2)) })
            for (t in list) {
                val row = LinearLayout(home).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, home.dp(4), 0, home.dp(4))
                }
                row.addView(home.txt(if (t.done) "●" else "○", 20f, if (t.done) C.green else C.text).apply {
                    setPadding(0, 0, home.dp(10), 0)
                    setOnClickListener { store.setDone(t.id, !t.done); refreshTodos() }
                })
                val label = home.txt(t.text, 16f, if (t.done) C.dim else C.text).apply {
                    if (t.done) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                    setOnClickListener { editTodo(t) }
                }
                row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
                if (t.due != null && title != "오늘") {
                    val ds = try { LocalDate.parse(t.due).format(df) } catch (_: Exception) { t.due }
                    row.addView(home.txt(ds, 12f, C.dim))
                }
                todoBox.addView(row)
            }
        }
    }

    private fun editTodo(t: Todo?) {
        val box = LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(home.dp(20), home.dp(8), home.dp(20), 0)
        }
        val et = EditText(home).apply { setText(t?.text ?: ""); hint = "할 일" }
        box.addView(et)
        var due: String? = t?.due
        val dueBtn = Button(home).apply { isAllCaps = false }
        fun showDue() { dueBtn.text = due?.let { "기한: $it  (누르면 바꾸기)" } ?: "기한 정하기" }
        showDue()
        dueBtn.setOnClickListener {
            val d = due?.let { try { LocalDate.parse(it) } catch (_: Exception) { null } } ?: LocalDate.now()
            DatePickerDialog(home, { _, y, m, day -> due = LocalDate.of(y, m + 1, day).toString(); showDue() },
                d.year, d.monthValue - 1, d.dayOfMonth).apply {
                setButton(DatePickerDialog.BUTTON_NEUTRAL, "기한 없음") { _, _ -> due = null; showDue() }
            }.show()
        }
        box.addView(dueBtn)
        val b = AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (t == null) "할 일 추가" else "할 일 고치기")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (t == null) store.addTodo(text, due)
                    else store.editTodo(t.id) { it.copy(text = text, due = due) }
                }
                refreshTodos()
            }
            .setNegativeButton("취소", null)
        if (t != null) b.setNeutralButton("삭제") { _, _ -> store.editTodo(t.id) { null }; refreshTodos() }
        b.show()
    }
}

