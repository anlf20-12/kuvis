package com.t.kuvis

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

private const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT

/**
 * 다누 그림 홈 화면.
 * 그림(572×1024) 안의 위치를 그림 기준 좌표로 정해 두고, 화면 크기에 맞춰 바꿔 씀.
 */
object SceneSpots {
    /** 고정줄 1~5번 자리 (그림 459×1024 기준) */
    val apps = listOf(
        RectF(110f, 485f, 235f, 645f),   // 1 안긴 흰 토끼 → 성경
        RectF(5f, 398f, 150f, 565f),     // 2 강아지 → 전화
        RectF(0f, 698f, 105f, 800f),     // 3 노란 오리 → 네이버 지도
        RectF(338f, 590f, 452f, 708f),   // 4 갈색 토끼 → 카메라
        RectF(372f, 728f, 459f, 872f)    // 5 오른쪽 아래 흰 토끼 → Claude
    )
    val names = listOf("안긴 흰 토끼", "강아지", "노란 오리", "갈색 토끼", "아래 흰 토끼")
    val board = RectF(335f, 450f, 445f, 512f)
    val face = RectF(168f, 385f, 300f, 500f)
    /** 달 자리 (누르면 날씨 앱) */
    val moon = RectF(250f, 75f, 360f, 185f)

    /** 날씨 코드 + 낮/밤 → 그림 이름 */
    fun key(w: WInfo?): String {
        val c = w?.code ?: 0
        if (c in 51..67 || c in 80..82 || c in 95..99) return "rain"
        if (c in 71..77 || c == 85 || c == 86) return "snow"
        if (isNight(w)) return "night"
        return if (c == 3 || c == 45 || c == 48) "cloudy" else "clear"
    }

    private fun isNight(w: WInfo?): Boolean {
        if (w != null && w.isDay >= 0 && System.currentTimeMillis() - w.fetched < 45 * 60_000L) return w.isDay == 0
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h < 6 || h >= 18
    }
}

/** 화면 전체에 깔리는 그림 (웃는 얼굴로 바뀌기, 다른 장에서는 살짝 어둡게) */
class SceneBackground(ctx: Context) : View(ctx) {
    private val cache = HashMap<String, Bitmap?>()
    var key = "clear"; private set
    private var base: Bitmap? = null
    private var smile: Bitmap? = null
    private var filler: Bitmap? = null
    var smileAlpha = 0f
        set(v) { field = v; invalidate() }
    var dim = 0f
        set(v) { field = v; invalidate() }

    private var scale = 1f
    private var left0 = 0f
    private var top0 = 0f
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val topLight = HashMap<String, Boolean>()

    init { setKey("clear") }

