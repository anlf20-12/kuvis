package com.t.kuvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 쿠비스 대화 창: 대화 기록 + 동그라미 + 상태 + 글 입력 */
class KuvisPanel(ctx: Context) : LinearLayout(ctx) {

    val scroll = ScrollView(ctx)
    private val log = LinearLayout(ctx)
    private val interim = ctx.txt("", 15f, Color.parseColor("#A7F3D0"), gravity = Gravity.CENTER)
    private val orbBg = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        gradientType = GradientDrawable.RADIAL_GRADIENT
        gradientRadius = ctx.dpf(55f)
        setGradientCenter(0.35f, 0.3f)
    }
    val orb = View(ctx)
    private val status = ctx.txt("", 13f, C.dim, gravity = Gravity.CENTER)
    val input = EditText(ctx)
    val newBtn = smallBtn("새 대화")
    val setBtn = smallBtn("설정")
    val loopBtn = smallBtn("")
    private var pulse: ValueAnimator? = null

    var onSend: ((String) -> Unit)? = null

    init {
        orientation = VERTICAL
        background = rounded(Color.argb(240, 5, 8, 15), ctx.dpf(24f), ctx.dp(1), C.line)
        setPadding(ctx.dp(14), ctx.dp(12), ctx.dp(14), ctx.dp(12))
        isClickable = true // 바깥 닫기와 구분

        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ctx.txt("쿠비스", 16f, C.text, bold = true), LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(loopBtn); head.addView(newBtn); head.addView(setBtn)
        addView(head)

        log.orientation = VERTICAL
        scroll.addView(log)
        scroll.isVerticalScrollBarEnabled = false
        addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(230)))

        interim.setPadding(0, ctx.dp(4), 0, ctx.dp(6))
        addView(interim)

        orb.background = orbBg
        orb.contentDescription = "말하기"
        addView(orb, LayoutParams(ctx.dp(92), ctx.dp(92)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        status.setPadding(0, ctx.dp(8), 0, ctx.dp(8))
        addView(status)

        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        input.apply {
            hint = "글로 입력해도 돼요"; setHintTextColor(C.dim); setTextColor(C.text); textSize = 15f
            background = rounded(C.panel, ctx.dpf(12f), ctx.dp(1), C.line)
            setPadding(ctx.dp(12), ctx.dp(9), ctx.dp(12), ctx.dp(9))
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        row.addView(input, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sendBtn = Button(ctx).apply {
            text = "보내기"; setTextColor(Color.parseColor("#04121F")); isAllCaps = false
            background = rounded(C.accent, ctx.dpf(12f))
            setOnClickListener { send() }
        }
        row.addView(sendBtn, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = ctx.dp(8) })
        addView(row)
        setState("idle", null)
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        onSend?.invoke(t)
    }

    private fun smallBtn(label: String) = Button(context).apply {
        text = label; setTextColor(C.text); textSize = 13f; isAllCaps = false
        background = rounded(C.panel, context.dpf(10f), context.dp(1), C.line)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        setPadding(context.dp(10), context.dp(6), context.dp(10), context.dp(6))
        layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = context.dp(6) }
    }

    fun setLoop(on: Boolean) {
        loopBtn.text = if (on) "연속 켜짐" else "연속 꺼짐"
        loopBtn.setTextColor(if (on) C.green else C.text)
    }

    fun setState(s: String, msg: String?) {
        orbBg.colors = when (s) {
            "listening" -> intArrayOf(Color.parseColor("#86EFAC"), Color.parseColor("#15803D"), Color.parseColor("#052E16"))
            "thinking" -> intArrayOf(Color.parseColor("#FCD34D"), Color.parseColor("#B45309"), Color.parseColor("#451A03"))
            else -> intArrayOf(Color.parseColor("#7DD3FC"), Color.parseColor("#0369A1"), Color.parseColor("#082F49"))
        }
        status.text = msg ?: when (s) {
            "listening" -> "듣고 있어요…"
            "thinking" -> "생각 중…"
            "speaking" -> "말하는 중 — 누르면 끊고 바로 들어요"
            else -> "동그라미를 누르고 말씀하세요"
        }
        pulse?.cancel(); pulse = null
        orb.scaleX = 1f; orb.scaleY = 1f
        if (s == "thinking" || s == "speaking") {
            pulse = ValueAnimator.ofFloat(1f, 1.07f).apply {
                duration = if (s == "speaking") 350L else 700L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { a -> val v = a.animatedValue as Float; orb.scaleX = v; orb.scaleY = v }
                start()
            }
        }
    }

    fun setLevel(rms: Float) {
        val s = 1f + (rms.coerceIn(0f, 10f) / 40f)
        orb.scaleX = s; orb.scaleY = s
    }

    fun setInterim(t: String) { interim.text = t }

    fun addMsg(role: String, text: String): TextView {
        val tv = TextView(context).apply {
            this.text = text
            textSize = if (role == "user" || role == "assistant") 15f else 13f
            setLineSpacing(0f, 1.2f)
            setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
            when (role) {
                "user" -> { setTextColor(C.text); background = rounded(C.user, context.dpf(14f)) }
                "assistant" -> { setTextColor(C.text); background = rounded(C.panel, context.dpf(14f), context.dp(1), C.line) }
                "error" -> { setTextColor(C.err); gravity = Gravity.CENTER }
                else -> { setTextColor(C.dim); gravity = Gravity.CENTER }
            }
        }
        val lp = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8)
            gravity = when (role) {
                "user" -> Gravity.END
                "assistant" -> Gravity.START
                else -> Gravity.CENTER_HORIZONTAL
            }
        }
        if (role == "user" || role == "assistant") tv.maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        log.addView(tv, lp)
        scrollDown()
        return tv
    }

    fun removeMsg(v: View) { log.removeView(v) }
    fun clear() { log.removeAllViews() }
    fun scrollDown() { scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
}
