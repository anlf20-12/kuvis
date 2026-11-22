package com.t.kuvis

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView

/** 색 */
object C {
    val bg = Color.parseColor("#05080F")
    val panel = Color.parseColor("#0D1422")
    val line = Color.parseColor("#1C2840")
    val text = Color.parseColor("#E6EDF7")
    val dim = Color.parseColor("#8A97AE")
    val user = Color.parseColor("#16324A")
    val err = Color.parseColor("#EF4444")
    val accent = Color.parseColor("#38BDF8")
    val green = Color.parseColor("#22C55E")
    val sun = Color.parseColor("#F87171")
    val sat = Color.parseColor("#60A5FA")
    /** 홈 화면 카드(배경 사진 위) */
    val card = Color.argb(185, 10, 16, 28)
    val chip = Color.parseColor("#2F7BD9")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(fill: Int, radius: Float, strokeW: Int = 0, stroke: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill); cornerRadius = radius
        if (strokeW > 0) setStroke(strokeW, stroke)
    }

fun Context.txt(
    s: String, size: Float = 15f, color: Int = C.text, bold: Boolean = false, gravity: Int = Gravity.START
): TextView = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); this.gravity = gravity
    if (bold) typeface = Typeface.DEFAULT_BOLD
}

/** 화면끼리 알리기: 할 일·메모·일정이 바뀜, 페이지 이동 */
object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val changed = mutableSetOf<() -> Unit>()
    private val pages = mutableSetOf<(String) -> Unit>()

    fun onChanged(l: () -> Unit) { changed.add(l) }
    fun offChanged(l: () -> Unit) { changed.remove(l) }
    fun onPage(l: (String) -> Unit) { pages.add(l) }
    fun offPage(l: (String) -> Unit) { pages.remove(l) }

    fun changed() = main.post { changed.toList().forEach { it() } }
    fun showPage(p: String) = main.post { pages.toList().forEach { it(p) } }
}
