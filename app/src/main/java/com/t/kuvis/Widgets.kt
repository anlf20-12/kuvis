package com.t.kuvis

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Intent
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

/** 시계 글꼴 (빌드 때 받아 둔 무료 한글 글꼴, 없으면 기본 글꼴) */
object ClockFonts {
    private val cache = HashMap<String, android.graphics.Typeface?>()
    fun get(ctx: android.content.Context, file: String, fallback: android.graphics.Typeface): android.graphics.Typeface =
        cache.getOrPut(file) { try { android.graphics.Typeface.createFromAsset(ctx.assets, "fonts/$file") } catch (_: Exception) { null } } ?: fallback
}

/**
 * 시계 위젯. 모양 6가지 (꾹 눌러 '시계 모양 바꾸기'로 고름). 누르면 알람 앱.
 * 0 기본 · 1 큰 숫자 두 줄 · 2 신문 명조 · 3 한글로 읽는 시계 · 4 굵은 한 줄 · 5 바늘 시계
 */
class ClockWidget(private val home: HomeActivity, private val fixedStyle: Int = -1) : FrameLayout(home) {

    companion object {
        val NAMES = listOf("기본", "큰 숫자 두 줄", "신문 명조", "한글로 읽는 시계", "굵은 한 줄", "바늘 시계")
        /** 날씨 한 줄 (한글 시계 모양에서 씀) */
        var weatherHint: String = ""
        private val HOURS = listOf("열두", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열", "열한")
        private val NUM = listOf("", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구")
        fun koreanMinute(m: Int): String {
            if (m == 0) return "정각"
            val t = m / 10; val o = m % 10
            val tens = when (t) { 0 -> ""; 1 -> "십"; else -> NUM[t] + "십" }
            return tens + NUM[o] + " 분"
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var style = -1
    private var analog: AnalogFace? = null

    private val cardColor = Color.argb(150, 28, 28, 32)
    private val cText = Color.parseColor("#F1EFE8")
    private val cSub = Color.parseColor("#B4B2A9")
    private val cFaint = Color.parseColor("#888780")
    private val cWarm = Color.parseColor("#FAC775")
    private val cCoral = Color.parseColor("#F0997B")

    init {
        background = rounded(cardColor, home.dpf(22f))
        setOnClickListener {
            if (fixedStyle < 0) home.safeStart(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        refresh()
    }

    fun currentStyle() = if (fixedStyle >= 0) fixedStyle else Prefs(home).p.getInt("clockStyle", 0)

    fun setStyle(st: Int) { Prefs(home).p.edit().putInt("clockStyle", st).apply(); refresh() }

    fun refresh() {
        val st = currentStyle()
        if (st == style && childCount > 0) { tick(); return }
        style = st
        removeAllViews()
        ticker?.let { main.removeCallbacks(it) }; ticker = null
        when (st) {
            1 -> buildStacked()
            2 -> buildNewspaper()
            3 -> buildKorean()
            4 -> buildBoldLine()
            5 -> buildAnalog()
            else -> buildBasic()
        }
    }

    private fun tc(fmt12: String, fmt24: String = fmt12) = TextClock(home).apply {
        format12Hour = fmt12; format24Hour = fmt24; textLocale = Locale.KOREAN
        maxLines = 1; includeFontPadding = false
    }

    private fun auto(v: android.widget.TextView, lo: Int, hi: Int) =
        v.setAutoSizeTextTypeUniformWithConfiguration(lo, hi, 1, android.util.TypedValue.COMPLEX_UNIT_SP)

    private fun pad(h: Int, v: Int) = setPadding(home.dp(h), home.dp(v), home.dp(h), home.dp(v))

    /** 0 기본: 오후 + 시간 + 날짜를 가운데로 */
    private fun buildBasic() {
        pad(18, 10)
        val col = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(tc("a", "").apply { setTextColor(cSub); textSize = 14f; setPadding(0, 0, home.dp(6), home.dp(6)) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.BOTTOM })
        row.addView(tc("h:mm", "HH:mm").apply { setTextColor(cText); textSize = 40f; letterSpacing = 0.03f })
        col.addView(row)
        col.addView(tc("M월 d일 EEEE").apply { setTextColor(cSub); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, home.dp(4), 0, 0) })
        addView(col, LayoutParams(MATCH, MATCH))
    }

    /** 1 큰 숫자 두 줄 */
    private fun buildStacked() {
        pad(22, 8)
        val bh = ClockFonts.get(home, "BlackHanSans-Regular.ttf", android.graphics.Typeface.DEFAULT_BOLD)
        val dh = ClockFonts.get(home, "DoHyeon-Regular.ttf", android.graphics.Typeface.DEFAULT)
        val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val nums = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        nums.addView(tc("hh", "HH").apply { typeface = bh; setTextColor(cText); auto(this, 16, 56); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(WRAP, 0, 1f))
        nums.addView(tc("mm").apply { typeface = bh; setTextColor(cText); auto(this, 16, 56); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(WRAP, 0, 1f))
        row.addView(nums, LinearLayout.LayoutParams(WRAP, MATCH))
        val side = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(home.dp(16), 0, 0, 0) }
        side.addView(tc("a", "").apply { typeface = dh; setTextColor(cSub); textSize = 17f })
        side.addView(tc("M.d").apply { typeface = dh; setTextColor(cSub); textSize = 17f })
        side.addView(tc("EEEE").apply { typeface = dh; setTextColor(cCoral); textSize = 17f })
        row.addView(side, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(row, LayoutParams(MATCH, MATCH))
    }

    /** 2 신문 명조 */
    private fun buildNewspaper() {
        pad(22, 10)
        val gb = ClockFonts.get(home, "GowunBatang-Bold.ttf", android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD))
        val gr = ClockFonts.get(home, "GowunBatang-Bold.ttf", android.graphics.Typeface.SERIF)
        val col = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val top = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(tc("yyyy년 M월 d일").apply { typeface = gr; setTextColor(cSub); textSize = 12f }, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(tc("EEEE").apply { typeface = gr; setTextColor(cSub); textSize = 12f })
        col.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(android.view.View(home).apply { setBackgroundColor(cFaint) }, LinearLayout.LayoutParams(MATCH, home.dp(1)).apply { topMargin = home.dp(4) })
        val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(tc("h:mm", "HH:mm").apply { typeface = gb; setTextColor(cText); auto(this, 16, 42); letterSpacing = 0.06f; gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(WRAP, MATCH))
        row.addView(tc("a", "").apply { typeface = gr; setTextColor(cSub); textSize = 14f; setPadding(home.dp(6), 0, 0, home.dp(6)) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { gravity = Gravity.BOTTOM })
        col.addView(row, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(col, LayoutParams(MATCH, MATCH))
    }

    /** 3 한글로 읽는 시계 */
    private lateinit var korLine1: android.widget.TextView
    private lateinit var korLine2: android.widget.TextView
    private lateinit var korLine3: android.widget.TextView
    private fun buildKorean() {
        pad(22, 8)
        val dh = ClockFonts.get(home, "DoHyeon-Regular.ttf", android.graphics.Typeface.DEFAULT)
        val col = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        korLine1 = home.txt("", 22f, cText).apply { typeface = dh; maxLines = 1; auto(this, 12, 24) }
        korLine2 = home.txt("", 26f, cWarm).apply { typeface = dh; maxLines = 1; auto(this, 12, 28) }
        korLine3 = home.txt("", 13f, cFaint).apply { typeface = dh; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        col.addView(korLine1, LinearLayout.LayoutParams(MATCH, 0, 1f))
        col.addView(korLine2, LinearLayout.LayoutParams(MATCH, 0, 1.2f))
        col.addView(korLine3, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(col, LayoutParams(MATCH, MATCH))
        startTicker(20_000)
    }

    /** 4 굵은 한 줄 */
    private fun buildBoldLine() {
        pad(18, 6)
        val dh = ClockFonts.get(home, "DoHyeon-Regular.ttf", android.graphics.Typeface.DEFAULT_BOLD)
        val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(tc("h:mm", "HH:mm").apply { typeface = dh; setTextColor(cText); auto(this, 16, 60); gravity = Gravity.CENTER_VERTICAL or Gravity.END },
            LinearLayout.LayoutParams(0, MATCH, 1.4f))
        val side = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(home.dp(12), 0, 0, 0) }
        side.addView(tc("a", "").apply { typeface = dh; setTextColor(cWarm); textSize = 16f })
        side.addView(tc("M월 d일 E").apply { typeface = dh; setTextColor(cSub); textSize = 14f })
        row.addView(side, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(row, LayoutParams(MATCH, MATCH))
    }

    /** 5 바늘 시계 + 날짜 */
    private fun buildAnalog() {
        pad(16, 8)
        val row = LinearLayout(home).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val face = AnalogFace(home, cText, cSub, cWarm)
        analog = face
        row.addView(face, LinearLayout.LayoutParams(0, MATCH, 1f))
        val side = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(home.dp(10), 0, 0, 0) }
        side.addView(tc("EEEE").apply { setTextColor(cText); textSize = 18f })
        side.addView(tc("M월 d일").apply { setTextColor(cSub); textSize = 14f })
        side.addView(tc("a h:mm", "HH:mm").apply { setTextColor(cFaint); textSize = 13f })
        row.addView(side, LinearLayout.LayoutParams(0, WRAP, 1.2f))
        addView(row, LayoutParams(MATCH, MATCH))
        startTicker(1_000)
    }

    private fun startTicker(ms: Long) {
        val r = object : Runnable { override fun run() { tick(); main.postDelayed(this, ms) } }
        ticker = r
        if (isAttachedToWindow) main.post(r)
    }

    private fun tick() {
        val now = java.time.LocalDateTime.now()
        when (style) {
            3 -> if (::korLine1.isInitialized) {
                val h = now.hour
                korLine1.text = android.text.SpannableString("지금은 ${if (h < 12) "오전" else "오후"}").apply {
                    setSpan(android.text.style.ForegroundColorSpan(cFaint), 0, 3, 0)
                }
                korLine2.text = "${HOURS[h % 12]} 시 ${koreanMinute(now.minute)}"
                val d = now.format(DateTimeFormatter.ofPattern("M월 d일 EEEE", Locale.KOREAN))
                korLine3.text = if (weatherHint.isNotBlank()) "$d · $weatherHint" else d
            }
            5 -> analog?.invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ticker?.let { main.removeCallbacks(it); main.post(it) }
    }

    override fun onDetachedFromWindow() {
        ticker?.let { main.removeCallbacks(it) }
        super.onDetachedFromWindow()
    }
}

/** 바늘 시계 그림 */
class AnalogFace(ctx: android.content.Context, private val fg: Int, private val dim: Int, private val accent: Int) : android.view.View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(width, height) / 2f * 0.9f
        if (r <= 0) return
        val now = java.time.LocalTime.now()
        p.style = Paint.Style.STROKE; p.color = dim; p.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r, p)
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val r1 = if (i % 3 == 0) r * 0.78f else r * 0.86f
            p.strokeWidth = if (i % 3 == 0) r * 0.05f else r * 0.025f
            p.color = if (i % 3 == 0) fg else dim
            c.drawLine(cx + (r1 * Math.sin(a)).toFloat(), cy - (r1 * Math.cos(a)).toFloat(),
                cx + (r * 0.93f * Math.sin(a)).toFloat(), cy - (r * 0.93f * Math.cos(a)).toFloat(), p)
        }
        fun hand(deg: Double, len: Float, w: Float, col: Int) {
            val a = Math.toRadians(deg)
            p.color = col; p.strokeWidth = w
            c.drawLine(cx, cy, cx + (len * Math.sin(a)).toFloat(), cy - (len * Math.cos(a)).toFloat(), p)
        }
        hand((now.hour % 12) * 30.0 + now.minute * 0.5, r * 0.5f, r * 0.08f, fg)
        hand(now.minute * 6.0 + now.second * 0.1, r * 0.75f, r * 0.05f, fg)
        hand(now.second * 6.0, r * 0.8f, r * 0.02f, accent)
        p.style = Paint.Style.FILL; p.color = accent
        c.drawCircle(cx, cy, r * 0.05f, p)
    }
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

