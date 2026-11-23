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
 * 쿠비스 대화 창 — Claude 앱 음성 대화 화면과 같은 모양.
 * 아래에서 올라오는 어두운 시트, 가운데 별꽃 마크와 지금 하는 말, 큰 마이크 버튼,
 * 아래 왼쪽 모델 고르기, 오른쪽 소리 켜기/끄기와 끝내기(X).
 * 대화 글이 쌓이는 목록은 없고, 지금 듣거나 말하는 것만 보인다.
 */
class KuvisPanel(ctx: Context) : LinearLayout(ctx) {

    companion object {
        val ORANGE = Color.parseColor("#D97757")
        val ORANGE_DIM = Color.parseColor("#6E3F33")
        private val GREY = Color.parseColor("#9A9DA3")
        private val BTN = Color.parseColor("#26272B")
        private val BTN_LINE = Color.parseColor("#33353A")
        private const val GREETING = "T, 이야기해요"
        private const val MATCH = -1  // MATCH_PARENT
        private const val WRAP = -2   // WRAP_CONTENT
    }

    val handle = View(ctx)
    val expandBtn = IconView(ctx, IconView.EXPAND)
    val setBtn = IconView(ctx, IconView.GEAR)
    val spark = SparkView(ctx)
    private val caption = TextView(ctx)
    val micBtn = IconView(ctx, IconView.MIC)
    val modelBtn = LinearLayout(ctx)
    private val modelLabel = TextView(ctx)
    val speakerBtn = IconView(ctx, IconView.SPEAKER)
    val closeBtn = IconView(ctx, IconView.CLOSE)

    private var pulse: ValueAnimator? = null
    private var spin: ValueAnimator? = null
    private var state = "idle"
    private var replyShown = false

