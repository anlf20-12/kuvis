package com.t.kuvis

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 쿠비스 대화 창 — T가 녹화해 준 Claude 앱 음성 대화 화면을 보고 맞춤 (2026-11-25).
 * 화면 대부분을 덮는 어두운 시트(위쪽에 홈이 조금 보임), 손잡이, 대화가 위에서부터 쌓이는 글 목록.
 *  - T가 한 말: 오른쪽 회색 말풍선
 *  - 쿠비스 답: 왼쪽 큰 글씨. 지금 읽는 문장만 하얗고 굵게, 나머지는 흐리게 (노래방처럼)
 *  - 도구 쓴 것: 작은 회색 줄 (예: "▢ 일정 넣기 ›")
 * 아래 가운데 작은 듣기 버튼, 왼쪽 아래 모델(Haiku ⌄), 오른쪽 아래 스피커와 하얀 끝내기 버튼.
 */
class KuvisPanel(ctx: Context) : LinearLayout(ctx) {

    companion object {
        val ORANGE = Color.parseColor("#D97757")
        val ORANGE_DIM = Color.parseColor("#6E3F33")
        private val BG = Color.parseColor("#262624")
        private val GREY = Color.parseColor("#8E8E93")
        private val DIM = Color.parseColor("#7C7C80")
        private val BRIGHT = Color.parseColor("#F2F2F2")
        private val DONE = Color.parseColor("#E3E3E5")
        private val BUBBLE = Color.parseColor("#3A3A3C")
        private val BTN = Color.parseColor("#333335")
        private const val GREETING = "T, 이야기해요"
        private const val MATCH = -1  // MATCH_PARENT
        private const val WRAP = -2   // WRAP_CONTENT
    }

    val handle = View(ctx)
    val expandBtn = IconView(ctx, IconView.EXPAND)
    val setBtn = IconView(ctx, IconView.GEAR)
    val spark = SparkView(ctx)
    val micBtn = IconView(ctx, IconView.MIC)
    val modelBtn = LinearLayout(ctx)
    private val modelLabel = TextView(ctx)
    val speakerBtn = IconView(ctx, IconView.SPEAKER)
    val closeBtn = IconView(ctx, IconView.CLOSE)

    private val scroll = android.widget.ScrollView(ctx)
    private val list = LinearLayout(ctx)
    private val empty = LinearLayout(ctx)
    private val emptyText = TextView(ctx)
    private val statusText = TextView(ctx)
    private val thinkSpark = SparkView(ctx)

    private var liveUser: TextView? = null
    private var reply: TextView? = null
    private var replyText = ""
    private var hiStart = -1
    private var hiEnd = -1

    private var pulse: ValueAnimator? = null
    private var spin: ValueAnimator? = null
    private var state = "idle"

    var onSend: ((String) -> Unit)? = null

