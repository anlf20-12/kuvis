package com.t.kuvis

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 홈 화면 바둑판 (페이지 3장, 각 COLS × ROWS 칸).
 * kind: calendar | weather | clock | todo | memo | kuvis | app
 */
data class GridItem(val kind: String, val pkg: String = "", val x: Int, val y: Int, val w: Int, val h: Int) {
    val id: String get() = if (kind == "app") "app:$pkg" else kind

    fun toJson(): JSONObject = JSONObject().put("kind", kind).put("pkg", pkg).put("x", x).put("y", y).put("w", w).put("h", h)

    companion object {
        fun from(o: JSONObject) = GridItem(o.getString("kind"), o.optString("pkg"), o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"))
    }
}

object HomeGrid {
    const val COLS = 5
    const val ROWS = 6
    const val PAGES = 3

    /** 쿠비스 위젯 이름·기본 크기·최소 크기 */
    val KINDS = linkedMapOf(
        "calendar" to Triple("달력", 5 to 6, 3 to 3),
        "weather" to Triple("날씨", 5 to 2, 2 to 1),
        "clock" to Triple("시계", 5 to 1, 2 to 1),
        "todo" to Triple("할 일", 5 to 2, 2 to 2),
        "memo" to Triple("메모", 5 to 6, 2 to 2),
        "kuvis" to Triple("쿠비스 버튼", 3 to 1, 1 to 1)
    )

    fun minSize(kind: String): Pair<Int, Int> = KINDS[kind]?.third ?: (1 to 1)
    fun label(kind: String): String = KINDS[kind]?.first ?: kind

    private fun defaults(page: Int): List<GridItem> = when (page) {
        0 -> listOf(GridItem("calendar", x = 0, y = 0, w = 5, h = 6))
        1 -> listOf(
            GridItem("weather", x = 0, y = 0, w = 5, h = 2),
            GridItem("clock", x = 0, y = 2, w = 5, h = 1),
            GridItem("todo", x = 0, y = 3, w = 5, h = 2),
            GridItem("kuvis", x = 1, y = 5, w = 3, h = 1)
        )
        else -> listOf(GridItem("memo", x = 0, y = 0, w = 5, h = 6))
    }

    fun items(ctx: Context, page: Int): List<GridItem> {
        val s = Store(ctx).rawGet("grid_$page") ?: return defaults(page).also { save(ctx, page, it) }
        return try { val a = JSONArray(s); List(a.length()) { GridItem.from(a.getJSONObject(it)) } } catch (_: Exception) { defaults(page) }
    }

    fun save(ctx: Context, page: Int, list: List<GridItem>) {
        val a = JSONArray(); list.forEach { a.put(it.toJson()) }
        Store(ctx).rawPut("grid_$page", a.toString())
    }

    fun resetAll(ctx: Context) { for (p in 0 until PAGES) save(ctx, p, defaults(p)) }

    /** 이 칸들이 비었는지 (except는 옮기는 중인 자기 자신) */
    fun isFree(list: List<GridItem>, x: Int, y: Int, w: Int, h: Int, except: GridItem? = null): Boolean {
        if (x < 0 || y < 0 || x + w > COLS || y + h > ROWS) return false
        return list.none { it != except && x < it.x + it.w && it.x < x + w && y < it.y + it.h && it.y < y + h }
    }

    /** w×h가 들어갈 첫 빈자리 */
    fun firstFree(list: List<GridItem>, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..ROWS - h) for (x in 0..COLS - w) if (isFree(list, x, y, w, h)) return x to y
        return null
    }

    /** 어느 페이지에든 이미 있는 쿠비스 위젯 */
    fun placedKinds(ctx: Context): Set<String> = (0 until PAGES).flatMap { items(ctx, it) }.map { it.kind }.toSet()

    /** 지워진 앱 아이콘 정리 */
    fun prune(ctx: Context, installed: Set<String>) {
        for (p in 0 until PAGES) {
            val l = items(ctx, p)
            val k = l.filter { it.kind != "app" || it.pkg in installed }
            if (k.size != l.size) save(ctx, p, k)
        }
    }
}

/**
 * 바둑판 한 장. 자식 뷰를 칸에 맞춰 놓고, 길게 누르면 HomeActivity에 알림.
 */
class GridPageView(private val home: HomeActivity, val page: Int) : FrameLayout(home) {

    var items: List<GridItem> = emptyList(); private set
    private val views = HashMap<String, View>()
    private val outline = View(home)
    private val gap = home.dp(5)

    // 길게 누르기
    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(home).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downRawX = 0f
    private var downRawY = 0f
    private val longPress = Runnable {
        val it = itemAt(downX, downY)
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if (it != null) home.onGridItemLongPress(this, it, downRawX, downRawY)
        else home.onGridEmptyLongPress(this, cellAt(downX, downY))
    }