    var onSend: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        isClickable = true // 바깥 닫기와 구분
        val r = ctx.dpf(28f)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            Color.parseColor("#151515"), Color.parseColor("#151515"),
            Color.parseColor("#1B1F27"), Color.parseColor("#3C4A5E"), Color.parseColor("#55657C")
        )).apply { cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }

        // 맨 위: 손잡이 + 크게 보기
        val top = FrameLayout(ctx)
        handle.background = rounded(Color.parseColor("#5C5D61"), ctx.dpf(3f))
        top.addView(handle, FrameLayout.LayoutParams(ctx.dp(44), ctx.dp(5), Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            .apply { topMargin = ctx.dp(14) })
        expandBtn.contentDescription = "크게 보기"
        top.addView(expandBtn, FrameLayout.LayoutParams(ctx.dp(40), ctx.dp(40), Gravity.END or Gravity.TOP)
            .apply { topMargin = ctx.dp(10); rightMargin = ctx.dp(14) })
        addView(top, LayoutParams(MATCH, ctx.dp(60)))

        // 설정(톱니바퀴) — 오른쪽
        val gearRow = FrameLayout(ctx)
        setBtn.contentDescription = "쿠비스 설정"
        setBtn.background = circle(BTN, BTN_LINE)
        setBtn.pad = 0.30f
        gearRow.addView(setBtn, FrameLayout.LayoutParams(ctx.dp(60), ctx.dp(60), Gravity.END).apply { rightMargin = ctx.dp(16) })
        addView(gearRow, LayoutParams(MATCH, WRAP))

        addView(View(ctx), LayoutParams(MATCH, 0, 1f))

        // 가운데: 별꽃 마크 + 지금 하는 말
        addView(spark, LayoutParams(ctx.dp(66), ctx.dp(66)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        caption.apply {
            gravity = Gravity.CENTER
            setTextColor(GREY)
            setLineSpacing(0f, 1.15f)
            setPadding(ctx.dp(28), ctx.dp(12), ctx.dp(28), ctx.dp(4))
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.START
        }
        addView(caption, LayoutParams(MATCH, WRAP))

        // 마이크 (누르면 듣기/멈추기, 길게 누르면 글로 입력)
        micBtn.contentDescription = "말하기"
        micBtn.background = circle(Color.parseColor("#2A2B30"), BTN_LINE)
        micBtn.pad = 0.30f
        micBtn.elevation = ctx.dpf(6f)
        micBtn.setOnLongClickListener { typeDialog(); true }
        addView(micBtn, LayoutParams(ctx.dp(86), ctx.dp(86)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; topMargin = ctx.dp(14)
        })

        // 맨 아래 줄
        val bottom = FrameLayout(ctx)
        modelBtn.apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(BTN, ctx.dpf(30f), ctx.dp(1), BTN_LINE)
            setPadding(ctx.dp(22), 0, ctx.dp(16), 0)
            contentDescription = "모델 고르기"
        }
        modelLabel.apply { textSize = 17f; setTextColor(Color.parseColor("#E8E8EA")) }
        modelBtn.addView(modelLabel)
        modelBtn.addView(IconView(ctx, IconView.CHEVRONS).apply { pad = 0.18f },
            LayoutParams(ctx.dp(26), ctx.dp(26)).apply { leftMargin = ctx.dp(8) })
        bottom.addView(modelBtn, FrameLayout.LayoutParams(WRAP, ctx.dp(58), Gravity.START or Gravity.CENTER_VERTICAL)
            .apply { leftMargin = ctx.dp(18) })

        val right = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        speakerBtn.background = circle(BTN, BTN_LINE)
        speakerBtn.pad = 0.29f
        speakerBtn.contentDescription = "소리 켜기/끄기"
        right.addView(speakerBtn, LayoutParams(ctx.dp(58), ctx.dp(58)))
        closeBtn.background = circle(Color.WHITE, Color.WHITE)
        closeBtn.color = Color.parseColor("#111111")
        closeBtn.pad = 0.32f
        closeBtn.contentDescription = "끝내기"
        right.addView(closeBtn, LayoutParams(ctx.dp(58), ctx.dp(58)).apply { leftMargin = ctx.dp(12) })
        bottom.addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL)
            .apply { rightMargin = ctx.dp(18) })
        addView(bottom, LayoutParams(MATCH, ctx.dp(70)).apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(14) })

        greet()
        setState("idle", null)
    }

    private fun circle(fill: Int, line: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(fill); setStroke(context.dp(1), line)
    }

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
    private fun captionStyle(kind: String) {
        when (kind) {
            "greet" -> { caption.textSize = 20f; caption.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL); caption.setTextColor(GREY) }
            "interim" -> { caption.textSize = 18f; caption.typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC); caption.setTextColor(GREY) }
            "reply" -> { caption.textSize = 16f; caption.typeface = Typeface.DEFAULT; caption.setTextColor(Color.parseColor("#D4D6DA")) }
            "status" -> { caption.textSize = 14f; caption.typeface = Typeface.DEFAULT; caption.setTextColor(GREY) }
            "error" -> { caption.textSize = 14f; caption.typeface = Typeface.DEFAULT; caption.setTextColor(Color.parseColor("#F2A39A")) }
        }
    }

    /** 처음 열렸을 때: "T, 이야기해요" */
    fun greet() { replyShown = false; captionStyle("greet"); caption.text = GREETING }

    fun setModel(label: String) { modelLabel.text = label }

    fun setSpeaker(on: Boolean) { speakerBtn.icon = if (on) IconView.SPEAKER else IconView.SPEAKER_OFF }

    fun setExpanded(on: Boolean) { expandBtn.icon = if (on) IconView.COLLAPSE else IconView.EXPAND }

    fun setState(s: String, msg: String?) {
        state = s
        pulse?.cancel(); pulse = null
        spin?.cancel(); spin = null
        spark.scaleX = 1f; spark.scaleY = 1f
        spark.color = if (s == "listening") ORANGE_DIM else ORANGE
        when (s) {
            "thinking" -> {
                spin = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 2400L; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
                    addUpdateListener { a -> spark.rotation = a.animatedValue as Float }
                    start()
                }
                if (msg != null) { captionStyle("status"); caption.text = msg; replyShown = false }
            }
            "speaking" -> {
                pulse = ValueAnimator.ofFloat(1f, 1.12f).apply {
                    duration = 380L; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { a -> val v = a.animatedValue as Float; spark.scaleX = v; spark.scaleY = v }
                    start()
                }
            }
            "listening" -> { if (msg != null) { captionStyle("status"); caption.text = msg } }
            else -> {
                if (msg != null) { captionStyle("status"); caption.text = msg }
                else if (!replyShown) greet()
            }
        }
    }

    fun setLevel(rms: Float) {
        val v = (rms.coerceIn(0f, 10f) / 10f)
        spark.color = blend(ORANGE_DIM, ORANGE, v)
        val s = 1f + v * 0.18f
        spark.scaleX = s; spark.scaleY = s
    }

    /** 듣는 중 들리는 말 (기울임 회색) */
    fun setInterim(t: String) {
        if (t.isBlank()) return
        replyShown = false
        captionStyle("interim"); caption.text = t
    }

    /** 사용자가 한 말 (다 들은 뒤) */
    fun showUser(t: String) { replyShown = false; captionStyle("interim"); caption.text = t }

    /** 쿠비스가 하는 말 (스트리밍으로 늘어남) */
    fun showReply(t: String) {
        replyShown = true
        captionStyle("reply")
        caption.text = if (t.length > 220) "…" + t.takeLast(220) else t
    }

    fun showError(t: String) { replyShown = true; captionStyle("error"); caption.text = t }

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