    // init 보다 위에 있어야 함 (init의 greet()가 씀)
    private val statusRow: LinearLayout by lazy {
        LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, context.dp(10), 0, 0)
            addView(thinkSpark, LayoutParams(context.dp(18), context.dp(18)))
            addView(statusText, LayoutParams(WRAP, WRAP).apply { leftMargin = context.dp(8) })
        }
    }

    init {
        orientation = VERTICAL
        isClickable = true // 바깥 닫기와 구분
        val r = ctx.dpf(26f)
        background = GradientDrawable().apply { setColor(BG); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }

        // 맨 위: 설정(작게) · 손잡이 · 크게 보기
        val top = FrameLayout(ctx)
        handle.background = rounded(Color.parseColor("#5C5D61"), ctx.dpf(3f))
        top.addView(handle, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(5), Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            .apply { topMargin = ctx.dp(12) })
        setBtn.contentDescription = "쿠비스 설정"
        setBtn.color = GREY; setBtn.pad = 0.26f
        top.addView(setBtn, FrameLayout.LayoutParams(ctx.dp(38), ctx.dp(38), Gravity.START or Gravity.TOP)
            .apply { topMargin = ctx.dp(8); leftMargin = ctx.dp(12) })
        expandBtn.contentDescription = "크게 보기"
        expandBtn.color = GREY; expandBtn.pad = 0.30f
        top.addView(expandBtn, FrameLayout.LayoutParams(ctx.dp(38), ctx.dp(38), Gravity.END or Gravity.TOP)
            .apply { topMargin = ctx.dp(8); rightMargin = ctx.dp(12) })
        addView(top, LayoutParams(MATCH, ctx.dp(48)))

        // 가운데: 대화 글 목록 (처음엔 별꽃 + "T, 이야기해요")
        val mid = FrameLayout(ctx)
        list.orientation = VERTICAL
        list.setPadding(ctx.dp(22), ctx.dp(6), ctx.dp(22), ctx.dp(16))
        scroll.addView(list, FrameLayout.LayoutParams(MATCH, WRAP))
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER
        mid.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
        empty.orientation = VERTICAL; empty.gravity = Gravity.CENTER
        empty.addView(spark, LayoutParams(ctx.dp(40), ctx.dp(40)))
        emptyText.apply {
            text = GREETING; textSize = 15f; setTextColor(GREY); gravity = Gravity.CENTER
            setPadding(0, ctx.dp(10), 0, 0)
        }
        empty.addView(emptyText, LayoutParams(WRAP, WRAP))
        mid.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        addView(mid, LayoutParams(MATCH, 0, 1f))

        // 생각 중 표시 (목록 맨 끝에 붙는 작은 별꽃 + 글)
        thinkSpark.color = ORANGE
        statusText.apply { textSize = 13f; setTextColor(GREY) }

        // 아래 가운데: 듣기 버튼 (누르면 듣기/멈추기, 길게 누르면 글로 입력)
        micBtn.contentDescription = "말하기"
        micBtn.background = circle(BTN)
        micBtn.pad = 0.30f
        micBtn.setOnLongClickListener { typeDialog(); true }
        addView(micBtn, LayoutParams(ctx.dp(54), ctx.dp(54)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(4) })

        // 맨 아래 줄: 모델 · 스피커 · 끝내기
        val bottom = FrameLayout(ctx)
        modelBtn.apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(BTN, ctx.dpf(20f))
            setPadding(ctx.dp(14), 0, ctx.dp(8), 0)
            contentDescription = "모델 고르기"
        }
        modelLabel.apply { textSize = 14f; setTextColor(Color.parseColor("#E8E8EA")) }
        modelBtn.addView(modelLabel)
        modelBtn.addView(IconView(ctx, IconView.CHEVRONS).apply { pad = 0.24f; color = GREY },
            LayoutParams(ctx.dp(20), ctx.dp(20)).apply { leftMargin = ctx.dp(4) })
        bottom.addView(modelBtn, FrameLayout.LayoutParams(WRAP, ctx.dp(38), Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { leftMargin = ctx.dp(18) })

        val right = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speakerBtn.pad = 0.28f
        speakerBtn.color = Color.parseColor("#C7C7CC")
        speakerBtn.contentDescription = "소리 켜기/끄기"
        right.addView(speakerBtn, LayoutParams(ctx.dp(44), ctx.dp(44)))
        closeBtn.background = circle(Color.WHITE)
        closeBtn.color = Color.parseColor("#1C1C1E")
        closeBtn.pad = 0.33f
        closeBtn.contentDescription = "끝내기"
        right.addView(closeBtn, LayoutParams(ctx.dp(46), ctx.dp(46)).apply { leftMargin = ctx.dp(10) })
        bottom.addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { rightMargin = ctx.dp(16) })
        addView(bottom, LayoutParams(MATCH, ctx.dp(60)).apply { topMargin = ctx.dp(6); bottomMargin = ctx.dp(10) })

        greet()
        setState("idle", null)
    }

    private fun circle(fill: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }

    private fun typeDialog() {
        val et = EditText(context).apply {
            hint = "쿠비스에게 글로 말하기"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val wrap = FrameLayout(context).apply { setPadding(context.dp(20), context.dp(8), context.dp(20), 0); addView(et) }
        AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(wrap)
            .setPositiveButton("보내기") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let { onSend?.invoke(it) } }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---------------------------------------------------------------
    private fun hasItems() = list.childCount > 0 && (list.childCount > 1 || list.getChildAt(0) != statusRow)
    private fun refreshEmpty() { empty.visibility = if (hasItems()) View.GONE else View.VISIBLE }
    private fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }

    private fun showStatus(msg: String?) {
        (statusRow.parent as? ViewGroup)?.removeView(statusRow)
        statusText.text = msg ?: ""
        list.addView(statusRow)
        refreshEmpty(); scrollDown()
    }

    private fun hideStatus() { (statusRow.parent as? ViewGroup)?.removeView(statusRow); refreshEmpty() }

    private fun addItem(v: View, lp: LayoutParams) {
        val hadStatus = statusRow.parent != null
        if (hadStatus) list.removeView(statusRow)
        list.addView(v, lp)
        if (hadStatus) list.addView(statusRow)
        refreshEmpty(); scrollDown()
    }

    /** 처음 열렸을 때 / 새 대화: 목록 비우고 "T, 이야기해요" */
    fun greet() {
        if (!hasItems()) { emptyText.text = GREETING; refreshEmpty() }
    }

    fun clear() {
        list.removeAllViews(); liveUser = null; reply = null; replyText = ""
        emptyText.text = GREETING; refreshEmpty()
    }

    fun setModel(label: String) { modelLabel.text = label }

    fun setSpeaker(on: Boolean) { speakerBtn.icon = if (on) IconView.SPEAKER else IconView.SPEAKER_OFF }

    fun setExpanded(on: Boolean) { expandBtn.icon = if (on) IconView.COLLAPSE else IconView.EXPAND }

    fun setState(s: String, msg: String?) {
        state = s
        pulse?.cancel(); pulse = null
        spin?.cancel(); spin = null
        micBtn.scaleX = 1f; micBtn.scaleY = 1f
        spark.scaleX = 1f; spark.scaleY = 1f
        spark.color = if (s == "listening") ORANGE_DIM else ORANGE
        micBtn.icon = IconView.MIC
        micBtn.background = circle(if (s == "listening") Color.parseColor("#3E3E41") else BTN)
        when (s) {
            "thinking" -> {
                showStatus(msg ?: "")
                spin = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 2000L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                    addUpdateListener { a -> val v = a.animatedValue as Float; thinkSpark.rotation = v; spark.rotation = v }
                    start()
                }
            }
            "speaking" -> {
                hideStatus()
                pulse = ValueAnimator.ofFloat(1f, 1.08f).apply {
                    duration = 420L; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { a -> val v = a.animatedValue as Float; micBtn.scaleX = v; micBtn.scaleY = v }
                    start()
                }
            }
            "listening" -> { hideStatus(); if (msg != null) emptyText.text = msg }
            else -> {
                hideStatus()
                if (msg != null) { if (hasItems()) showError(msg) else emptyText.text = msg }
            }
        }
    }

    /** 듣는 중 목소리 크기에 따라 듣기 버튼이 살짝 커짐 */
    fun setLevel(rms: Float) {
        val v = (rms.coerceIn(0f, 10f) / 10f)
        val s = 1f + v * 0.16f
        micBtn.scaleX = s; micBtn.scaleY = s
        spark.color = blend(ORANGE_DIM, ORANGE, v)
    }

    private fun userBubble(): TextView = TextView(context).apply {
        textSize = 16f; setTextColor(BRIGHT); setLineSpacing(0f, 1.2f)
        background = rounded(BUBBLE, context.dpf(18f))
        setPadding(context.dp(14), context.dp(9), context.dp(14), context.dp(9))
    }

    private fun bubbleParams() = LayoutParams(WRAP, WRAP).apply { gravity = Gravity.END; topMargin = context.dp(16) }

    /** 듣는 중 들리는 말 — 오른쪽 말풍선에 흐리게 */
    fun setInterim(t: String) {
        if (t.isBlank()) return
        val b = liveUser ?: userBubble().also { liveUser = it; addItem(it, bubbleParams()); it.maxWidth = (width * 0.82f).toInt().coerceAtLeast(context.dp(200)) }
        b.setTextColor(GREY); b.text = t
        scrollDown()
    }

    /** 사용자가 한 말 (다 들은 뒤) */
    fun showUser(t: String) {
        val b = liveUser ?: userBubble().also { addItem(it, bubbleParams()); it.maxWidth = (width * 0.82f).toInt().coerceAtLeast(context.dp(200)) }
        b.setTextColor(BRIGHT); b.text = t
        liveUser = null
        reply = null; replyText = ""
        scrollDown()
    }

    /** 듣던 말이 없던 일이 됨 (쿠비스에게 한 말이 아님 등) */
    fun dropLive() {
        liveUser?.let { list.removeView(it) }; liveUser = null
        reply?.let { if (replyText.isBlank() || !replyText.any { c -> c.isLetterOrDigit() }) list.removeView(it) }
        reply = null; replyText = ""
        refreshEmpty()
    }

    /** 도구 쓴 것 한 줄 (예: 일정 넣기 ›) */
    fun addTool(label: String) {
        val tv = TextView(context).apply {
            text = "▢  ${label.removeSuffix("…").removeSuffix(" 중").trim()}  ›"; textSize = 13f; setTextColor(GREY)
        }
        addItem(tv, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(14) })
        reply = null; replyText = ""
    }

    /** 쿠비스가 하는 말 (스트리밍으로 늘어남). 지금 읽는 문장만 밝게 */
    fun showReply(t: String) {
        val r = reply ?: TextView(context).apply {
            textSize = 18f; setLineSpacing(0f, 1.32f); setTextColor(DIM)
        }.also { reply = it; hiStart = -1; hiEnd = -1; addItem(it, LayoutParams(MATCH, WRAP).apply { topMargin = context.dp(14) }) }
        replyText = t
        paint(r, false)
        scrollDown()
    }

    /** 지금 읽는 문장 위치 (답 글 안에서) */
    fun highlight(start: Int, end: Int) {
        hiStart = start; hiEnd = end
        reply?.let { paint(it, false) }
    }

    /** 다 읽음 — 답 전체를 보통 밝기로 */
    fun replyDone() {
        hiStart = -1; hiEnd = -1
        reply?.let { paint(it, true) }
    }

    private fun paint(tv: TextView, done: Boolean) {
        val sp = android.text.SpannableString(replyText)
        if (done || hiStart < 0) {
            sp.setSpan(android.text.style.ForegroundColorSpan(if (done) DONE else DIM), 0, sp.length, 0)
            if (!done && hiStart < 0 && replyText.isNotEmpty()) {
                // 아직 읽기 전: 받은 글은 흐리게
            }
        } else {
            val s = hiStart.coerceIn(0, sp.length); val e = hiEnd.coerceIn(s, sp.length)
            sp.setSpan(android.text.style.ForegroundColorSpan(DIM), 0, sp.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(BRIGHT), s, e, 0)
            sp.setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, e, 0)
        }
        tv.text = sp
    }

    fun showError(t: String) {
        val tv = TextView(context).apply { text = t; textSize = 14f; setTextColor(Color.parseColor("#F2A39A")) }
        addItem(tv, LayoutParams(WRAP, WRAP).apply { topMargin = context.dp(12) })
    }

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }
}

