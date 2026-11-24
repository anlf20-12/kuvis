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
    val body = LinearLayout(home).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(home.dp(2), home.dp(2), home.dp(2), home.dp(8))
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
        val allDay: Boolean, val location: String?, val color: Int,
        val holiday: Boolean = false, val recurring: Boolean = false
    ) {
        val zone: ZoneId get() = if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()
        val startDate: LocalDate get() = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        /** 끝 날짜(포함) */
        val endDate: LocalDate get() {
            val e = Instant.ofEpochMilli(maxOf(begin, end - 1)).atZone(zone).toLocalDate()
            return if (e.isBefore(startDate)) startDate else e
        }
    }

    // 차분한 색 (T가 고른 ②번)
    private val HOLIDAY_COLOR = Color.rgb(64, 128, 86)
    private val REPEAT_COLOR = Color.rgb(70, 104, 150)
    private val ONCE_COLOR = Color.rgb(156, 104, 80)
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
        head.addView(home.txt("${ym.year}년 ${ym.monthValue}월", 20f, C.text, bold = true).apply {
            setOnLongClickListener { showHidden(); true }
        })
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
            week.addView(home.txt(d, 14f, when (i) { 0 -> C.sun; 6 -> C.sat; else -> C.dim }, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        c.addView(week)

        // 이번 달 칸들 (일요일 시작, 6주)
        val first = ym.atDay(1)
        val gridStart = first.minusDays((first.dayOfWeek.value % 7).toLong())
        events = loadEvents(gridStart, gridStart.plusDays(42))
        val today = LocalDate.now()
        for (w in 0 until 6) c.addView(weekRow(gridStart.plusDays((w * 7).toLong()), today), LinearLayout.LayoutParams(MATCH, home.dp(86)))

        // 고른 날 일정
        val list = card()
        body.addView(list, lp())
        val f = DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN)
        list.addView(home.txt(selected.format(f), 16f, C.text, bold = true))
        val dayEvents = events.filter { !selected.isBefore(it.startDate) && !selected.isAfter(it.endDate) }
            .sortedWith(compareBy<Ev> { !it.allDay }.thenBy { it.begin })
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
                // 꾹 누르면 쿠비스 달력에서 숨기기 (공휴일처럼 캘린더 앱에서 못 지우는 것도)
                setOnLongClickListener { askHide(e); true }
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

    /** 한 주: 아래는 날짜 칸(누르면 선택), 위에는 일정 막대. 여러 날 이어지는 일정은 한 막대로 이어 그림 */
    private fun weekRow(d0: LocalDate, today: LocalDate): View {
        val d6 = d0.plusDays(6)
        val frame = FrameLayout(home)
        val days = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL }
        for (d in 0 until 7) days.addView(dayCell(d0.plusDays(d.toLong()), d, today), LinearLayout.LayoutParams(0, MATCH, 1f))
        frame.addView(days, FrameLayout.LayoutParams(MATCH, MATCH))

        // 이 주에 걸친 일정 → 칸(줄) 배정
        data class Seg(val e: Ev, val from: Int, val to: Int)
        val segs = events.filter { !it.endDate.isBefore(d0) && !it.startDate.isAfter(d6) }.map {
            val a = if (it.startDate.isBefore(d0)) 0 else (it.startDate.toEpochDay() - d0.toEpochDay()).toInt()
            val b = if (it.endDate.isAfter(d6)) 6 else (it.endDate.toEpochDay() - d0.toEpochDay()).toInt()
            Seg(it, a, b)
        }.sortedWith(compareBy<Seg> { -(it.to - it.from) }.thenBy { it.from }.thenBy { !it.e.allDay }.thenBy { it.e.begin })
        val maxLanes = 3
        val lanes = mutableListOf<MutableList<Seg>>()
        val hidden = IntArray(7)
        for (sg in segs) {
            val lane = lanes.indexOfFirst { l -> l.none { it.from <= sg.to && sg.from <= it.to } }
            when {
                lane >= 0 -> lanes[lane].add(sg)
                lanes.size < maxLanes -> lanes.add(mutableListOf(sg))
                else -> for (c in sg.from..sg.to) hidden[c]++
            }
        }
        val bars = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; setPadding(0, home.dp(28), 0, 0) }
        for (l in lanes) {
            val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL }
            var col = 0
            for (sg in l.sortedBy { it.from }) {
                if (sg.from > col) row.addView(View(home), LinearLayout.LayoutParams(0, 1, (sg.from - col).toFloat()))
                val contL = sg.e.startDate.isBefore(d0); val contR = sg.e.endDate.isAfter(d6)
                val r = home.dpf(3f)
                row.addView(TextView(home).apply {
                    text = sg.e.title; textSize = 11.5f; setTextColor(Color.WHITE)
                    includeFontPadding = false
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    setPadding(home.dp(3), 0, home.dp(2), 0)
                    // 여러 날 이어지는 일정은 이름을 막대 가운데에
                    if (sg.to > sg.from) gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(sg.e.color)
                        cornerRadii = floatArrayOf(if (contL) 0f else r, if (contL) 0f else r, if (contR) 0f else r, if (contR) 0f else r,
                            if (contR) 0f else r, if (contR) 0f else r, if (contL) 0f else r, if (contL) 0f else r)
                    }
                }, LinearLayout.LayoutParams(0, home.dp(17), (sg.to - sg.from + 1).toFloat()).apply {
                    leftMargin = if (contL) 0 else home.dp(1); rightMargin = if (contR) 0 else home.dp(1)
                })
                col = sg.to + 1
            }
            if (col < 7) row.addView(View(home), LinearLayout.LayoutParams(0, 1, (7 - col).toFloat()))
            bars.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = home.dp(2) })
        }
        if (hidden.any { it > 0 }) {
            val more = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 7) more.addView(home.txt(if (hidden[c] > 0) "+${hidden[c]}" else "", 11f, C.dim, gravity = Gravity.CENTER),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            bars.addView(more)
        }
        // 막대는 누르기를 가로채지 않음 → 아래 날짜 칸이 눌림
        frame.addView(bars, FrameLayout.LayoutParams(MATCH, MATCH))
        return frame
    }

    private fun dayCell(date: LocalDate, dow: Int, today: LocalDate): View {
        val cell = LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(home.dp(1), home.dp(3), home.dp(1), 0)
            if (date == selected) background = rounded(Color.argb(60, 255, 255, 255), home.dpf(8f))
            setOnClickListener {
                selected = date
                refresh()
            }
        }
        val inMonth = YearMonth.from(date) == ym
        val base = when (dow) { 0 -> C.sun; 6 -> C.sat; else -> C.text }
        val num = home.txt(date.dayOfMonth.toString(), 15f, if (inMonth) base else Color.argb(90, 255, 255, 255), gravity = Gravity.CENTER)
        if (date == today) {
            num.setTextColor(Color.WHITE)
            num.background = rounded(C.err, home.dpf(13f))
        }
        val numWrap = FrameLayout(home)
        numWrap.addView(num, FrameLayout.LayoutParams(home.dp(26), home.dp(26), Gravity.CENTER_HORIZONTAL))
        cell.addView(numWrap)
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
            CalendarContract.Instances.DISPLAY_COLOR, CalendarContract.Instances.RRULE,
            CalendarContract.Instances.ORIGINAL_ID, CalendarContract.Instances.CALENDAR_DISPLAY_NAME
        )
        val out = mutableListOf<Ev>()
        try {
            // 캘린더 앱에서 '숨긴' 캘린더(VISIBLE=0)는 쿠비스 달력에도 안 보이게
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(begin.toString()).appendPath(end.toString()).build()
            home.contentResolver.query(uri, proj, "${CalendarContract.Instances.VISIBLE}=1", null, null)?.use { c ->
                while (c.moveToNext()) {
                    val color = if (c.isNull(6)) C.chip else (c.getInt(6) or 0xFF000000.toInt())
                    val cal = c.getString(9) ?: ""
                    val holiday = cal.contains("휴일") || cal.contains("공휴일") || cal.contains("Holiday", true)
                    val rep = !c.getString(7).isNullOrBlank() || !c.isNull(8)
                    out.add(Ev(c.getLong(0), c.getString(1) ?: "(제목 없음)", c.getLong(2), c.getLong(3),
                        c.getInt(4) == 1, c.getString(5)?.takeIf { it.isNotBlank() }, color, holiday, rep))
                }
            }
        } catch (_: Exception) {}
        // 삼성 '공휴일'과 구글 '대한민국의 휴일'처럼 같은 날 같은 이름은 하나만
        val hiddenTitles = hiddenTitles()
        val uniq = out.filter { it.title.replace(" ", "") !in hiddenTitles }.distinctBy { Triple(it.title.replace(" ", ""), it.startDate, if (it.allDay) -1L else it.begin) }
        // 색은 알아서: 공휴일은 차분한 녹색, 반복 일정은 파랑, 한 번뿐인 일정은 갈색.
        // 반복 규칙이 없어도(옮겨 온 일정 등) 같은 이름이 6주 안에 3번 넘게 나오면 반복으로 봄
        val count = uniq.filter { !it.holiday }.groupingBy { it.title.trim() }.eachCount()
        return uniq.map {
            when {
                it.holiday -> it.copy(color = HOLIDAY_COLOR)
                it.recurring || (count[it.title.trim()] ?: 0) >= 3 -> it.copy(color = REPEAT_COLOR, recurring = true)
                else -> it.copy(color = ONCE_COLOR)
            }
        }
    }

    /** 쿠비스 달력에서만 숨긴 일정 이름 (휴대폰 캘린더에는 그대로 있음) */
    private fun hiddenTitles(): Set<String> = Prefs(home).p.getStringSet("calHidden", emptySet()) ?: emptySet()
    private fun setHiddenTitles(s: Set<String>) = Prefs(home).p.edit().putStringSet("calHidden", HashSet(s)).apply()

    private fun askHide(e: Ev) {
        AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("'${e.title}' 숨기기")
            .setMessage("쿠비스 달력에서 이 이름의 일정을 모두 안 보이게 해요. 휴대폰 캘린더에서는 지워지지 않아요.\n다시 보이게 하려면 달력 위 '년 월' 글씨를 꾹 누르세요.")
            .setPositiveButton("숨기기") { _, _ -> setHiddenTitles(hiddenTitles() + e.title.replace(" ", "")); refresh() }
            .setNegativeButton("취소", null).show()
    }

    private fun showHidden() {
        val list = hiddenTitles().sorted()
        if (list.isEmpty()) { android.widget.Toast.makeText(home, "숨긴 일정이 없어요", android.widget.Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(home, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("숨긴 일정 — 누르면 다시 보여요")
            .setItems(list.toTypedArray()) { _, i -> setHiddenTitles(hiddenTitles() - list[i]); refresh() }
            .setNegativeButton("닫기", null).show()
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