    private fun load(name: String): Bitmap? = cache.getOrPut(name) {
        try { context.assets.open("scene/$name.webp").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
    }

    fun setKey(k: String) {
        if (k == key && base != null) return
        key = k
        base = load(k)
        smile = load("smile_$k")
        // 위쪽 빈자리: 그림 맨 윗줄 색을 가로로 부드럽게 펴서 위로 늘림 (네모난 얼룩 없이)
        filler = base?.let { b ->
            val band = Bitmap.createBitmap(b, 0, 0, b.width, (b.height * 0.03f).toInt().coerceAtLeast(2))
            val tiny = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(band, 48, 1, true), 16, 1, true)
            Bitmap.createScaledBitmap(tiny, 64, 1, true)
        }
        base?.let { b ->
            if (!topLight.containsKey(k)) {
                val s = Bitmap.createScaledBitmap(Bitmap.createBitmap(b, 0, 0, b.width, (b.height * 0.08f).toInt()), 8, 2, true)
                var lum = 0f
                for (x in 0 until 8) for (y in 0 until 2) {
                    val c = s.getPixel(x, y)
                    lum += (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
                }
                topLight[k] = lum / 16f > 0.55f
            }
        }
        layoutImage(); invalidate()
    }

    /** 위쪽(상태 표시줄 자리)이 밝은지 — 어둡게 덮인 정도까지 셈 */
    /** 상태 표시줄 뒤에는 늘 살짝 어두운 그늘을 깔고 흰 글씨를 씀 (어떤 그림이어도 시간·배터리가 보이게) */
    fun topIsLight(): Boolean = false
    private val scrimPaint = Paint()

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); layoutImage() }

    private fun layoutImage() {
        val b = base ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // 그림(9:20)이 화면 비율과 거의 같아서 꽉 채움 (넘치는 만큼만 가운데 기준으로 잘림)
        scale = maxOf(w / b.width, h / b.height)
        val dw = b.width * scale; val dh = b.height * scale
        left0 = (w - dw) / 2f
        top0 = (h - dh) / 2f
    }

    /** 그림 좌표 → 이 뷰 좌표 */
    fun mapRect(r: RectF): RectF = RectF(left0 + r.left * scale, top0 + r.top * scale, left0 + r.right * scale, top0 + r.bottom * scale)

    /** 이 뷰 좌표 → 그림 좌표 */
    fun toImage(x: Float, y: Float): Pair<Float, Float> = ((x - left0) / scale) to ((y - top0) / scale)

    override fun onDraw(c: Canvas) {
        val b = base ?: run { c.drawColor(Color.rgb(30, 34, 40)); return }
        val w = width.toFloat(); val h = height.toFloat()
        val dst = RectF(left0, top0, left0 + b.width * scale, top0 + b.height * scale)
        drawImages(c, b, dst)
        val sh = context.dpf(90f)
        scrimPaint.shader = LinearGradient(0f, 0f, 0f, sh, Color.argb(110, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, sh, scrimPaint)
        if (dim > 0f) c.drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
    }

    private fun drawImages(c: Canvas, b: Bitmap, dst: RectF) {
        paint.alpha = 255
        c.drawBitmap(b, null, dst, paint)
        val s = smile
        if (s != null && smileAlpha > 0f) {
            paint.alpha = (smileAlpha * 255).toInt()
            c.drawBitmap(s, null, dst, paint)
            paint.alpha = 255
        }
    }
}

/** 효과음 (휴대폰이 소리 모드일 때만) */
object SceneSounds {
    private var pool: SoundPool? = null
    private var pop = 0
    private var tok = 0
    private fun init(ctx: Context) {
        if (pool != null) return
        pool = SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build().also { p ->
            try { pop = p.load(ctx.assets.openFd("scene/pop.wav"), 1) } catch (_: Exception) {}
            try { tok = p.load(ctx.assets.openFd("scene/tok.wav"), 1) } catch (_: Exception) {}
        }
    }
    fun prepare(ctx: Context) = init(ctx)
    private fun play(ctx: Context, id: Int, vol: Float) {
        init(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL || id == 0) return
        pool?.play(id, vol, vol, 1, 0, 1f)
    }
    fun pop(ctx: Context) = play(ctx, pop, 0.7f)
    fun tok(ctx: Context) = play(ctx, tok, 0.5f)
}

/** 첫 장: 그림 속 대상을 누르면 앱, 칠판에는 시계 */
class ScenePage(private val home: HomeActivity, private val bg: SceneBackground) : FrameLayout(home) {
    private val clockBox = LinearLayout(home).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
    private val time: TextClock
    private val date: TextClock
    private var downX = 0f
    private var downY = 0f
    private var longFired = false
    private val longPress = Runnable { longFired = true; onLong(downX, downY) }
    private var smileAnim: ValueAnimator? = null