/** 별꽃 모양 마크 (가는 빛살이 여러 갈래로 퍼진 모양) */
class SparkView(ctx: Context) : View(ctx) {
    var color: Int = KuvisPanel.ORANGE
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // 빛살 길이 (조금씩 달라서 손으로 그린 느낌)
    private val lens = floatArrayOf(1f, .78f, .93f, .7f, .97f, .82f, .9f, .74f, 1f, .8f, .88f, .72f)

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val rad = min(width, height) / 2f * 0.95f
        paint.color = color
        paint.strokeWidth = rad * 0.10f
        val n = lens.size
        for (i in 0 until n) {
            val a = Math.toRadians((i * 360.0 / n) - 90 + (if (i % 2 == 0) 0.0 else 4.0))
            val inner = rad * 0.12f
            val outer = rad * lens[i]
            c.drawLine(cx + (cos(a) * inner).toFloat(), cy + (sin(a) * inner).toFloat(),
                cx + (cos(a) * outer).toFloat(), cy + (sin(a) * outer).toFloat(), paint)
        }
    }
}

/** 선으로 그린 아이콘 (마이크·톱니바퀴·스피커·X·크게 보기) */
class IconView(ctx: Context, icon: Int) : View(ctx) {
    companion object {
        const val MIC = 0; const val GEAR = 1; const val SPEAKER = 2; const val SPEAKER_OFF = 3
        const val CLOSE = 4; const val EXPAND = 5; const val COLLAPSE = 6; const val CHEVRONS = 7
    }
    var icon: Int = icon
        set(v) { field = v; invalidate() }
    var color: Int = Color.parseColor("#E8E8EA")
        set(v) { field = v; invalidate() }
    /** 가장자리 여백 비율 */
    var pad: Float = 0.2f
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    init { isClickable = true }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val ox = (width - s) / 2f + s * pad; val oy = (height - s) / 2f + s * pad
        val w = s * (1 - 2 * pad)
        fun x(f: Float) = ox + w * f
        fun y(f: Float) = oy + w * f
        p.color = color
        p.strokeWidth = maxOf(w * 0.085f, 2f)
        p.style = Paint.Style.STROKE
        when (icon) {
            MIC -> {
                c.drawRoundRect(RectF(x(.34f), y(.02f), x(.66f), y(.62f)), w * .16f, w * .16f, p)
                val path = Path().apply { moveTo(x(.16f), y(.44f)); cubicTo(x(.16f), y(.86f), x(.84f), y(.86f), x(.84f), y(.44f)) }
                c.drawPath(path, p)
                c.drawLine(x(.5f), y(.76f), x(.5f), y(.96f), p)
            }
            GEAR -> {
                c.drawCircle(x(.5f), y(.5f), w * .15f, p)
                c.drawCircle(x(.5f), y(.5f), w * .32f, p)
                val tooth = Paint(p).apply { strokeWidth = p.strokeWidth * 1.9f; strokeCap = Paint.Cap.SQUARE }
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    c.drawLine(x(.5f) + (cos(a) * w * .36f).toFloat(), y(.5f) + (sin(a) * w * .36f).toFloat(),
                        x(.5f) + (cos(a) * w * .44f).toFloat(), y(.5f) + (sin(a) * w * .44f).toFloat(), tooth)
                }
            }
            SPEAKER, SPEAKER_OFF -> {
                val path = Path().apply {
                    moveTo(x(.08f), y(.36f)); lineTo(x(.28f), y(.36f)); lineTo(x(.52f), y(.14f))
                    lineTo(x(.52f), y(.86f)); lineTo(x(.28f), y(.64f)); lineTo(x(.08f), y(.64f)); close()
                }
                c.drawPath(path, p)
                if (icon == SPEAKER) {
                    c.drawArc(RectF(x(.50f), y(.34f), x(.74f), y(.66f)), -55f, 110f, false, p)
                    c.drawArc(RectF(x(.52f), y(.18f), x(.96f), y(.82f)), -55f, 110f, false, p)
                } else {
                    c.drawLine(x(.68f), y(.36f), x(.94f), y(.64f), p)
                    c.drawLine(x(.94f), y(.36f), x(.68f), y(.64f), p)
                }
            }
            CLOSE -> {
                c.drawLine(x(.1f), y(.1f), x(.9f), y(.9f), p)
                c.drawLine(x(.9f), y(.1f), x(.1f), y(.9f), p)
            }
            EXPAND, COLLAPSE -> {
                fun arrow(fx: Float, fy: Float, tx: Float, ty: Float) {
                    c.drawLine(x(fx), y(fy), x(tx), y(ty), p)
                    val dx = if (tx > fx) -1f else 1f; val dy = if (ty > fy) -1f else 1f
                    c.drawLine(x(tx), y(ty), x(tx + dx * .3f), y(ty), p)
                    c.drawLine(x(tx), y(ty), x(tx), y(ty + dy * .3f), p)
                }
                if (icon == EXPAND) { arrow(.58f, .42f, .92f, .08f); arrow(.42f, .58f, .08f, .92f) }
                else { arrow(.92f, .08f, .58f, .42f); arrow(.08f, .92f, .42f, .58f) }
            }
            CHEVRONS -> {
                c.drawLine(x(.2f), y(.38f), x(.5f), y(.12f), p); c.drawLine(x(.5f), y(.12f), x(.8f), y(.38f), p)
                c.drawLine(x(.2f), y(.62f), x(.5f), y(.88f), p); c.drawLine(x(.5f), y(.88f), x(.8f), y(.62f), p)
            }
        }
    }
}
