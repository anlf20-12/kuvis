package com.t.kuvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.text.Collator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class AppEntry(val label: String, val pkg: String, val icon: Drawable?, val category: Int = -1)

const val GAME_HUB = "com.samsung.android.game.gamehome"

/** 쿠비스 홈 화면(런처) */
class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private lateinit var mainContent: LinearLayout
    private lateinit var pager: ViewPager2
    private lateinit var grids: List<GridPageView>
    private lateinit var dots: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var sceneBg: SceneBackground
    private lateinit var scenePage: ScenePage
    /** 넘기는 장: 0 달력(바둑판 0번), 1 다누 그림, 2 할 일·메모·시간표 */
    private val SCENE = 1
    private lateinit var page3: LinearLayout
    private lateinit var timetableW: TimetableCard
    private fun gridIdx() = if (pager.currentItem == 0) 0 else -1
    private var lastWeather: WInfo? = null
    private lateinit var overlay: FrameLayout
    private lateinit var deleteBar: TextView
    private val main = Handler(Looper.getMainLooper())

    // 쿠비스 위젯 (한 개씩만 있음)
    private lateinit var calendarW: CalendarPage
    private lateinit var weatherW: WeatherWidget
    private lateinit var clockW: ClockWidget
    private lateinit var todoW: TodoWidget
    private lateinit var memoW: MemoPage
    private lateinit var kuvisW: KuvisButtonWidget

    // 앱 서랍
    private lateinit var drawer: FrameLayout
    private lateinit var drawerColumn: LinearLayout
    private lateinit var search: EditText
    private lateinit var appList: RecyclerView
    private val appAdapter = AppAdapter(inFolder = false)
    var apps: List<AppEntry> = emptyList(); private set

    // 폴더 창
    private lateinit var folderLayer: FrameLayout
    private lateinit var folderTitle: TextView
    private lateinit var folderList: RecyclerView
    private lateinit var folderCard: LinearLayout
    private lateinit var homeZone: TextView
    private val folderAdapter = AppAdapter(inFolder = true)
    private var openFolder: String? = null

    // 쓸기
    private var touchSlop = 16
    private var downX = 0f
    private var downY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var decided = false
    private var dragMode = 0 // 0 없음, 1 앱 창 올리기, 2 앱 창 내리기, 3 알림창
    private var startInDock = false
    private var vt: android.view.VelocityTracker? = null

    private val onChanged: () -> Unit = { refreshAll() }
    private val onPage: (String) -> Unit = { p ->
        closeDrawer()
        pager.setCurrentItem(when (p) { "calendar" -> 0; "memo" -> 2; else -> SCENE }, true)
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { loadApps() }
    }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        root = FrameLayout(this).apply { fitsSystemWindows = true }
        sceneBg = SceneBackground(this)
        lastWeather = Weather.cached(this)
        sceneBg.setKey(SceneSpots.key(lastWeather))
        scenePage = ScenePage(this, sceneBg)

        calendarW = CalendarPage(this)
        weatherW = WeatherWidget(this)
        clockW = ClockWidget(this)
        todoW = TodoWidget(this)
        memoW = MemoPage(this)
        kuvisW = KuvisButtonWidget(this)

        mainContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        grids = (0 until HomeGrid.PAGES).map { GridPageView(this, it).apply { setPadding(dp(6), dp(4), dp(6), dp(4)) } }
        pager = ViewPager2(this).apply {
            adapter = PagesAdapter(listOf<View>(grids[0], scenePage, buildPage3()))
            offscreenPageLimit = 2
        }
        mainContent.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mainContent.addView(dots, LinearLayout.LayoutParams(MATCH, dp(18)))
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { drawDots(position) }
            override fun onPageScrolled(position: Int, offset: Float, px: Int) {
                // 다누 그림 장에서는 고정줄 대신 그림 속 대상, 다른 장에서는 그림을 살짝 어둡게
                val sceneFrac = (1f - abs(position + offset - SCENE)).coerceIn(0f, 1f)
                sceneBg.dim = (1f - sceneFrac) * 0.38f
                if (::drawer.isInitialized && drawer.visibility != View.VISIBLE) setBarIconsDark(wallpaperIsLight())
            }
        })
        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(10))
        }
        mainContent.addView(dockRow, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(mainContent, FrameLayout.LayoutParams(MATCH, MATCH))

        drawer = buildDrawer()
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))

        // 끌기용 맨 위 층 (떠 있는 아이콘·삭제 칸·크기 손잡이)
        overlay = FrameLayout(this).apply { isClickable = false }
        deleteBar = txt("🗑  여기에 놓으면 홈에서 빠져요", 15f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(Color.argb(200, 185, 28, 28), dpf(18f))
            visibility = View.GONE
        }
        overlay.addView(deleteBar, FrameLayout.LayoutParams(MATCH, dp(56)).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        homeZone = txt("⌂  여기에 잠깐 대면 홈 화면으로", 15f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            background = rounded(Color.argb(190, 30, 64, 120), dpf(18f))
            visibility = View.GONE
        }
        overlay.addView(homeZone, FrameLayout.LayoutParams(MATCH, dp(56)).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        // 맨 아래 층: 화면 전체(상태 표시줄 뒤까지) 다누 그림
        val outer = FrameLayout(this)
        outer.addView(sceneBg, FrameLayout.LayoutParams(MATCH, MATCH))
        outer.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(outer)
        pager.setCurrentItem(SCENE, false)
        dots.visibility = View.GONE   // 화면 넘기는 번호 점은 안 보이게
        // 고정줄은 어느 장에서도 안 보임 (고정 앱은 다누 그림 속 대상으로) → 모든 장이 화면 끝까지 꽉 참
        dockRow.visibility = View.GONE

        Bus.onChanged(onChanged)
        Bus.onPage(onPage)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

        fixClockOnce()
        loadApps()
        refreshAll()
        firstRun()
    }

    override fun onDestroy() {
        super.onDestroy()
        Bus.offChanged(onChanged)
        Bus.offPage(onPage)
        try { unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
        Updater.checkQuietly(this)
        Hotword.sync(this)
        main.removeCallbacks(sceneTick); main.post(sceneTick)
        wallLight = null; barsDark = null
        if (drawer.visibility != View.VISIBLE) setBarIconsDark(wallpaperIsLight())
        if (Build.VERSION.SDK_INT >= 33 && Hotword.enabled(this) && !mp0().getBoolean("askedNotif", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            mp0().edit().putBoolean("askedNotif", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        Backup.autoDaily(this)
        val mp = Prefs(this).p
        if (mp.contains("ghToken")) mp.edit().remove("ghToken").remove("lastMemPull").apply()   // 기억장 빼면서 저장해 둔 토큰도 지움
        if (mp.getBoolean("crashedLastTime", false)) {
            mp.edit().putBoolean("crashedLastTime", false).apply()
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("쿠비스가 멈췄었어요")
                .setMessage("오류 기록을 Claude에게 보내 주시면 고칠 수 있어요.")
                .setPositiveButton("보내기") { _, _ ->
                    safeStart(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "쿠비스 오류 기록\n\n" + ErrorLog.read(this).takeLast(20000)), "오류 기록 보내기"))
                }
                .setNegativeButton("나중에", null).show()
        }
        Thread {
            val w = Weather.get(this)
            runOnUiThread { weatherW.setWeather(w); setClockWeather(w); lastWeather = w ?: lastWeather; updateScene() }
        }.start()
    }

    private fun setClockWeather(w: WInfo?) {
        if (w == null) return
        val rain = w.days.firstOrNull()?.rain ?: 0
        ClockWidget.weatherHint = "${w.temp.roundToInt()}°" + if (rain >= 50) " · 비 올 수 있어요" else ""
        clockW.refresh()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(sceneTick)
        endDrag(cancel = true)
        endDrawerDrag()
        endResize()
    }

    /** 홈 버튼을 다시 누르면 가운데로 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        endResize()
        if (drawer.visibility == View.VISIBLE) closeDrawer() else pager.setCurrentItem(SCENE, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            resize != null -> endResize()
            openFolder != null -> closeFolder()
            drawer.visibility == View.VISIBLE -> closeDrawer()
            else -> pager.setCurrentItem(SCENE, true)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshAll()
        Thread { val w = Weather.get(this, 0L); runOnUiThread { weatherW.setWeather(w) } }.start()
        if (Prefs(this).key.isBlank()) SettingsActivity.open(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            61 -> { if (resultCode == RESULT_OK) configureOrPlace() else cancelPending(); return }
            62 -> { if (resultCode == RESULT_OK) placePending() else cancelPending(); return }
        }
        if (requestCode == 51 && resultCode == RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            search.setText(t.replace(" 열어 줘", "").replace(" 열어줘", "").trim())
        }
    }

    // =====================================================================
    fun refreshAll() {
        calendarW.refresh(); weatherW.refresh(); todoW.refresh(); memoW.refresh()
        if (::timetableW.isInitialized) timetableW.refresh()
        renderGrids()
        drawDock()
        if (::search.isInitialized) appAdapter.filter(search.text.toString())
        openFolder?.let { folderAdapter.showFolder(it) }
    }

    private fun drawDots(sel: Int) {
        dots.removeAllViews()
        for (i in 0 until 3) {
            dots.addView(View(this).apply {
                background = rounded(if (i == sel) Color.WHITE else Color.argb(110, 255, 255, 255), dpf(4f))
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
    }

    private fun mp0() = Prefs(this).p

    fun safeStart(i: Intent) {
        try { startActivity(i) } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage("열 수 있는 앱이 없어요.").setPositiveButton("확인", null).show()
        }
    }

    fun callKuvis() {
        startActivity(Intent(this, AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun askPermissions() {
        val all = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ).let { if (Build.VERSION.SDK_INT >= 33) it + Manifest.permission.POST_NOTIFICATIONS else it }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (all.isNotEmpty()) requestPermissions(all.toTypedArray(), 1)
    }

    private fun firstRun() {
        val p = Prefs(this).p
        if (p.getBoolean("firstRunDone", false)) return
        p.edit().putBoolean("firstRunDone", true).apply()
        askPermissions()
    }

    @Suppress("WrongConstant")
    private fun expandNotifications() {
        try {
            val sb = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sb)
        } catch (_: Exception) {}
    }

    fun showMenu() = SettingsActivity.open(this)

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 한 번만: 시계 위젯을 예전 홈 화면처럼 한 줄 높이로 (가능하면 가로 가득) */
    private fun fixClockOnce() {
        if (store.rawGet("clockV2") != null) return
        store.rawPut("clockV2", "1")
        for (p in 0 until HomeGrid.PAGES) {
            val list = HomeGrid.items(this, p)
            val c = list.firstOrNull { it.kind == "clock" } ?: continue
            val full = c.copy(x = 0, w = HomeGrid.COLS, h = 1)
            val fixed = if (HomeGrid.isFree(list, full.x, full.y, full.w, full.h, c)) full else c.copy(h = 1)
            HomeGrid.save(this, p, list.map { if (it == c) fixed else it })
        }
    }

    // =====================================================================
    // 홈 바둑판
    private fun viewFor(item: GridItem): View = when (item.kind) {
        "calendar" -> calendarW
        "weather" -> weatherW
        "clock" -> clockW
        "todo" -> todoW
        "memo" -> memoW
        "kuvis" -> kuvisW
        "widget" -> widgetView(item)
        "shortcut" -> appIconView(this, Shortcuts.icon(this, item.pkg), Shortcuts.label(this, item.pkg)).apply {
            setOnClickListener { Shortcuts.start(this@HomeActivity, item.pkg) }
        }
        else -> {
            val app = apps.firstOrNull { it.pkg == item.pkg }
            val icon = app?.icon ?: try { packageManager.getApplicationIcon(item.pkg) } catch (_: Exception) { null }
            appIconView(this, icon, app?.label ?: item.pkg).apply { setOnClickListener { launch(item.pkg) } }
        }
    }

    /** 3번 장: 위쪽 할 일 + 메모 나란히, 아래 시간표 */
    private fun buildPage3(): View {
        page3 = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (todoW.parent as? ViewGroup)?.removeView(todoW)
        (memoW.parent as? ViewGroup)?.removeView(memoW)
        top.addView(todoW, LinearLayout.LayoutParams(0, MATCH, 1f).apply { rightMargin = dp(4) })
        top.addView(memoW, LinearLayout.LayoutParams(0, MATCH, 1f).apply { leftMargin = dp(4) })
        page3.addView(top, LinearLayout.LayoutParams(MATCH, 0, 1f))
        timetableW = TimetableCard(this)
        page3.addView(timetableW, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        return page3
    }

    private fun renderGrids() {
        // 바둑판은 달력 장(0번)만 씀. 할 일·메모는 3번 장에 직접 들어감
        grids[0].setItems(HomeGrid.items(this, 0).filter { it.kind != "todo" && it.kind != "memo" }) { viewFor(it) }
        root.post { syncWidgetSizes() }
    }

    // ---------- 다른 앱 위젯 ----------
    private val widgetSizes = HashMap<Int, String>()

    private fun widgetView(item: GridItem): View {
        val id = item.pkg.toIntOrNull() ?: -1
        val info = if (id >= 0) WidgetHost.info(this, id) else null
        if (info == null) return txt("위젯을 불러올 수 없어요.\n길게 눌러 빼 주세요.", 12f, Color.WHITE, gravity = Gravity.CENTER).apply {
            background = rounded(Color.argb(90, 0, 0, 0), dpf(18f))
        }
        return WidgetHost.host(this).createView(applicationContext, id, info).apply { setPadding(0, 0, 0, 0) }
    }

    /** 위젯에 칸 크기를 알려 줌 (크기 바꿨을 때 내용이 맞게 다시 그려지도록) */
    private fun syncWidgetSizes() {
        val g = grids[0]
        if (g.cellW <= 0f) return
        WidgetHost.cellW = g.cellW; WidgetHost.cellH = g.cellH
        for (p in 0 until HomeGrid.PAGES) for (it in HomeGrid.items(this, p)) {
            if (it.kind != "widget") continue
            val id = it.pkg.toIntOrNull() ?: continue
            val k = "${it.w}x${it.h}"
            if (widgetSizes[id] == k) continue
            widgetSizes[id] = k
            try { WidgetHost.mgr(this).updateAppWidgetOptions(id, WidgetHost.sizeOptions(this, it.w, it.h)) } catch (_: Exception) {}
        }
    }

    private fun minSizeOf(item: GridItem): Pair<Int, Int> {
        if (item.kind != "widget") return HomeGrid.minSize(item.kind)
        val info = item.pkg.toIntOrNull()?.let { WidgetHost.info(this, it) } ?: return 1 to 1
        val c = WidgetHost.cells(this, info)
        return c[2] to c[3]
    }

    /** 홈에서 빼기 (다른 앱 위젯이면 위젯 연결도 끊음) */
    private fun removeFromHome(page: Int, item: GridItem) {
        HomeGrid.save(this, page, HomeGrid.items(this, page).filter { it != item })
        if (item.kind == "widget") item.pkg.toIntOrNull()?.let { WidgetHost.delete(this, it); widgetSizes.remove(it) }
        if (item.kind in HomeGrid.KINDS) toast("빈 곳을 길게 눌러 '쿠비스 위젯 추가'로 다시 넣을 수 있어요.")
    }

    private class PendingWidget(val id: Int, val info: android.appwidget.AppWidgetProviderInfo, val page: Int, val cell: Pair<Int, Int>?)
    private var pending: PendingWidget? = null

    /** 위젯 고르는 화면 (삼성처럼 앱별로 묶고 미리보기) */
    private fun openWidgetPicker(page: Int, cell: Pair<Int, Int>?) {
        val providers = try { WidgetHost.mgr(this).installedProviders } catch (_: Exception) { emptyList() }
        if (providers.isEmpty()) { toast("넣을 수 있는 위젯이 없어요."); return }
        val pm = packageManager
        val col = Collator.getInstance(Locale.KOREAN)
        fun appLabel(pkg: String) = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        val groups = providers.groupBy { it.provider.packageName }.map { (pkg, l) -> Triple(pkg, appLabel(pkg), l) }
            .sortedWith { a, b -> col.compare(a.second, b.second) }

        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(16, 18, 24)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(18), dp(12), dp(10)) }
        head.addView(txt("위젯", 26f, Color.WHITE, bold = true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(txt("✕", 22f, Color.WHITE, gravity = Gravity.CENTER).apply { setPadding(dp(14), dp(6), dp(14), dp(6)); setOnClickListener { dlg.dismiss() } })
        outer.addView(head)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(24)) }
        val sv = android.widget.ScrollView(this).apply { addView(list) }
        outer.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        for ((pkg, label, infos) in groups) {
            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(Color.argb(40, 255, 255, 255), dpf(20f))
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(ImageView(this).apply { setImageDrawable(try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }) }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(txt(label, 16f, Color.WHITE, bold = true).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
            val count = txt("${infos.size}개  ›", 13f, C.dim)
            row.addView(count)
            section.addView(row)
            var shelf: View? = null
            row.setOnClickListener {
                shelf?.let { v ->
                    v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    count.text = if (v.visibility == View.VISIBLE) "${infos.size}개  ⌄" else "${infos.size}개  ›"
                    return@setOnClickListener
                }
                // 처음 펼칠 때 미리보기 그림을 불러옴
                val hs = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
                val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4)) }
                for (info in infos) {
                    val c = WidgetHost.cells(this, info)
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                        background = rounded(Color.argb(50, 255, 255, 255), dpf(16f))
                        setPadding(dp(10), dp(10), dp(10), dp(10))
                        setOnClickListener { dlg.dismiss(); beginAddWidget(info, page, cell) }
                    }
                    val prev = try { info.loadPreviewImage(this, 0) } catch (_: Exception) { null }
                        ?: try { info.loadIcon(this, 0) } catch (_: Exception) { null }
                    card.addView(ImageView(this).apply { setImageDrawable(prev); scaleType = ImageView.ScaleType.FIT_CENTER }, LinearLayout.LayoutParams(dp(150), dp(110)))
                    card.addView(txt(WidgetHost.label(this, info), 13f, Color.WHITE, gravity = Gravity.CENTER).apply {
                        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(6), 0, 0)
                    }, LinearLayout.LayoutParams(dp(150), WRAP))
                    card.addView(txt("${c[0]} × ${c[1]}", 12f, C.dim, gravity = Gravity.CENTER), LinearLayout.LayoutParams(dp(150), WRAP))
                    cards.addView(card, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
                }
                hs.addView(cards)
                section.addView(hs)
                shelf = hs
                count.text = "${infos.size}개  ⌄"
            }
            list.addView(section, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        dlg.setContentView(outer)
        dlg.show()
    }

    private fun beginAddWidget(info: android.appwidget.AppWidgetProviderInfo, page: Int, cell: Pair<Int, Int>?) {
        val id = WidgetHost.host(this).allocateAppWidgetId()
        pending = PendingWidget(id, info, page, cell)
        val ok = try { WidgetHost.mgr(this).bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null) } catch (_: Exception) { false }
        if (ok) configureOrPlace()
        else try {
            // 안드로이드가 '쿠비스가 위젯을 만들도록 허용할까요?'를 물어봄
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), 61)
        } catch (_: Exception) { cancelPending(); toast("위젯 허용 화면을 열 수 없어요.") }
    }

    private fun configureOrPlace() {
        val p = pending ?: return
        if (p.info.configure != null) {
            try { WidgetHost.host(this).startAppWidgetConfigureActivityForResult(this, p.id, 0, 62, null); return }
            catch (_: Exception) {}
        }
        placePending()
    }

    private fun placePending() {
        val p = pending ?: return
        pending = null
        val c = WidgetHost.cells(this, p.info)
        if (HomeGrid.placeAnywhere(this, "widget", p.id.toString(), c[0], c[1], c[2], c[3], p.page, p.cell)) {
            renderGrids()
        } else {
            WidgetHost.delete(this, p.id)
            toast("홈 화면에 빈 칸이 모자라요. 다른 걸 옮기거나 줄여 주세요.")
        }
    }

    private fun cancelPending() {
        pending?.let { WidgetHost.delete(this, it.id) }
        pending = null
    }

    override fun onStart() {
        super.onStart()
        try { WidgetHost.host(this).startListening() } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        try { WidgetHost.host(this).stopListening() } catch (_: Exception) {}
    }

    fun canStartGridGesture() = drawer.visibility != View.VISIBLE && drag == null && resize == null && dragMode == 0

    /** 바둑판의 위젯·아이콘을 길게 누름 → 끌기 시작 (안 움직이고 떼면 메뉴) */
    fun onGridItemLongPress(grid: GridPageView, item: GridItem, rawX: Float, rawY: Float) {
        val v = grid.viewOf(item) ?: return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        startDrag(item, grid.page, snapshot(v), v.width, v.height, rawX - loc[0], rawY - loc[1], rawX, rawY, v) {
            gridItemMenu(grid.page, item)
        }
    }

    /** 빈 곳 길게 누름 → 홈 메뉴 */
    fun onGridEmptyLongPress(grid: GridPageView, cell: Pair<Int, Int>) {
        val missing = HomeGrid.KINDS.keys.filter { it !in HomeGrid.placedKinds(this) }
        val items = mutableListOf<String>()
        if (missing.isNotEmpty()) items.add("쿠비스 위젯 추가")
        items.add("다른 앱 위젯 추가")
        items.add("앱 아이콘 추가"); items.add("배경화면 바꾸기"); items.add("쿠비스 설정"); items.add("홈 화면을 처음 배치로")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items.toTypedArray()) { _, w ->
                when (items[w]) {
                    "쿠비스 위젯 추가" -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("어떤 위젯을 넣을까요?")
                        .setItems(missing.map { HomeGrid.label(it) }.toTypedArray()) { _, k -> addKuvisWidget(grid.page, missing[k], cell) }
                        .show()
                    "다른 앱 위젯 추가" -> openWidgetPicker(grid.page, cell)
                    "앱 아이콘 추가" -> {
                        val visible = visibleApps()
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("홈에 놓을 앱")
                            .setItems(visible.map { it.label }.toTypedArray()) { _, k -> addAppToHome(grid.page, visible[k].pkg, cell) }
                            .show()
                    }
                    "배경화면 바꾸기" -> safeStart(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "배경화면"))
                    "쿠비스 설정" -> SettingsActivity.open(this)
                    "홈 화면을 처음 배치로" -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("홈 화면 3장을 처음 배치로 되돌릴까요? 홈에 꺼내 둔 앱 아이콘·다른 앱 위젯은 빠져요. (앱은 지워지지 않아요)")
                        .setPositiveButton("되돌리기") { _, _ ->
                            for (p in 0 until HomeGrid.PAGES) HomeGrid.items(this, p).filter { it.kind == "widget" }
                                .forEach { it.pkg.toIntOrNull()?.let { id -> WidgetHost.delete(this, id) } }
                            HomeGrid.resetAll(this); renderGrids()
                        }
                        .setNegativeButton("취소", null).show()
                }
            }.show()
    }

    private fun addKuvisWidget(page: Int, kind: String, cell: Pair<Int, Int>) {
        val list = HomeGrid.items(this, page)
        val (dw, dh) = HomeGrid.KINDS[kind]!!.second
        val (mw, mh) = HomeGrid.minSize(kind)
        // 누른 자리 → 첫 빈자리, 큰 크기부터 작은 크기까지
        for (h in dh downTo mh) for (w in dw downTo mw) {
            val spot = if (HomeGrid.isFree(list, cell.first, cell.second, w, h)) cell else HomeGrid.firstFree(list, w, h)
            if (spot != null) {
                HomeGrid.save(this, page, list + GridItem(kind, x = spot.first, y = spot.second, w = w, h = h))
                renderGrids(); return
            }
        }
        toast("이 화면에 빈 칸이 모자라요. 다른 걸 옮기거나 줄여 주세요.")
    }

    private fun addAppToHome(page: Int, pkg: String, cell: Pair<Int, Int>?) {
        val list = HomeGrid.items(this, page)
        if (list.any { it.kind == "app" && it.pkg == pkg }) { toast("이미 이 화면에 있어요."); return }
        val spot = cell?.takeIf { HomeGrid.isFree(list, it.first, it.second, 1, 1) } ?: HomeGrid.firstFree(list, 1, 1)
        if (spot == null) { toast("이 화면에 빈 칸이 없어요."); return }
        HomeGrid.save(this, page, list + GridItem("app", pkg, spot.first, spot.second, 1, 1))
        renderGrids()
    }

    private fun gridItemMenu(page: Int, item: GridItem) {
        val opts = mutableListOf<String>()
        var title = HomeGrid.label(item.kind)
        var pkg = item.pkg
        var winfo: android.appwidget.AppWidgetProviderInfo? = null
        when (item.kind) {
            "app" -> {
                title = apps.firstOrNull { it.pkg == item.pkg }?.label ?: item.pkg
                opts.add("홈에서 빼기"); opts.add("숨기기"); opts.add("앱 정보"); opts.add("앱 삭제")
            }
            "shortcut" -> {
                title = Shortcuts.label(this, item.pkg); pkg = Shortcuts.pkgOf(item.pkg)
                opts.add("홈에서 빼기"); opts.add("앱 정보")
            }
            "widget" -> {
                winfo = item.pkg.toIntOrNull()?.let { WidgetHost.info(this, it) }
                title = winfo?.let { WidgetHost.label(this, it) } ?: "위젯"
                pkg = winfo?.provider?.packageName ?: ""
                if (winfo != null && WidgetHost.resizable(winfo)) opts.add("크기 조절")
                if (winfo?.configure != null) opts.add("위젯 설정")
                opts.add("홈에서 빼기")
                if (pkg.isNotEmpty()) opts.add("앱 정보")
            }
            "clock" -> { opts.add("시계 모양 바꾸기"); opts.add("크기 조절"); opts.add("홈에서 빼기") }
            else -> { opts.add("크기 조절"); opts.add("홈에서 빼기") }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(opts.toTypedArray()) { _, w ->
                when (opts[w]) {
                    "크기 조절" -> beginResize(page, item)
                    "시계 모양 바꾸기" -> clockStylePicker()
                    "위젯 설정" -> item.pkg.toIntOrNull()?.let { id ->
                        try { WidgetHost.host(this).startAppWidgetConfigureActivityForResult(this, id, 0, 63, null) }
                        catch (_: Exception) { toast("이 위젯은 설정을 다시 열 수 없어요.") }
                    }
                    "홈에서 빼기" -> { removeFromHome(page, item); renderGrids() }
                    "숨기기" -> hideApp(item.pkg)
                    "앱 정보" -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                    "앱 삭제" -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${item.pkg}")))
                }
            }.show()
    }

    /** 시계 모양 고르기: 미리보기를 보고 누르면 바로 바뀜 */
    private fun clockStylePicker() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(12)) }
        list.addView(txt("시계 모양 고르기", 18f, Color.WHITE, bold = true).apply { setPadding(dp(2), 0, 0, dp(6)) })
        val sv = android.widget.ScrollView(this).apply { addView(list); setBackgroundColor(Color.rgb(28, 28, 32)) }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(sv).setNegativeButton("닫기", null).create()
        val cur = clockW.currentStyle()
        // 미리보기는 실제 홈의 시계 칸 크기 그대로, 뒤에는 홈 배경(다누 그림)을 깔아서
        val cw = if (clockW.width > 0) clockW.width else dp(300)
        val ch = if (clockW.height > 0) clockW.height else dp(104)
        val back = sceneBackdrop()
        ClockWidget.NAMES.forEachIndexed { i, name ->
            list.addView(txt((if (i == cur) "✓  " else "") + name, 15f, if (i == cur) Color.rgb(250, 199, 117) else Color.rgb(225, 225, 230), bold = i == cur).apply {
                setPadding(dp(2), dp(14), 0, dp(6))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            val box = FrameLayout(this).apply {
                if (back != null) background = android.graphics.drawable.BitmapDrawable(resources, back)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, dpf(22f)) }
                }
                setOnClickListener { clockW.setStyle(i); dlg.dismiss() }
            }
            box.addView(ClockWidget(this, i).apply { isClickable = false; isLongClickable = false }, FrameLayout.LayoutParams(cw, ch, Gravity.CENTER))
            list.addView(box, LinearLayout.LayoutParams(cw, ch).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        dlg.show()
        // 미리보기 칸이 들어가게 창 너비를 넓힘
        dlg.window?.setLayout((cw + dp(36 + 16)).coerceAtMost(resources.displayMetrics.widthPixels), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.rgb(28, 28, 32)))
    }

    /** 시계 고르기 미리보기 뒤에 깔 홈 배경 조각 (위젯 장처럼 살짝 어둡게) */
    private fun sceneBackdrop(): Bitmap? = try {
        if (sceneBg.width <= 0 || clockW.width <= 0) null else {
            val full = Bitmap.createBitmap(sceneBg.width, sceneBg.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(full)
            val d = sceneBg.dim; sceneBg.dim = 0.38f; sceneBg.draw(c); sceneBg.dim = d
            val a = IntArray(2); val b = IntArray(2)
            clockW.getLocationOnScreen(a); sceneBg.getLocationOnScreen(b)
            val x = (a[0] - b[0]).coerceIn(0, full.width - 1); val y = (a[1] - b[1]).coerceIn(0, full.height - 1)
            Bitmap.createBitmap(full, x, y, clockW.width.coerceAtMost(full.width - x), clockW.height.coerceAtMost(full.height - y))
        }
    } catch (_: Exception) { null }

    private fun snapshot(v: View): Bitmap? = try {
        Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    } catch (_: Exception) { null }

    // ---------- 끌기 ----------
    private class Drag(
        val item: GridItem, val fromPage: Int?, val float: ImageView, val grabX: Float, val grabY: Float,
        val startX: Float, val startY: Float, val source: View?, val onTap: () -> Unit
    ) {
        var moved = false
        var cancelSent = false
        var target: Triple<Int, Int, Boolean>? = null
    }

    private var drag: Drag? = null
    private var flip: Runnable? = null

    private fun startDrag(item: GridItem, fromPage: Int?, bmp: Bitmap?, w: Int, h: Int, grabX: Float, grabY: Float,
                          rawX: Float, rawY: Float, source: View?, icon: Drawable? = null, onTap: () -> Unit) {
        val iv = ImageView(this).apply {
            if (bmp != null) setImageBitmap(bmp) else setImageDrawable(icon)
            alpha = 0f; scaleX = 1.05f; scaleY = 1.05f
            elevation = dpf(12f)
        }
        overlay.addView(iv, FrameLayout.LayoutParams(w, h))
        drag = Drag(item, fromPage, iv, grabX, grabY, rawX, rawY, source, onTap)
        pager.isUserInputEnabled = false
        positionFloat(rawX, rawY)
    }

    private fun positionFloat(rawX: Float, rawY: Float) {
        val d = drag ?: return
        val loc = IntArray(2); overlay.getLocationOnScreen(loc)
        d.float.x = rawX - d.grabX - loc[0]
        d.float.y = rawY - d.grabY - loc[1]
    }

    private fun handleDrag(ev: MotionEvent): Boolean {
        val d = drag ?: return false
        if (!d.cancelSent) {
            // 밑에 있는 위젯·목록이 이 손가락을 놓게
            d.cancelSent = true
            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c); c.recycle()
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!d.moved && hypot(ev.rawX - d.startX, ev.rawY - d.startY) > touchSlop * 2) {
                    d.moved = true
                    d.float.alpha = 0.9f
                    d.source?.alpha = 0.25f
                    if (d.fromPage == null) hideDrawerNow() else deleteBar.visibility = View.VISIBLE
                }
                if (d.moved) {
                    positionFloat(ev.rawX, ev.rawY)
                    updateTarget(ev.rawX, ev.rawY)
                    edgeFlip(ev.rawX)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!d.moved) { endDrag(cancel = true); if (ev.actionMasked == MotionEvent.ACTION_UP) d.onTap() }
                else drop(ev.rawX, ev.rawY)
            }
        }
        return true
    }

    private fun overDelete(rawY: Float): Boolean {
        if (deleteBar.visibility != View.VISIBLE) return false
        val loc = IntArray(2); deleteBar.getLocationOnScreen(loc)
        return rawY <= loc[1] + deleteBar.height
    }

    private fun updateTarget(rawX: Float, rawY: Float) {
        val d = drag ?: return
        val page = gridIdx()
        if (page < 0) { grids.forEach { it.hideOutline() }; d.target = null; deleteBar.alpha = if (overDelete(rawY)) 1f else 0.8f; return }
        val g = grids[page]
        grids.filter { it !== g }.forEach { it.hideOutline() }
        if (overDelete(rawY)) { g.hideOutline(); d.target = null; deleteBar.alpha = 1f; return }
        deleteBar.alpha = 0.8f
        val loc = IntArray(2); g.getLocationOnScreen(loc)
        val left = rawX - d.grabX - loc[0] - g.paddingLeft
        val top = rawY - d.grabY - loc[1] - g.paddingTop
        val x = (left / g.cellW).roundToInt().coerceIn(0, HomeGrid.COLS - d.item.w)
        val y = (top / g.cellH).roundToInt().coerceIn(0, HomeGrid.ROWS - d.item.h)
        val list = HomeGrid.items(this, page)
        val samePage = d.fromPage == page
        var ok = HomeGrid.isFree(list, x, y, d.item.w, d.item.h, if (samePage) d.item else null)
        if (ok && !samePage && d.item.kind == "app" && list.any { it.kind == "app" && it.pkg == d.item.pkg }) ok = false
        d.target = Triple(x, y, ok)
        g.showOutline(x, y, d.item.w, d.item.h, ok)
    }

    /** 화면 가장자리에 잠깐 머물면 옆 장으로 */
    private fun edgeFlip(rawX: Float) {
        val edge = dp(28)
        val w = root.width
        val dir = when { rawX < edge -> -1; rawX > w - edge -> 1; else -> 0 }
        if (dir == 0) { flip?.let { main.removeCallbacks(it) }; flip = null; return }
        if (flip != null) return
        flip = Runnable {
            flip = null
            val to = pager.currentItem   // 바둑판은 달력 장 하나뿐이라 넘기지 않음
            if (to != pager.currentItem) { grids.forEach { it.hideOutline() }; pager.setCurrentItem(to, true) }
        }.also { main.postDelayed(it, 600) }
    }

    private fun drop(rawX: Float, rawY: Float) {
        val d = drag ?: return
        val page = gridIdx().coerceAtLeast(0)
        if (overDelete(rawY) && d.fromPage != null) {
            removeFromHome(d.fromPage, d.item)
        } else {
            val t = d.target
            if (t != null && t.third) {
                val moved = d.item.copy(x = t.first, y = t.second)
                if (d.fromPage == page) {
                    HomeGrid.save(this, page, HomeGrid.items(this, page).map { if (it == d.item) moved else it })
                } else {
                    if (d.fromPage != null) HomeGrid.save(this, d.fromPage, HomeGrid.items(this, d.fromPage).filter { it != d.item })
                    HomeGrid.save(this, page, HomeGrid.items(this, page) + moved)
                }
            } else if (t != null) toast("그 자리에는 놓을 수 없어요.")
        }
        endDrag(cancel = false)
    }

    private fun endDrag(cancel: Boolean) {
        val d = drag ?: return
        drag = null
        flip?.let { main.removeCallbacks(it) }; flip = null
        overlay.removeView(d.float)
        d.source?.alpha = 1f
        deleteBar.visibility = View.GONE
        grids.forEach { it.hideOutline() }
        pager.isUserInputEnabled = true
        if (!cancel) renderGrids()
    }

    // ---------- 크기 조절 ----------
    private class Resize(val page: Int, var item: GridItem, val handles: List<View>) {
        var corner = 0 // 0 없음, 1 왼쪽 위, 2 오른쪽 아래
        var preview: GridItem = item
        var ok = true
    }

    private var resize: Resize? = null

    private fun beginResize(page: Int, item: GridItem) {
        endResize()
        if (pager.currentItem != 0) pager.setCurrentItem(0, false)
        val hs = (0..1).map {
            View(this).apply { background = rounded(Color.WHITE, dpf(14f), dp(3), C.accent); elevation = dpf(10f) }
                .also { v -> overlay.addView(v, FrameLayout.LayoutParams(dp(28), dp(28))) }
        }
        resize = Resize(page, item, hs)
        pager.isUserInputEnabled = false
        toast("모서리의 동그라미를 끌어 크기를 바꾸세요. 다른 곳을 누르면 끝나요.")
        root.post { showResize() }
    }

    private fun showResize() {
        val r = resize ?: return
        val g = grids[r.page]
        val p = r.preview
        g.showOutline(p.x, p.y, p.w, p.h, r.ok)
        val rect = g.rectOf(p.x, p.y, p.w, p.h)
        val gl = IntArray(2); g.getLocationOnScreen(gl)
        val ol = IntArray(2); overlay.getLocationOnScreen(ol)
        val ox = gl[0] - ol[0]; val oy = gl[1] - ol[1]
        val half = dp(14)
        r.handles[0].x = (ox + rect[0] - half).toFloat(); r.handles[0].y = (oy + rect[1] - half).toFloat()
        r.handles[1].x = (ox + rect[2] - half).toFloat(); r.handles[1].y = (oy + rect[3] - half).toFloat()
    }

    private fun handleResize(ev: MotionEvent): Boolean {
        val r = resize ?: return false
        val g = grids[r.page]
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                r.corner = 0
                for ((i, h) in r.handles.withIndex()) {
                    val l = IntArray(2); h.getLocationOnScreen(l)
                    if (hypot(ev.rawX - (l[0] + h.width / 2), ev.rawY - (l[1] + h.height / 2)) < dp(36)) r.corner = i + 1
                }
                if (r.corner == 0) { endResize(); return true }
            }
            MotionEvent.ACTION_MOVE -> if (r.corner != 0) {
                val gl = IntArray(2); g.getLocationOnScreen(gl)
                val cx = ((ev.rawX - gl[0] - g.paddingLeft) / g.cellW).roundToInt()
                val cy = ((ev.rawY - gl[1] - g.paddingTop) / g.cellH).roundToInt()
                val it = r.item
                val (mw, mh) = minSizeOf(it)
                val p = if (r.corner == 2) {
                    it.copy(w = (cx - it.x).coerceIn(mw, HomeGrid.COLS - it.x), h = (cy - it.y).coerceIn(mh, HomeGrid.ROWS - it.y))
                } else {
                    val right = it.x + it.w; val bottom = it.y + it.h
                    val nx = cx.coerceIn(0, right - mw); val ny = cy.coerceIn(0, bottom - mh)
                    it.copy(x = nx, y = ny, w = right - nx, h = bottom - ny)
                }
                r.preview = p
                r.ok = HomeGrid.isFree(HomeGrid.items(this, r.page), p.x, p.y, p.w, p.h, it)
                showResize()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (r.corner != 0) {
                if (r.ok && r.preview != r.item) {
                    HomeGrid.save(this, r.page, HomeGrid.items(this, r.page).map { if (it == r.item) r.preview else it })
                    r.item = r.preview
                    renderGrids()
                } else r.preview = r.item
                r.ok = true
                r.corner = 0
                root.post { showResize() }
            }
        }
        return true
    }

    private fun endResize() {
        val r = resize ?: return
        resize = null
        r.handles.forEach { overlay.removeView(it) }
        grids.forEach { it.hideOutline() }
        pager.isUserInputEnabled = true
    }

    // =====================================================================
    // 손가락 처리 (끌기 > 크기 조절 > 쓸기)
    private fun drawerHeight(): Float = (if (drawer.height > 0) drawer.height else window.decorView.height).toFloat()

    /** 손가락 아래에 위아래로 스크롤할 수 있는 게 있는지 (할 일 목록 등) */
    private fun canScrollAt(v: View, rawX: Float, rawY: Float, dir: Int): Boolean {
        val r = android.graphics.Rect()
        if (!v.isShown || !v.getGlobalVisibleRect(r) || !r.contains(rawX.toInt(), rawY.toInt())) return false
        if (v.canScrollVertically(dir)) return true
        if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) if (canScrollAt(v.getChildAt(i), rawX, rawY, dir)) return true
        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX; lastRawY = ev.rawY
        if (drag != null) return handleDrag(ev)
        if (ddrag != null) return handleDrawerDrag(ev)
        if (resize != null) return handleResize(ev)
        if (openFolder != null) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; decided = false; dragMode = 0
                vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev)
                startInDock = if (dockRow.visibility == View.VISIBLE) {
                    val loc = IntArray(2); dockRow.getLocationOnScreen(loc); ev.rawY >= loc[1] - dp(40)
                } else false
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!decided) {
                    if (abs(dy) > touchSlop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                        decided = true
                        grids.forEach { it.cancelLongPress2() }
                        if (drawer.visibility != View.VISIBLE) {
                            val g: View = when (pager.currentItem) { 0 -> grids[0]; SCENE -> scenePage; else -> page3 }
                            if (dy < 0 && (startInDock || !canScrollAt(g, downX, downY, 1))) {
                                dragMode = 1
                                prepareDrawer()
                                setDrawerPos(drawerHeight())
                                drawer.visibility = View.VISIBLE
                            } else if (dy > 0 && !canScrollAt(g, downX, downY, -1)) {
                                dragMode = 3
                                expandNotifications()
                            }
                        } else if (dy > 0 && !appList.canScrollVertically(-1)) {
                            dragMode = 2
                            hideKeyboard()
                        } else if (dy < 0 && !appList.canScrollVertically(1)) {
                            // 서랍에서 한 번 더 위로 쓸면 홈 화면으로 (서랍이 위로 빠져나감)
                            dragMode = 4
                            hideKeyboard()
                        }
                        if (dragMode != 0) {
                            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(c); c.recycle()
                        }
                    } else if (abs(dx) > touchSlop * 1.5f) {
                        decided = true
                    }
                }
                val h = drawerHeight()
                when (dragMode) {
                    1 -> { setDrawerPos((h + dy).coerceIn(0f, h)); return true }
                    2 -> { setDrawerPos(dy.coerceIn(0f, h)); return true }
                    4 -> { setDrawerPos(dy.coerceIn(-h, 0f)); return true }
                    3 -> return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                if (dragMode == 1 || dragMode == 2) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val open = when { vy < -700 -> true; vy > 700 -> false; else -> drawerPos < drawerHeight() / 2 }
                    settleDrawer(open)
                    dragMode = 0
                    return true
                }
                if (dragMode == 4) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val close = vy < -700 || (vy < 700 && drawerPos < -drawerHeight() / 4)
                    settleDrawer(!close, up = true)
                    dragMode = 0
                    return true
                }
                if (dragMode == 3) { dragMode = 0; return true }
                dragMode = 0
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // =====================================================================
    // 앱 목록 · 고정줄
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val col = Collator.getInstance(Locale.KOREAN)
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != packageName || it.activityInfo.name == SettingsActivity::class.java.name }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    val cat = if (Build.VERSION.SDK_INT >= 26) it.activityInfo.applicationInfo.category else -1
                    AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, try { it.loadIcon(pm) } catch (_: Exception) { null }, cat)
                }
                .sortedWith { a, b -> col.compare(a.label, b.label) }
                .let { l ->
                    // 앱 서랍에 '쿠비스 설정'이 늘 보이게 (런처 목록에 없으면 직접 넣음)
                    if (l.any { it.pkg == packageName }) l.map { if (it.pkg == packageName) AppEntry("쿠비스 설정", packageName, it.icon, it.category) else it }
                    else (l + AppEntry("쿠비스 설정", packageName, try { pm.getApplicationIcon(packageName) } catch (_: Exception) { null }))
                        .sortedWith { a, b -> col.compare(a.label, b.label) }
                }
            runOnUiThread {
                apps = list
                HomeGrid.prune(this, list.map { it.pkg }.toSet())
                refreshAll()
            }
        }.start()
    }

    private fun visibleApps(): List<AppEntry> {
        val h = store.hiddenApps()
        // 게이밍 허브가 있으면 게임은 거기서만 보이게 (삼성 홈처럼)
        val hideGames = Prefs(this).p.getBoolean("hideGames", true) && apps.any { it.pkg == GAME_HUB }
        return apps.filter { it.pkg !in h && !(hideGames && it.pkg != GAME_HUB && isGame(it)) }
    }

    private fun isGame(a: AppEntry): Boolean {
        if (a.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) return true
        return try {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(a.pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_IS_GAME != 0
        } catch (_: Exception) { false }
    }

    private fun defaultDock(): List<String> {
        val pm = packageManager
        fun resolve(i: Intent) = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName?.takeIf { it != "android" }
        val have = apps.map { it.pkg }.toSet()
        val bible = apps.firstOrNull { it.label.contains("성경") }?.pkg
        val camera = resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        val phone = resolve(Intent(Intent.ACTION_DIAL))
        // 다누 그림 자리 순서: 안긴 토끼, 강아지, 오리, 갈색 토끼, 아래 흰 토끼 (없는 앱은 빈자리로 둬서 순서 유지)
        return listOf(bible, phone, "com.nhn.android.nmap", camera, "com.anthropic.claude").map { if (it != null && it in have) it else "" }
    }

    private fun drawDock() {
        if (apps.isEmpty()) return
        var pkgs = store.dock()
        if (pkgs == null || store.rawGet("dockV2") == null) {
            // 한 번만: 그림 속 대상에 맞춰 고정줄 순서를 새로 (성경·전화·네이버 지도·카메라·Claude)
            store.rawPut("dockV2", "1")
            pkgs = defaultDock(); store.setDock(pkgs)
        }
        dockRow.removeAllViews()
        for (pkg in pkgs) {
            val app = apps.firstOrNull { it.pkg == pkg } ?: continue
            dockRow.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { launch(app.pkg) }
                setOnLongClickListener { appMenu(app); true }
            }, LinearLayout.LayoutParams(0, dp(68), 1f))
        }
    }

    // ---------- 다누 그림 ----------
    /** 그림 속 대상 i번 = 고정줄 i번째 앱 */
    fun sceneApp(i: Int): String? {
        val d = store.dock() ?: defaultDock()
        return d.getOrNull(i)?.takeIf { p -> p.isNotEmpty() && apps.any { it.pkg == p } }
    }

    fun pickSceneApp(i: Int) {
        if (apps.isEmpty()) return
        val list = apps.sortedWith(compareBy(Collator.getInstance(Locale.KOREAN)) { it.label })
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${SceneSpots.names[i]}를 누르면 열 앱")
            .setItems(list.map { it.label }.toTypedArray()) { _, k ->
                val pkg = list[k].pkg
                val d = (store.dock() ?: defaultDock()).toMutableList()
                while (d.size <= i) d.add("")
                val old = d[i]
                val at = d.indexOf(pkg)
                if (at >= 0 && at != i) d[at] = old   // 다른 자리에 있던 앱이면 서로 바꿈
                d[i] = pkg
                store.setDock(d.take(5))
                drawDock()
                toast("${SceneSpots.names[i]} → ${list[k].label}")
            }.show()
    }

    private val sceneTick = object : Runnable {
        override fun run() { updateScene(); main.postDelayed(this, 60_000) }
    }

    /** 날씨·시간에 맞춰 그림 바꾸기 */
    private fun updateScene() {
        val k = SceneSpots.key(lastWeather)
        if (k != sceneBg.key) {
            sceneBg.animate().cancel()
            sceneBg.animate().alpha(0.35f).setDuration(220).withEndAction {
                sceneBg.setKey(k)
                sceneBg.animate().alpha(1f).setDuration(380).start()
                wallLight = null; barsDark = null
                if (drawer.visibility != View.VISIBLE) setBarIconsDark(wallpaperIsLight())
            }.start()
        }
    }

    /** 고정줄 몇 번째에 넣을지 묻기 (others: 이 앱을 뺀 지금 고정줄) */
    private fun askDockPosition(app: AppEntry, others: List<String>) {
        fun name(p: String) = apps.firstOrNull { it.pkg == p }?.label ?: p
        val choices = (0..others.size).map { i ->
            "${i + 1}번째" + when {
                others.isEmpty() -> ""
                i == 0 -> "  (맨 왼쪽, ${name(others[0])} 앞)"
                i == others.size -> "  (맨 오른쪽, ${name(others.last())} 뒤)"
                else -> "  (${name(others[i - 1])}와 ${name(others[i])} 사이)"
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("${app.label} → 고정줄 몇 번째에 둘까요?")
            .setItems(choices.toTypedArray()) { _, i ->
                store.setDock(others.toMutableList().apply { add(i, app.pkg) })
                drawDock()
            }.show()
    }

    fun launch(pkg: String) {
        if (pkg == packageName) { SettingsActivity.open(this); return }
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        safeStart(i)
    }

    private fun hideApp(pkg: String) {
        store.setHidden(pkg, true)
        store.dock()?.let { d -> if (pkg in d) store.setDock(d - pkg) }
        for (p in 0 until HomeGrid.PAGES) HomeGrid.save(this, p, HomeGrid.items(this, p).filter { !(it.kind == "app" && it.pkg == pkg) })
        refreshAll()
        val label = apps.firstOrNull { it.pkg == pkg }?.label ?: pkg
        Toast.makeText(this, "$label 을(를) 숨겼어요. 쿠비스 설정 > 숨긴 앱에서 다시 보이게 할 수 있어요.", Toast.LENGTH_LONG).show()
    }

    /** 앱을 길게 누르고 안 움직이고 떼면 나오는 메뉴 (서랍·폴더·고정줄) */
    private fun appMenu(app: AppEntry) {
        val dock = store.dock() ?: emptyList()
        val items = mutableListOf("홈 화면에 추가")
        if (app.pkg in dock) { items.add("고정줄에서 빼기"); if (dock.size > 1) items.add("고정줄 자리 옮기기") }
        else items.add("고정줄에 넣기")
        items.add("폴더 옮기기"); items.add("숨기기"); items.add("앱 정보"); items.add("앱 삭제")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items.toTypedArray()) { _, w ->
                when (items[w]) {
                    "홈 화면에 추가" -> { closeDrawer(); addAppToHome(0, app.pkg, null) }
                    "고정줄에서 빼기" -> { store.setDock(dock - app.pkg); drawDock() }
                    "고정줄에 넣기" -> {
                        if (dock.size >= 5) toast("고정줄은 5개까지예요. 하나를 먼저 빼 주세요.")
                        else askDockPosition(app, dock)
                    }
                    "고정줄 자리 옮기기" -> askDockPosition(app, dock - app.pkg)
                    "폴더 옮기기" -> {
                        val order = store.folderOrder()
                        val choices = order + "폴더 밖 (맨 아래)"
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("${app.label} → 어느 폴더로?")
                            .setItems(choices.toTypedArray()) { _, f ->
                                store.moveToFolder(app.pkg, if (f < order.size) order[f] else Folders.TOP)
                                refreshAll()
                            }.show()
                    }
                    "숨기기" -> hideApp(app.pkg)
                    "앱 정보" -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                    "앱 삭제" -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            }.show()
    }

    // =====================================================================
    // 앱 서랍 (삼성식: 흐린 배경, 아래 검색창, 폴더 창)
    private fun blurSupported(): Boolean =
        Build.VERSION.SDK_INT >= 31 && (getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true)

    /** 서랍 위치에 맞춰 홈 흐리기·배경 블러 */
    private var drawerPos = 0f
    private fun setDrawerPos(ty: Float) {
        drawerPos = ty
        val frac = (1f - abs(ty) / drawerHeight()).coerceIn(0f, 1f)
        // 밀려 올라오는 대신 제자리에서 서서히 (손가락 방향으로 아주 조금만 움직임)
        drawer.alpha = frac
        drawer.translationY = ty * 0.06f
        mainContent.alpha = 1f - frac
        if (Build.VERSION.SDK_INT >= 31 && blurSupported()) window.setBackgroundBlurRadius((frac * 70).toInt())
        // 위 상태 표시줄·아래 내비게이션 줄도 서랍과 같은 색으로 (안 그러면 배경화면이 그대로 보임)
        val base = drawerTint()
        val bar = Color.argb((Color.alpha(base) * frac).toInt(), Color.red(base), Color.green(base), Color.blue(base))
        window.statusBarColor = bar
        window.navigationBarColor = bar
        setBarIconsDark(frac < 0.5f && wallpaperIsLight())
    }

    /** 배경화면이 밝으면 상태 표시줄 글씨(시간·배터리)를 어둡게 */
    private var wallLight: Boolean? = null
    private fun wallpaperIsLight(): Boolean {
        if (::sceneBg.isInitialized) return sceneBg.topIsLight()
        wallLight?.let { return it }
        val v = try {
            if (Build.VERSION.SDK_INT >= 27) {
                val wc = android.app.WallpaperManager.getInstance(this).getWallpaperColors(android.app.WallpaperManager.FLAG_SYSTEM)
                wc != null && (wc.colorHints and android.app.WallpaperColors.HINT_SUPPORTS_DARK_TEXT) != 0
            } else false
        } catch (_: Exception) { false }
        wallLight = v
        return v
    }

    private var barsDark: Boolean? = null
    private fun setBarIconsDark(dark: Boolean) {
        if (barsDark == dark) return
        barsDark = dark
        if (Build.VERSION.SDK_INT >= 30) {
            val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) m else 0, m)
        } else {
            @Suppress("DEPRECATION")
            var f = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            f = if (dark) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                else f and (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = f
        }
    }

    private fun drawerTint(): Int = if (blurSupported()) Color.argb(70, 0, 0, 0) else Color.argb(215, 12, 14, 20)

    private fun buildDrawer(): FrameLayout {
        val d = FrameLayout(this).apply {
            setBackgroundColor(drawerTint())
            isClickable = true
        }
        drawerColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 위: ⋮ 메뉴
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(8), dp(8), 0) }
        top.addView(txt("⋮", 24f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setPadding(dp(14), dp(4), dp(14), dp(4)); setOnClickListener { drawerMenu() }
        })
        drawerColumn.addView(top)
        appList = RecyclerView(this).apply {
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            layoutManager = GridLayoutManager(this@HomeActivity, 4)
            adapter = appAdapter
            setPadding(dp(8), dp(4), dp(8), dp(8)); clipToPadding = false
        }
        drawerColumn.addView(appList, LinearLayout.LayoutParams(MATCH, 0, 1f))
        // 아래: 둥근 검색창 + 마이크
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.argb(110, 60, 60, 66), dpf(28f))
            setPadding(dp(18), dp(4), dp(8), dp(4))
        }
        pill.addView(txt("🔍", 16f, Color.WHITE))
        search = EditText(this).apply {
            hint = "검색"; setHintTextColor(Color.argb(200, 230, 230, 235)); setTextColor(Color.WHITE); textSize = 17f
            background = null; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { appAdapter.filter(s?.toString() ?: "") }
            })
        }
        pill.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        pill.addView(txt("🎙", 18f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "찾을 앱 이름을 말씀하세요"), 51)
                } catch (_: Exception) { toast("음성 검색을 열 수 없어요.") }
            }
        })
        drawerColumn.addView(pill, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(20), dp(6), dp(20), dp(14)) })
        d.addView(drawerColumn, FrameLayout.LayoutParams(MATCH, MATCH))

        // 폴더 창
        folderLayer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(90, 0, 0, 0))
            visibility = View.GONE
            setOnClickListener { closeFolder() } // 바깥 누르면 닫힘
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        folderTitle = txt("", 30f, Color.WHITE, bold = true, gravity = Gravity.CENTER).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setShadowLayer(dpf(4f), 0f, dpf(1f), Color.argb(160, 0, 0, 0))
            setOnClickListener { openFolder?.let { renameFolderDialog(it) } }
        }
        col.addView(folderTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        addRow.addView(txt("⊕", 34f, Color.WHITE, gravity = Gravity.CENTER).apply {
            setPadding(dp(14), 0, dp(14), dp(4)); setOnClickListener { openFolder?.let { addAppsToFolderDialog(it) } }
        })
        col.addView(addRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { rightMargin = dp(28) })
        val card = LinearLayout(this).apply {
            folderCard = this
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.argb(200, 58, 52, 52), dpf(34f))
            setPadding(dp(14), dp(18), dp(14), dp(18))
            isClickable = true // 카드 안을 눌러도 안 닫힘
        }
        folderList = RecyclerView(this).apply {
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
            layoutManager = GridLayoutManager(this@HomeActivity, 4)
            adapter = folderAdapter
        }
        card.addView(folderList, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { setMargins(dp(20), 0, dp(20), 0) })
        folderLayer.addView(col, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER_VERTICAL))
        d.addView(folderLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        return d
    }

    private fun drawerMenu() {
        val items = arrayOf("숨긴 앱 보기·되돌리기", "폴더를 처음부터 다시 자동 정리", "쿠비스 설정")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> startActivity(Intent(this, SettingsActivity::class.java).putExtra("section", "hidden").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    1 -> AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setMessage("모든 앱을 이름을 보고 폴더에 다시 나눌까요? 손으로 옮긴 것도 다시 정해져요.")
                        .setPositiveButton("다시 정리") { _, _ -> Folders.resortAll(this, apps); refreshAll() }
                        .setNegativeButton("취소", null).show()
                    2 -> SettingsActivity.open(this)
                }
            }.show()
    }

    private fun prepareDrawer() {
        closeFolder()
        search.setText("")
        appAdapter.filter("")
        appList.scrollToPosition(0)
    }

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        prepareDrawer()
        setDrawerPos(drawerHeight())
        drawer.visibility = View.VISIBLE
        settleDrawer(true)
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        hideKeyboard()
        closeFolder()
        settleDrawer(false)
    }

    /** 끌어서 홈에 놓을 때: 애니메이션 없이 바로 숨김 */
    private fun hideDrawerNow() {
        hideKeyboard()
        closeFolder()
        drawer.animate().cancel()
        drawer.visibility = View.GONE
        setDrawerPos(drawerHeight())
    }

    private fun settleDrawer(open: Boolean, up: Boolean = false) {
        val from = drawerPos
        val to = if (open) 0f else if (up) -drawerHeight() else drawerHeight()
        drawer.visibility = View.VISIBLE
        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = if (open) 200 else 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { setDrawerPos(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!open) drawer.visibility = View.GONE
                }
            })
        }.start()
    }

    private fun hideKeyboard() {
        if (::search.isInitialized)
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ---------- 폴더 창 ----------
    private fun openFolderPopup(name: String) {
        openFolder = name
        folderTitle.text = name
        folderAdapter.showFolder(name)
        hideKeyboard()
        if (Build.VERSION.SDK_INT >= 31) drawerColumn.setRenderEffect(RenderEffect.createBlurEffect(36f, 36f, Shader.TileMode.CLAMP))
        else drawerColumn.alpha = 0.25f
        folderLayer.alpha = 0f
        folderLayer.visibility = View.VISIBLE
        folderLayer.animate().alpha(1f).setDuration(160).start()
    }

    private fun closeFolder() {
        if (openFolder == null) return
        openFolder = null
        if (Build.VERSION.SDK_INT >= 31) drawerColumn.setRenderEffect(null) else drawerColumn.alpha = 1f
        folderLayer.visibility = View.GONE
        appAdapter.filter(search.text.toString())
    }

    private fun renameFolderDialog(name: String) {
        val et = EditText(this).apply { setText(name); setSelection(name.length) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("폴더 이름 바꾸기")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("바꾸기") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && n != name && n !in store.folderOrder()) {
                    store.renameFolder(name, n)
                    if (openFolder == name) { openFolder = n; folderTitle.text = n }
                }
                refreshAll()
            }
            .setNegativeButton("취소", null).show()
    }

    private fun addAppsToFolderDialog(name: String) {
        val assign = Folders.assign(this, apps)
        val cands = visibleApps().filter { assign[it.pkg] != name }
        val checked = BooleanArray(cands.size)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("'$name'에 넣을 앱")
            .setMultiChoiceItems(cands.map { it.label }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("넣기") { _, _ ->
                cands.forEachIndexed { i, a -> if (checked[i]) store.moveToFolder(a.pkg, name) }
                folderAdapter.showFolder(name); appAdapter.filter(search.text.toString())
            }
            .setNegativeButton("취소", null).show()
    }

    // ---------- 서랍·폴더 안에서 끌어 옮기기 ----------
    private class DrawerDrag(
        val cell: Cell, val float: ImageView, val grabX: Float, val grabY: Float,
        val startX: Float, val startY: Float, var folder: String?, val onTap: () -> Unit
    ) {
        var moved = false
        var cancelSent = false
        var hover: String? = null
        var zoneSince = 0L
        var outSince = 0L
        var lastMove = 0L
    }

    private var ddrag: DrawerDrag? = null

    private val autoScroll = object : Runnable {
        override fun run() {
            val d = ddrag ?: return
            if (d.moved && d.folder == null) {
                val loc = IntArray(2); appList.getLocationOnScreen(loc)
                val ly = lastRawY - loc[1]
                val edge = dp(64)
                val dy = when {
                    ly < edge && ly > -dp(10) -> -dp(12)
                    ly > appList.height - edge -> dp(12)
                    else -> 0
                }
                if (dy != 0 && appList.canScrollVertically(if (dy < 0) -1 else 1)) {
                    appList.scrollBy(0, dy)
                    updateDrawerTarget(lastRawX, lastRawY)
                }
            }
            main.postDelayed(this, 16)
        }
    }

    private fun inView(v: View, rawX: Float, rawY: Float): Boolean {
        if (!v.isShown) return false
        val l = IntArray(2); v.getLocationOnScreen(l)
        return rawX >= l[0] && rawX <= l[0] + v.width && rawY >= l[1] && rawY <= l[1] + v.height
    }

    private fun placeFloat(v: View, grabX: Float, grabY: Float, rawX: Float, rawY: Float) {
        val loc = IntArray(2); overlay.getLocationOnScreen(loc)
        v.x = rawX - grabX - loc[0]; v.y = rawY - grabY - loc[1]
    }

    /** 서랍·폴더 칸을 길게 누름 → 그 안에서 끌어 옮기기 (안 움직이고 떼면 메뉴) */
    private fun startDrawerDrag(h: AppAdapter.VH, cell: Cell) {
        val tap: () -> Unit = { if (cell.app != null) appMenu(cell.app) else cell.folder?.let { renameFolderDialog(it) } }
        if (search.text.isNotEmpty()) { tap(); return } // 검색 중엔 옮기기 없이 메뉴
        val box = h.box
        val loc = IntArray(2); box.getLocationOnScreen(loc)
        val iv = ImageView(this).apply {
            setImageBitmap(snapshot(box)); alpha = 0f; scaleX = 1.1f; scaleY = 1.1f; elevation = dpf(12f)
        }
        overlay.addView(iv, FrameLayout.LayoutParams(box.width, box.height))
        val d = DrawerDrag(cell, iv, lastRawX - loc[0], lastRawY - loc[1], lastRawX, lastRawY, openFolder, tap)
        ddrag = d
        placeFloat(iv, d.grabX, d.grabY, lastRawX, lastRawY)
    }

    private fun handleDrawerDrag(ev: MotionEvent): Boolean {
        val d = ddrag ?: return false
        if (!d.cancelSent) {
            d.cancelSent = true
            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(c); c.recycle()
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!d.moved && hypot(ev.rawX - d.startX, ev.rawY - d.startY) > touchSlop * 2) {
                    d.moved = true
                    d.float.alpha = 0.95f
                    adapterOf(d).setDragging(d.cell.key)
                    if (d.cell.app != null) { homeZone.alpha = 0.8f; homeZone.visibility = View.VISIBLE }
                    main.post(autoScroll)
                }
                if (d.moved) {
                    placeFloat(d.float, d.grabX, d.grabY, ev.rawX, ev.rawY)
                    updateDrawerTarget(ev.rawX, ev.rawY)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!d.moved) { endDrawerDrag(); if (ev.actionMasked == MotionEvent.ACTION_UP) d.onTap() }
                else dropDrawer()
            }
        }
        return true
    }

    private fun adapterOf(d: DrawerDrag) = if (d.folder != null) folderAdapter else appAdapter

    private fun updateDrawerTarget(rawX: Float, rawY: Float) {
        val d = ddrag ?: return
        val now = System.currentTimeMillis()
        // 위쪽 '홈 화면으로' 칸에 잠깐 대면 홈으로 넘어감
        if (d.cell.app != null && inView(homeZone, rawX, rawY)) {
            homeZone.alpha = 1f
            if (d.zoneSince == 0L) d.zoneSince = now
            if (now - d.zoneSince > 350) toHomeDrag()
            return
        }
        d.zoneSince = 0L
        homeZone.alpha = 0.8f
        // 폴더 창 밖으로 끌면 폴더에서 꺼냄
        if (d.folder != null) {
            if (!inView(folderCard, rawX, rawY)) {
                if (d.outSince == 0L) d.outSince = now
                if (now - d.outSince > 250) pullOutOfFolder()
                return
            }
            d.outSince = 0L
        }
        val adapter = adapterOf(d)
        val rv = if (d.folder != null) folderList else appList
        val loc = IntArray(2); rv.getLocationOnScreen(loc)
        val lx = rawX - loc[0]; val ly = rawY - loc[1]
        val child = rv.findChildViewUnder(lx, ly)
        var pos = if (child != null) rv.getChildAdapterPosition(child) else RecyclerView.NO_POSITION
        var hover: String? = null
        if (child != null && pos != RecyclerView.NO_POSITION) {
            val target = adapter.cellAt(pos)
            if (d.folder == null && d.cell.app != null && target.folder != null) {
                val vh = rv.getChildViewHolder(child) as AppAdapter.VH
                val il = IntArray(2); vh.icon.getLocationOnScreen(il)
                val m = vh.icon.width * 0.1f
                if (rawX > il[0] - m && rawX < il[0] + vh.icon.width + m && rawY > il[1] - m && rawY < il[1] + vh.icon.height + m) hover = target.folder
            }
        } else if (child == null && rv.childCount > 0 && ly > rv.getChildAt(rv.childCount - 1).bottom) {
            pos = adapter.itemCount - 1
        }
        d.hover = hover
        adapter.setHover(hover)
        if (hover == null && pos != RecyclerView.NO_POSITION && now - d.lastMove > 120) {
            val from = adapter.indexOfKey(d.cell.key)
            if (from >= 0 && from != pos) {
                d.lastMove = now
                val lm = rv.layoutManager as GridLayoutManager
                val first = lm.findFirstVisibleItemPosition()
                val top = (rv.getChildAt(0)?.top ?: 0) - rv.paddingTop
                adapter.move(from, pos)
                if (first == from || first == pos) lm.scrollToPositionWithOffset(first, top)
            }
        }
    }

    /** 폴더 창 밖으로 끌어냄 → 폴더 밖 앱이 되고, 서랍에서 계속 끌기 */
    private fun pullOutOfFolder() {
        val d = ddrag ?: return
        val app = d.cell.app ?: return
        val f = d.folder ?: return
        store.setFolderAppOrder(f, folderAdapter.keys().map { it.removePrefix("a:") }.filter { it != app.pkg })
        store.moveToFolder(app.pkg, Folders.TOP)
        d.folder = null
        d.outSince = 0L
        closeFolder()
        appAdapter.setDragging(d.cell.key)
    }

    /** 서랍에서 끌던 앱을 홈 화면 끌기로 바꿈 */
    private fun toHomeDrag() {
        val d = ddrag ?: return
        val app = d.cell.app ?: return
        val bmp = (d.float.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        val w = d.float.width; val h = d.float.height
        if (d.folder != null) store.setFolderAppOrder(d.folder!!, folderAdapter.keys().map { it.removePrefix("a:") })
        else store.setDrawerOrder(appAdapter.keys())
        endDrawerDrag()
        hideDrawerNow()
        startDrag(GridItem("app", app.pkg, 0, 0, 1, 1), null, bmp, w, h, d.grabX, d.grabY, lastRawX, lastRawY, null, icon = app.icon) {}
        drag?.let { it.moved = true; it.cancelSent = true; it.float.alpha = 0.9f }
        updateTarget(lastRawX, lastRawY)
    }

    private fun dropDrawer() {
        val d = ddrag ?: return
        val hv = d.hover
        val app = d.cell.app
        if (hv != null && app != null) {
            store.moveToFolder(app.pkg, hv)
            store.setDrawerOrder(appAdapter.keys().filter { it != d.cell.key })
            toast("'$hv' 폴더에 넣었어요.")
        } else if (d.folder != null) {
            store.setFolderAppOrder(d.folder!!, folderAdapter.keys().map { it.removePrefix("a:") })
        } else {
            store.setDrawerOrder(appAdapter.keys())
        }
        endDrawerDrag()
    }

    private fun endDrawerDrag() {
        val d = ddrag ?: return
        ddrag = null
        main.removeCallbacks(autoScroll)
        overlay.removeView(d.float)
        homeZone.visibility = View.GONE
        appAdapter.setHover(null); folderAdapter.setHover(null)
        appAdapter.draggingKey = null; folderAdapter.draggingKey = null
        appAdapter.filter(search.text.toString())
        openFolder?.let { folderAdapter.showFolder(it) }
    }

    /** 서랍 칸: 폴더 또는 앱 */
    private class Cell(val folder: String?, val apps: List<AppEntry>, val app: AppEntry?) {
        val key: String get() = if (app != null) "a:${app.pkg}" else "f:$folder"
    }

    private inner class AppAdapter(private val inFolder: Boolean) : RecyclerView.Adapter<AppAdapter.VH>() {
        private var shown: MutableList<Cell> = mutableListOf()
        var draggingKey: String? = null
        private var hoverFolder: String? = null

        inner class VH(val box: LinearLayout, val icon: FrameLayout, val label: TextView) : RecyclerView.ViewHolder(box)

        fun keys() = shown.map { it.key }
        fun cellAt(i: Int) = shown[i]
        fun indexOfKey(k: String) = shown.indexOfFirst { it.key == k }
        fun move(from: Int, to: Int) { val c = shown.removeAt(from); shown.add(to, c); notifyItemMoved(from, to) }

        fun setDragging(k: String?) {
            val old = draggingKey; draggingKey = k
            listOfNotNull(old, k).forEach { val i = indexOfKey(it); if (i >= 0) notifyItemChanged(i) }
        }

        fun setHover(f: String?) {
            if (f == hoverFolder) return
            val old = hoverFolder; hoverFolder = f
            listOfNotNull(old, f).forEach { val i = indexOfKey("f:$it"); if (i >= 0) notifyItemChanged(i) }
        }

        /** 서랍: 검색어가 있으면 결과, 없으면 폴더 + 폴더 밖 앱 (손으로 옮긴 순서대로) */
        fun filter(q: String) {
            if (inFolder) return
            val k = q.replace(" ", "").lowercase()
            val vis = visibleApps()
            val assign = if (apps.isEmpty()) emptyMap() else Folders.assign(this@HomeActivity, apps)
            shown = if (k.isNotEmpty()) vis.filter { it.label.replace(" ", "").lowercase().contains(k) }.map { Cell(null, emptyList(), it) }.toMutableList()
            else {
                val base = store.folderOrder().map { f -> Cell(f, orderBy(vis.filter { assign[it.pkg] == f }, store.folderAppOrder(f)) { it.pkg }, null) } +
                        vis.filter { assign[it.pkg] == Folders.TOP }.map { Cell(null, emptyList(), it) }
                orderBy(base, store.drawerOrder()) { it.key }.toMutableList()
            }
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        fun showFolder(name: String) {
            val assign = Folders.assign(this@HomeActivity, apps)
            shown = orderBy(visibleApps().filter { assign[it.pkg] == name }, store.folderAppOrder(name)) { it.pkg }
                .map { Cell(null, emptyList(), it) }.toMutableList()
            @Suppress("NotifyDataSetChanged") notifyDataSetChanged()
        }

        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val box = LinearLayout(this@HomeActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(10), dp(4), dp(10))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            val icon = FrameLayout(this@HomeActivity)
            box.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
            val label = TextView(this@HomeActivity).apply {
                textSize = 12.5f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(dpf(3f), 0f, dpf(1f), Color.argb(170, 0, 0, 0))
                setPadding(0, dp(5), 0, 0)
            }
            box.addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
            val vh = VH(box, icon, label)
            box.setOnLongClickListener {
                val p = vh.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) startDrawerDrag(vh, shown[p])
                true
            }
            return vh
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = shown[position]
            h.box.alpha = if (c.key == draggingKey) 0f else 1f
            h.icon.removeAllViews()
            h.icon.scaleX = 1f; h.icon.scaleY = 1f
            val app = c.app
            if (app != null) {
                h.icon.background = null
                h.icon.addView(ImageView(this@HomeActivity).apply { setImageDrawable(app.icon) }, FrameLayout.LayoutParams(MATCH, MATCH))
                h.label.text = app.label
                h.box.setOnClickListener { launch(app.pkg); closeDrawer() }
            } else {
                val name = c.folder ?: ""
                val hov = name == hoverFolder
                h.icon.background = rounded(Color.argb(if (hov) 170 else 95, 255, 255, 255), dpf(18f))
                if (hov) { h.icon.scaleX = 1.15f; h.icon.scaleY = 1.15f }
                val grid = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
                for (r in 0 until 2) {
                    val row = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    for (col in 0 until 2) {
                        row.addView(ImageView(this@HomeActivity).apply {
                            setImageDrawable(c.apps.getOrNull(r * 2 + col)?.icon?.constantState?.newDrawable()?.mutate())
                            setPadding(dp(2), dp(2), dp(2), dp(2))
                        }, LinearLayout.LayoutParams(0, dp(22), 1f))
                    }
                    grid.addView(row)
                }
                h.icon.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
                h.label.text = name
                h.box.setOnClickListener { openFolderPopup(name) }
            }
        }
    }
}

/** 페이지들을 그대로 끼워 넣는 어댑터 */
class PagesAdapter(private val views: List<View>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = views.size
    override fun getItemViewType(position: Int) = position
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val v = views[viewType]
        (v.parent as? ViewGroup)?.removeView(v)
        v.layoutParams = RecyclerView.LayoutParams(MATCH, MATCH)
        return object : RecyclerView.ViewHolder(v) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}