    init {
        val chalk = ClockFonts.get(home, "DoHyeon-Regular.ttf", android.graphics.Typeface.DEFAULT)
        val chalkColor = Color.argb(235, 246, 243, 234)
        time = TextClock(home).apply {
            format12Hour = "h:mm"; format24Hour = "HH:mm"; textLocale = Locale.KOREAN
            typeface = chalk; setTextColor(chalkColor); includeFontPadding = false; maxLines = 1; gravity = Gravity.CENTER
            setShadowLayer(home.dpf(2f), 0f, 0f, Color.argb(90, 255, 255, 255))
        }
        date = TextClock(home).apply {
            format12Hour = "M월 d일 E"; format24Hour = "M월 d일 E"; textLocale = Locale.KOREAN
            typeface = chalk; setTextColor(Color.argb(200, 246, 243, 234)); includeFontPadding = false; maxLines = 1; gravity = Gravity.CENTER
        }
        clockBox.addView(time, LinearLayout.LayoutParams(WRAP, WRAP))
        clockBox.addView(date, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(clockBox, LayoutParams(0, 0))
        clipChildren = false
        SceneSounds.prepare(home)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        placeClock()
        super.onLayout(changed, l, t, r, b)
    }

    /** 배경 그림 좌표(화면 전체) → 이 장 안의 좌표 */
    private fun offset(): Pair<Float, Float> {
        val a = IntArray(2); val p = IntArray(2)
        bg.getLocationOnScreen(a); getLocationOnScreen(p)
        // 장을 넘기는 중이면 그만큼 밀린 것도 셈 (translationX는 이미 위치에 들어 있음)
        return (a[0] - p[0]).toFloat() to (a[1] - p[1]).toFloat()
    }

    fun placeClock() {
        val (ox, oy) = offset()
        val r = bg.mapRect(SceneSpots.board)
        val lp = clockBox.layoutParams as LayoutParams
        val w = r.width().toInt(); val h = r.height().toInt()
        if (lp.width != w || lp.height != h || lp.leftMargin != (r.left + ox).toInt() || lp.topMargin != (r.top + oy).toInt()) {
            lp.width = w; lp.height = h; lp.leftMargin = (r.left + ox).toInt(); lp.topMargin = (r.top + oy).toInt()
            clockBox.layoutParams = lp
            time.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fitSize(time, "12:88", w * 0.86f, h * 0.56f))
            date.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fitSize(date, "12월 28일 일", w * 0.8f, h * 0.2f))
        }
    }

    private fun fitSize(v: TextView, sample: String, maxW: Float, maxH: Float): Float {
        val p = Paint(v.paint).apply { textSize = maxH }
        val tw = p.measureText(sample)
        return (if (tw > maxW) maxH * maxW / tw else maxH).coerceAtLeast(home.dpf(8f))
    }

    /** 이 장 좌표 → 그림 좌표 */
    private fun img(x: Float, y: Float): Pair<Float, Float> {
        val (ox, oy) = offset()
        return bg.toImage(x - ox, y - oy)
    }

    private fun hit(x: Float, y: Float): String? {
        val (ix, iy) = img(x, y)
        if (SceneSpots.board.contains(ix, iy)) return "board"
        if (SceneSpots.face.contains(ix, iy)) return "face"
        SceneSpots.apps.forEachIndexed { i, r -> if (r.contains(ix, iy)) return "app$i" }
        if (SceneSpots.moon.contains(ix, iy)) return "sky"
        return null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; longFired = false
                if (hit(e.x, e.y) == null) return false
                postDelayed(longPress, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > home.dpf(12f)) removeCallbacks(longPress)
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!longFired && Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) < home.dpf(12f)) onTap(e.x, e.y)
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    private fun onTap(x: Float, y: Float) {
        val h = hit(x, y) ?: return
        when {
            h == "face" -> { SceneSounds.pop(home); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); smileNow() }
            h == "board" -> { SceneSounds.tok(home); ripple(SceneSpots.board); ClockMenu.show(home) }
            h == "sky" -> { SceneSounds.tok(home); performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); WeatherApp.open(home) }
            h.startsWith("app") -> {
                val i = h.removePrefix("app").toInt()
                SceneSounds.tok(home)
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                ripple(SceneSpots.apps[i])
                val pkg = home.sceneApp(i)
                if (pkg == null) home.pickSceneApp(i) else postDelayed({ home.launch(pkg) }, 140)
            }
        }
    }

    private fun onLong(x: Float, y: Float) {
        val h = hit(x, y) ?: return
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (h.startsWith("app")) home.pickSceneApp(h.removePrefix("app").toInt())
    }

    /** 누른 대상이 살짝 커지는 느낌 (부드러운 빛 번짐) */
    private fun ripple(r: RectF) {
        val (ox, oy) = offset()
        val m = bg.mapRect(r)
        val size = maxOf(m.width(), m.height()) * 1.05f
        val v = View(home).apply {
            background = ShapeDrawable(OvalShape()).apply {
                paint.shader = RadialGradient(size / 2, size / 2, size / 2,
                    intArrayOf(Color.argb(120, 255, 255, 255), Color.argb(50, 255, 250, 235), Color.TRANSPARENT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            }
            alpha = 0f; scaleX = 0.7f; scaleY = 0.7f
        }
        addView(v, LayoutParams(size.toInt(), size.toInt()).apply {
            leftMargin = (m.centerX() + ox - size / 2).toInt(); topMargin = (m.centerY() + oy - size / 2).toInt()
        })
        v.animate().alpha(1f).scaleX(1.08f).scaleY(1.08f).setDuration(130).withEndAction {
            v.animate().alpha(0f).scaleX(1.2f).scaleY(1.2f).setDuration(260).withEndAction { removeView(v) }.start()
        }.start()
    }

    /** 다누가 웃고 하트가 뿅 */
    fun smileNow() {
        smileAnim?.cancel()
        smileAnim = ValueAnimator.ofFloat(bg.smileAlpha, 1f).apply {
            duration = 160
            addUpdateListener { bg.smileAlpha = it.animatedValue as Float }
            start()
        }
        removeCallbacks(unsmile); postDelayed(unsmile, 1300)
        if (bg.key != "clear") hearts()   // 맑은 날 웃는 그림에는 하트가 이미 그려져 있음
        // 살짝 흔들림
        bg.animate().cancel()
        bg.translationY = 0f
        bg.animate().translationY(-home.dpf(3f)).setDuration(90).withEndAction {
            bg.animate().translationY(0f).setDuration(160).start()
        }.start()
    }

    private val unsmile = Runnable {
        smileAnim?.cancel()
        smileAnim = ValueAnimator.ofFloat(bg.smileAlpha, 0f).apply {
            duration = 320
            addUpdateListener { bg.smileAlpha = it.animatedValue as Float }
            start()
        }
    }

    private fun hearts() {
        val (ox, oy) = offset()
        val f = bg.mapRect(SceneSpots.face)
        val colors = intArrayOf(Color.rgb(244, 143, 177), Color.rgb(240, 98, 146), Color.rgb(255, 171, 190), Color.rgb(239, 83, 80))
        repeat(9) { n ->
            val t = TextView(home).apply {
                text = "♥"; setTextColor(colors[n % colors.size])
                textSize = 14f + Random.nextFloat() * 14f
                setShadowLayer(home.dpf(6f), 0f, 0f, Color.argb(120, 255, 200, 210))
                alpha = 0f
            }
            val cx = f.centerX() + ox + (Random.nextFloat() - 0.5f) * f.width() * 2.6f
            val cy = f.centerY() + oy + (Random.nextFloat() - 0.3f) * f.height() * 1.6f
            addView(t, LayoutParams(WRAP, WRAP).apply { leftMargin = cx.toInt(); topMargin = cy.toInt() })
            t.scaleX = 0.4f; t.scaleY = 0.4f
            t.animate().setStartDelay(n * 60L).alpha(0.95f).scaleX(1f).scaleY(1f).translationY(-home.dpf(18f)).setDuration(260)
                .withEndAction {
                    t.animate().setStartDelay(500).alpha(0f).translationY(-home.dpf(46f)).setDuration(520).withEndAction { removeView(t) }.start()
                }.start()
        }
    }
}