    init {
        clipChildren = false
        outline.visibility = View.GONE
        addView(outline)
    }

    val cellW: Float get() = (width - paddingLeft - paddingRight).toFloat() / HomeGrid.COLS
    val cellH: Float get() = (height - paddingTop - paddingBottom).toFloat() / HomeGrid.ROWS

    fun setItems(list: List<GridItem>, viewFor: (GridItem) -> View) {
        items = list
        // 빠진 것 떼기
        val keep = list.map { it.id }.toSet()
        for ((id, v) in views.toMap()) if (id !in keep) { removeView(v); views.remove(id) }
        for (it in list) {
            val v = views[it.id] ?: viewFor(it).also { nv ->
                (nv.parent as? ViewGroup)?.removeView(nv)
                views[it.id] = nv
                addView(nv, 0)
            }
            v.tag = it
            v.visibility = View.VISIBLE
        }
        requestLayout()
    }

    fun viewOf(item: GridItem): View? = views[item.id]

    fun rectOf(x: Int, y: Int, w: Int, h: Int): IntArray {
        val l = paddingLeft + (x * cellW).toInt() + gap
        val t = paddingTop + (y * cellH).toInt() + gap
        val r = paddingLeft + ((x + w) * cellW).toInt() - gap
        val b = paddingTop + ((y + h) * cellH).toInt() - gap
        return intArrayOf(l, t, r, b)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        val cw = (measuredWidth - paddingLeft - paddingRight).toFloat() / HomeGrid.COLS
        val ch = (measuredHeight - paddingTop - paddingBottom).toFloat() / HomeGrid.ROWS
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val it = c.tag as? GridItem
            val (w, h) = if (it != null) ((cw * it.w).toInt() - gap * 2) to ((ch * it.h).toInt() - gap * 2)
            else (c.layoutParams.width.coerceAtLeast(0)) to (c.layoutParams.height.coerceAtLeast(0))
            c.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(0), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h.coerceAtLeast(0), MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val it = c.tag as? GridItem
            if (it != null) {
                val r = rectOf(it.x, it.y, it.w, it.h)
                c.layout(r[0], r[1], r[2], r[3])
            } else if (c === outline) {
                val r = outlineRect
                if (r != null) c.layout(r[0], r[1], r[2], r[3])
            }
        }
    }

    // ---------- 놓을 자리 표시 ----------
    private var outlineRect: IntArray? = null

    fun showOutline(x: Int, y: Int, w: Int, h: Int, ok: Boolean) {
        outlineRect = rectOf(x, y, w, h)
        outline.background = rounded(if (ok) Color.argb(60, 255, 255, 255) else Color.argb(60, 239, 68, 68),
            home.dpf(18f), home.dp(2), if (ok) Color.WHITE else C.err)
        outline.visibility = View.VISIBLE
        outline.layoutParams = LayoutParams(outlineRect!![2] - outlineRect!![0], outlineRect!![3] - outlineRect!![1])
        outline.bringToFront()
        requestLayout()
    }

    fun hideOutline() { outline.visibility = View.GONE; outlineRect = null }

    // ---------- 손가락 위치 → 칸 ----------
    fun cellAt(x: Float, y: Float): Pair<Int, Int> =
        ((x - paddingLeft) / cellW).toInt().coerceIn(0, HomeGrid.COLS - 1) to ((y - paddingTop) / cellH).toInt().coerceIn(0, HomeGrid.ROWS - 1)

    fun itemAt(x: Float, y: Float): GridItem? {
        val (cx, cy) = cellAt(x, y)
        return items.firstOrNull { cx >= it.x && cx < it.x + it.w && cy >= it.y && cy < it.y + it.h }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downRawX = ev.rawX; downRawY = ev.rawY
                handler.removeCallbacks(longPress)
                if (home.canStartGridGesture()) handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) handler.removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return false
    }

    // 빈 곳은 자식이 없어 onTouchEvent로 옴: 길게 누르기를 받으려면 true
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) handler.removeCallbacks(longPress)
        }
        return true
    }

    fun cancelLongPress2() = handler.removeCallbacks(longPress)
}

/** 홈 화면의 앱 아이콘 칸 */
fun appIconView(ctx: Context, icon: android.graphics.drawable.Drawable?, label: String): LinearLayout =
    LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        addView(ImageView(ctx).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(ctx.dp(50), ctx.dp(50)))
        addView(TextView(ctx).apply {
            text = label; textSize = 12f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(ctx.dpf(3f), 0f, ctx.dpf(1f), Color.argb(200, 0, 0, 0))
            setPadding(ctx.dp(2), ctx.dp(3), ctx.dp(2), 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
