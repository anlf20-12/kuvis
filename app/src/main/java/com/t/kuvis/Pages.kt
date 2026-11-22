package com.t.kuvis

import android.Manifest
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.provider.CalendarContract
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 페이지 공통: 위아래 스크롤 + 카드 만들기 */
abstract class Page(val home: HomeActivity) : ScrollView(home) {
    protected val body = LinearLayout(home).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(home.dp(16), home.dp(12), home.dp(16), home.dp(24))
    }

    init {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(body, LayoutParams(MATCH, WRAP))
    }

    abstract fun refresh()

    protected fun card(): LinearLayout = LinearLayout(home).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.card, home.dpf(22f))
        setPadding(home.dp(16), home.dp(14), home.dp(16), home.dp(14))
    }

    protected fun lp(top: Int = 12) = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = home.dp(top) }

    protected fun iconBtn(label: String, onClick: () -> Unit) = TextView(home).apply {
        text = label; textSize = 20f; setTextColor(C.text); gravity = Gravity.CENTER
        setPadding(home.dp(10), home.dp(2), home.dp(10), home.dp(2))
        setOnClickListener { onClick() }
    }
}

// =========================================================================
// 왼쪽: 달력
class CalendarPage(home: HomeActivity) : Page(home) {

    private data class Ev(
        val id: Long, val title: String, val begin: Long, val end: Long,
        val allDay: Boolean, val location: String?, val color: Int
    ) {
        val zone: ZoneId get() = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val startDate: LocalDate get() = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        /** 끝 날짜(포함) */
        val endDate: LocalDate get() {
            val e = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
            return if (e.isBefore(startDate)) startDate else e
        }
    }

    private var ym: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var events: List<Ev> = emptyList()

    override fun refresh() {
        body.removeAllViews()
        val c = card()
        body.addView(c, lp(4))

        // 머리줄
        val head = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconBtn("‹") { ym = ym.minusMonths(1); refresh() })
        head.addView(home.txt("${ym.year}년 ${ym.monthValue}월", 20f, C.text, bold = true))
        head.addView(iconBtn("›") { ym = ym.plusMonths(1); refresh() })
        head.addView(View(home), LinearLayout.LayoutParams(0, 1, 1f))
        head.addView(home.txt("오늘", 15f, C.text).apply {
            setPadding(home.dp(10), home.dp(6), home.dp(10), home.dp(6))
            setOnClickListener { ym = YearMonth.now(); selected = LocalDate.now(); refresh() }
        })
        head.addView(iconBtn("+") { addEvent() })
        c.addView(head)

        if (home.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            c.addView(home.txt("캘린더를 보려면 권한이 필요해요.", 14f, C.dim).apply { setPadding(0, home.dp(16), 0, home.dp(8)) })
            c.addView(Button(home).apply {
                text = "권한 허용"; isAllCaps = false
                setOnClickListener { home.askPermissions() }
            })
            return
        }

        // 요일
        val week = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, home.dp(10), 0, home.dp(4)) }
        listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { i, d ->
            week.addView(home.txt(d, 13f, when (i) { 0 -> C.sun; 6 -> C.sat; else -> C.dim }, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        c.addView(week)

        // 이번 달 칸들 (일요일 시작, 6주)
        val first = ym.atDay(1)
        val gridStart = first.minusDays((first.dayOfWeek.value % 7).toLong())
        events = loadEvents(gridStart, gridStart.plusDays(42))
        val today = LocalDate.now()
        for (w in 0 until 6) {
            val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL }
            for (d in 0 until 7) {
                val date = gridStart.plusDays((w * 7 + d).toLong())
                row.addView(dayCell(date, d, today), LinearLayout.LayoutParams(0, home.dp(74), 1f))
            }
            c.addView(row)
        }

        // 고른 날 일정
        val list = card()
        body.addView(list, lp())
        val f = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
        list.addView(home.txt(selected.format(f), 16f, C.text, bold = true))
        val dayEvents = events.filter { !selected.isBefore(it.startDate) && !selected.isAfter(it.endDate) }
            .sortedWith(compareBy<Ev> { !it.allDay }.thenBy { it.begin })
        if (dayEvents.isEmpty()) {
            list.addView(home.txt("일정이 없어요. 쿠비스에게 말하거나 + 를 눌러 추가하세요.", 14f, C.dim).apply {
                setPadding(0, home.dp(8), 0, 0)
            })
        }
        val tf = DateTimeFormatter.ofPattern("a h:mm", Locale.KOREAN)
        for (e in dayEvents) {
            val time = if (e.allDay) "종일" else
                Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()).format(tf) + " ~ " +
                        Instant.ofEpochMilli(e.end).atZone(ZoneId.systemDefault()).format(tf)
            val row = LinearLayout(home).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, home.dp(10), 0, 0)
                setOnClickListener {
                    home.safeStart(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)))
                }
            }
            row.addView(View(home).apply { background = rounded(e.color, home.dpf(2f)) },
                LinearLayout.LayoutParams(home.dp(4), MATCH).apply { rightMargin = home.dp(10) })
            val col = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL }
            col.addView(home.txt(e.title, 15f, C.text))
            col.addView(home.txt(time + (e.location?.let { "  ·  $it" } ?: ""), 12f, C.dim))
            row.addView(col)
            list.addView(row)
        }
    }

    private fun dayCell(date: LocalDate, dow: Int, today: LocalDate): View {
        val cell = LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(home.dp(1), home.dp(3), home.dp(1), 0)
            if (date == selected) background = rounded(Color.argb(60, 255, 255, 255), home.dpf(8f))
            setOnClickListener {
                selected = date
                if (YearMonth.from(date) != ym) ym = YearMonth.from(date)
                refresh()
            }
        }
        val inMonth = YearMonth.from(date) == ym
        val base = when (dow) { 0 -> C.sun; 6 -> C.sat; else -> C.text }
        val num = home.txt(date.dayOfMonth.toString(), 13f, if (inMonth) base else Color.argb(90, 255, 255, 255), gravity = Gravity.CENTER)
        if (date == today) {
            num.setTextColor(Color.WHITE)
            num.background = rounded(C.err, home.dpf(11f))
        }
        val numWrap = FrameLayout(home)
        numWrap.addView(num, FrameLayout.LayoutParams(home.dp(22), home.dp(22), Gravity.CENTER_HORIZONTAL))
        cell.addView(numWrap)

        val todays = events.filter { !date.isBefore(it.startDate) && !date.isAfter(it.endDate) }
            .sortedWith(compareBy<Ev> { !it.allDay }.thenBy { it.begin })
        for (e in todays.take(3)) {
            cell.addView(TextView(home).apply {
                text = e.title; textSize = 9.5f; setTextColor(Color.WHITE)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(home.dp(2), 0, home.dp(2), 0)
                background = rounded(e.color, home.dpf(3f))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = home.dp(2) })
        }
        if (todays.size > 3) cell.addView(home.txt("+${todays.size - 3}", 9f, C.dim, gravity = Gravity.CENTER))
        return cell
    }

    private fun loadEvents(from: LocalDate, to: LocalDate): List<Ev> {
        val zone = ZoneId.systemDefault()
        // 종일 일정(UTC 기준) 때문에 앞뒤로 하루씩 넉넉히
        val begin = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.DISPLAY_COLOR
        )
        val out = mutableListOf<Ev>()
        try {
            CalendarContract.Instances.query(home.contentResolver, proj, begin, end)?.use { c ->
                while (c.moveToNext()) {
                    val color = if (c.isNull(6)) C.chip else (c.getInt(6) or 0xFF000000.toInt())
                    out.add(Ev(c.getLong(0), c.getString(1) ?: "(제목 없음)", c.getLong(2), c.getLong(3),
                        c.getInt(4) == 1, c.getString(5)?.takeIf { it.isNotBlank() }, color))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    private fun addEvent() {
        val start = selected.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        home.safeStart(
            Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, start + 3_600_000L)
        )
    }
}

// =========================================================================
// 가운데: 날씨 · 시계 · 할 일
class CenterPage(home: HomeActivity) : Page(home) {

    private val store = Store(home)
    private val weatherBox = card()
    private val todoBox = card()

    init {
        // 맨 위: 설정
        val top = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        top.addView(iconBtn("⚙") { home.showMenu() })
        body.addView(top)

        body.addView(weatherBox, lp(0))

        // 시계
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
        body.addView(clock, lp())

        body.addView(todoBox, lp())

        // 쿠비스 부르기 버튼
        val call = home.txt("🎙  쿠비스", 17f, Color.parseColor("#04121F"), bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(C.accent, home.dpf(28f))
            setPadding(home.dp(24), home.dp(14), home.dp(24), home.dp(14))
            setOnClickListener { home.callKuvis() }
        }
        body.addView(call, LinearLayout.LayoutParams(WRAP, WRAP).apply {
            topMargin = home.dp(16); gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    override fun refresh() {
        setWeather(Weather.cached(home))
        refreshTodos()
    }

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

// =========================================================================
// 오른쪽: 메모
class MemoPage(home: HomeActivity) : Page(home) {

    private val store = Store(home)

    override fun refresh() {
        body.removeAllViews()
        val head = LinearLayout(home).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(C.card, home.dpf(22f))
            setPadding(home.dp(16), home.dp(10), home.dp(8), home.dp(10))
        }
        head.addView(home.txt("메모", 18f, C.text, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(iconBtn("+") { edit(null) })
        body.addView(head, lp(4))

        val memos = store.memos()
        if (memos.isEmpty()) {
            body.addView(card().apply {
                addView(home.txt("저장해 둔 메모가 없어요.\n\"메모해 줘, ○○\"처럼 말하면 여기에 저장돼요.", 14f, C.dim))
            }, lp())
        }
        val df = DateTimeFormatter.ofPattern("M월 d일", Locale.KOREAN)
        for (m in memos) {
            val c = card().apply { setOnClickListener { edit(m) } }
            val lines = m.text.trim().lines()
            c.addView(home.txt((if (m.pinned) "📌 " else "") + lines.first(), 16f, C.text, bold = true).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            val rest = lines.drop(1).joinToString("\n").trim()
            if (rest.isNotEmpty()) c.addView(home.txt(rest, 14f, C.text).apply {
                maxLines = 8; ellipsize = TextUtils.TruncateAt.END; setPadding(0, home.dp(4), 0, 0)
            })
            c.addView(home.txt(Instant.ofEpochMilli(m.updated).atZone(ZoneId.systemDefault()).format(df), 11f, C.dim).apply {
                setPadding(0, home.dp(6), 0, 0)
            })
            body.addView(c, lp(10))
        }
    }

    private fun edit(m: Memo?) {
        val et = EditText(home).apply {
            setText(m?.text ?: ""); hint = "첫 줄이 제목이 돼요"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4; gravity = Gravity.TOP
        }
        val wrap = FrameLayout(home).apply { setPadding(home.dp(20), home.dp(8), home.dp(20), 0); addView(et) }
        val b = AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (m == null) "새 메모" else "메모")
            .setView(wrap)
            .setPositiveButton("저장") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isNotEmpty()) {
                    if (m == null) store.addMemo(text) else store.editMemo(m.id) { it.copy(text = text) }
                }
                refresh()
            }
            .setNegativeButton(if (m == null) "취소" else "삭제") { _, _ ->
                if (m != null) {
                    AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("이 메모를 지울까요?")
                        .setPositiveButton("지우기") { _, _ -> store.editMemo(m.id) { null }; refresh() }
                        .setNegativeButton("아니요", null).show()
                }
            }
        if (m != null) {
            b.setNeutralButton(if (m.pinned) "고정 풀기" else "맨 위 고정") { _, _ ->
                store.editMemo(m.id) { it.copy(pinned = !it.pinned) }; refresh()
            }
        }
        b.show()
    }
}
