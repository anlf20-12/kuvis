package com.t.kuvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.text.Collator
import java.util.Locale
import kotlin.math.abs

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class AppEntry(val label: String, val pkg: String, val icon: Drawable?, val category: Int = -1)

/** 쿠비스 홈 화면(런처) */
class HomeActivity : Activity() {

    private lateinit var store: Store
    private lateinit var pager: ViewPager2
    private lateinit var calendarPage: CalendarPage
    private lateinit var centerPage: CenterPage
    private lateinit var memoPage: MemoPage
    private lateinit var dots: LinearLayout
    private lateinit var dockRow: LinearLayout
    private lateinit var drawer: LinearLayout
    private lateinit var search: EditText
    private lateinit var appList: RecyclerView
    private val appAdapter = AppAdapter()
    private var apps: List<AppEntry> = emptyList()
    // 손가락 따라가는 쓸기
    private var touchSlop = 16
    private var downX = 0f
    private var downY = 0f
    private var decided = false
    private var dragMode = 0 // 0 없음, 1 앱 창 올리기, 2 앱 창 내리기, 3 알림창
    private var startInDock = false
    private var vt: android.view.VelocityTracker? = null

    private val onChanged: () -> Unit = { refreshAll() }
    private val onPage: (String) -> Unit = { p ->
        closeDrawer()
        pager.setCurrentItem(when (p) { "calendar" -> 0; "memo" -> 2; else -> 1 }, true)
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { loadApps() }
    }

    // =====================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)

        // 상태 표시줄·내비게이션 바 뒤까지 배경 사진이 보이게
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        val root = FrameLayout(this).apply { fitsSystemWindows = true }

        // 페이지 + 점 + 고정줄
        val main = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        calendarPage = CalendarPage(this)
        centerPage = CenterPage(this)
        memoPage = MemoPage(this)
        pager = ViewPager2(this).apply {
            adapter = PagesAdapter(listOf(calendarPage, centerPage, memoPage))
            offscreenPageLimit = 2
        }
        main.addView(pager, LinearLayout.LayoutParams(MATCH, 0, 1f))

        dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        main.addView(dots, LinearLayout.LayoutParams(MATCH, dp(18)))
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { drawDots(position) }
        })

        // 위로 쓸어 올리기 손잡이
        main.addView(txt("⌃", 16f, Color.argb(170, 255, 255, 255), gravity = Gravity.CENTER).apply {
            setOnClickListener { openDrawer() }
        }, LinearLayout.LayoutParams(MATCH, dp(18)))

        dockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(10))
        }
        main.addView(dockRow, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(main, FrameLayout.LayoutParams(MATCH, MATCH))

        // 전체 앱
        drawer = buildDrawer()
        drawer.visibility = View.GONE
        root.addView(drawer, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        pager.setCurrentItem(1, false)
        drawDots(1)

        touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        Bus.onChanged(onChanged)
        Bus.onPage(onPage)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(pkgReceiver, f)

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
        Backup.autoDaily(this)
        // 기억장 맞추기(30분마다) + 지난 날 일지 올리기
        val mp = Prefs(this).p
        if (MemorySync.enabled(this) && System.currentTimeMillis() - mp.getLong("lastMemPull", 0L) > 30 * 60_000L) {
            mp.edit().putLong("lastMemPull", System.currentTimeMillis()).apply()
            Thread {
                try { MemorySync.pullMemory(this) } catch (e: Exception) { ErrorLog.add(this, "기억장 받기 실패", e.toString()) }
                try { Journal.flush(this) } catch (e: Exception) { ErrorLog.add(this, "일지 실패", e.toString()) }
            }.start()
        }
        val pp = Prefs(this).p
        if (pp.getBoolean("crashedLastTime", false)) {
            pp.edit().putBoolean("crashedLastTime", false).apply()
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
            runOnUiThread { centerPage.setWeather(w) }
        }.start()
    }

    /** 홈 버튼을 다시 누르면 가운데로 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (drawer.visibility == View.VISIBLE) closeDrawer() else pager.setCurrentItem(1, true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE && openFolder != null && search.text.isBlank()) { openFolder = null; appAdapter.filter("") }
        else if (drawer.visibility == View.VISIBLE) closeDrawer() else pager.setCurrentItem(1, true)
        // 런처는 닫히지 않음
    }

    private fun currentPage(): View = listOf(calendarPage, centerPage, memoPage)[pager.currentItem]

    private fun drawerHeight(): Float = (if (drawer.height > 0) drawer.height else window.decorView.height).toFloat()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; decided = false; dragMode = 0
                vt?.recycle(); vt = android.view.VelocityTracker.obtain(); vt?.addMovement(ev)
                val loc = IntArray(2); dockRow.getLocationOnScreen(loc)
                startInDock = ev.rawY >= loc[1] - dp(40)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.rawX - downX
                val dy = ev.rawY - downY
                if (!decided) {
                    if (abs(dy) > touchSlop * 1.5f && abs(dy) > abs(dx) * 1.3f) {
                        decided = true
                        if (drawer.visibility != View.VISIBLE) {
                            val page = currentPage()
                            if (dy < 0 && (startInDock || !page.canScrollVertically(1))) {
                                dragMode = 1
                                prepareDrawer()
                                drawer.translationY = drawerHeight()
                                drawer.visibility = View.VISIBLE
                            } else if (dy > 0 && !page.canScrollVertically(-1)) {
                                dragMode = 3
                                expandNotifications()
                            }
                        } else if (dy > 0 && !appList.canScrollVertically(-1)) {
                            dragMode = 2
                            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                                .hideSoftInputFromWindow(search.windowToken, 0)
                        }
                        if (dragMode != 0) {
                            // 아래 화면(페이지 스크롤 등)이 이 손가락을 놓게 함
                            val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(c); c.recycle()
                        }
                    } else if (abs(dx) > touchSlop * 1.5f) {
                        decided = true // 옆으로 넘기기 → 페이지 이동
                    }
                }
                val h = drawerHeight()
                when (dragMode) {
                    1 -> { drawer.translationY = (h + dy).coerceIn(0f, h); return true }
                    2 -> { drawer.translationY = dy.coerceIn(0f, h); return true }
                    3 -> return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev)
                if (dragMode == 1 || dragMode == 2) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    val h = drawerHeight()
                    val open = when {
                        vy < -700 -> true
                        vy > 700 -> false
                        else -> drawer.translationY < h / 2
                    }
                    settleDrawer(open)
                    dragMode = 0
                    return true
                }
                if (dragMode == 3) { dragMode = 0; return true }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** 손을 뗀 뒤 부드럽게 끝까지 */
    private fun settleDrawer(open: Boolean) {
        val h = drawerHeight()
        if (open) {
            drawer.visibility = View.VISIBLE
            drawer.animate().translationY(0f).setDuration(180)
                .setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction(null).start()
        } else {
            drawer.animate().translationY(h).setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { drawer.visibility = View.GONE; openFolder = null }.start()
        }
    }

    private fun prepareDrawer() {
        openFolder = null
        search.setText("")
        appAdapter.filter("")
        appList.scrollToPosition(0)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshAll()
        Thread { val w = Weather.get(this, 0L); runOnUiThread { centerPage.setWeather(w) } }.start()
        if (Prefs(this).key.isBlank()) SettingsActivity.open(this)
    }

    // =====================================================================
    fun refreshAll() {
        if (::search.isInitialized) appAdapter.filter(search.text.toString())
        calendarPage.refresh()
        centerPage.refresh()
        memoPage.refresh()
        drawDock()
    }

    private fun drawDots(sel: Int) {
        dots.removeAllViews()
        for (i in 0 until 3) {
            dots.addView(View(this).apply {
                background = rounded(if (i == sel) Color.WHITE else Color.argb(110, 255, 255, 255), dpf(4f))
            }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { leftMargin = dp(4); rightMargin = dp(4) })
        }
    }

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
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
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

    // =====================================================================
    // 설정 메뉴 (가운데 페이지 ⚙)
    fun showMenu() {
        SettingsActivity.open(this)
    }

    @Suppress("unused")
    private fun oldMenu() {
        val items = arrayOf(
            "쿠비스 설정 (API 키·모델·목소리)",
            "쿠비스를 기본 홈 화면으로",
            "측면 버튼으로 부르기 설정",
            "할 일·메모 백업 내보내기",
            "휴대폰 설정 열기"
        )
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setItems(items) { _, w ->
                when (w) {
                    0 -> SettingsActivity.open(this)
                    1 -> setAsHome()
                    2 -> assistGuide()
                    3 -> safeStart(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "쿠비스 백업")
                            .putExtra(Intent.EXTRA_TEXT, store.exportJson()), "백업 보낼 곳"))
                    4 -> safeStart(Intent(Settings.ACTION_SETTINGS))
                }
            }.show()
    }

    private fun setAsHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
                return
            }
        }
        safeStart(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun assistGuide() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("측면 버튼으로 쿠비스 부르기")
            .setMessage(
                "1) 다음 화면의 '디지털 어시스턴트 앱'(또는 '기기 지원 앱')에서 쿠비스를 고르세요.\n\n" +
                "2) 휴대폰 설정 > 유용한 기능(고급 기능) > 측면 버튼 > '길게 누르기'를 '디지털 어시스턴트 깨우기'로 바꾸세요.\n\n" +
                "그러면 어느 화면에서든 측면 버튼을 길게 누르면 쿠비스가 떠요."
            )
            .setPositiveButton("1번 화면 열기") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
                catch (_: Exception) { safeStart(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            }
            .setNeutralButton("2번 화면 열기") { _, _ -> safeStart(Intent(Settings.ACTION_SETTINGS)) }
            .setNegativeButton("닫기", null)
            .show()
    }

    // =====================================================================
    // 앱 목록 · 고정줄
    private fun loadApps() {
        Thread {
            val pm = packageManager
            val col = Collator.getInstance(Locale.KOREAN)
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                // 쿠비스 자신은 '쿠비스 설정'만 보여 줌
                .filter { it.activityInfo.packageName != packageName || it.activityInfo.name == SettingsActivity::class.java.name }
                .distinctBy { it.activityInfo.packageName }
                .map {
                    val cat = if (Build.VERSION.SDK_INT >= 26) it.activityInfo.applicationInfo.category else -1
                    AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, try { it.loadIcon(pm) } catch (_: Exception) { null }, cat)
                }
                .sortedWith { a, b -> col.compare(a.label, b.label) }
            runOnUiThread {
                apps = list
                appAdapter.filter(search.text.toString())
                drawDock()
            }
        }.start()
    }

    private fun defaultDock(): List<String> {
        val pm = packageManager
        fun resolve(i: Intent) = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            ?.takeIf { it != "android" }
        val have = apps.map { it.pkg }.toSet()
        val bible = apps.firstOrNull { it.label.contains("성경") }?.pkg
        val camera = resolve(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
        val phone = resolve(Intent(Intent.ACTION_DIAL))
        return listOfNotNull(bible, camera, phone, "com.nhn.android.nmap", "com.naver.whale")
            .filter { it in have }.distinct()
    }

    private fun drawDock() {
        if (apps.isEmpty()) return
        var pkgs = store.dock()
        if (pkgs == null) { pkgs = defaultDock(); store.setDock(pkgs) }
        dockRow.removeAllViews()
        for (pkg in pkgs) {
            val app = apps.firstOrNull { it.pkg == pkg } ?: continue
            val iv = ImageView(this).apply {
                setImageDrawable(app.icon)
                contentDescription = app.label
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener { launch(app.pkg) }
                setOnLongClickListener { appMenu(app, inDock = true); true }
            }
            dockRow.addView(iv, LinearLayout.LayoutParams(0, dp(68), 1f))
        }
    }

    fun launch(pkg: String) {
        if (pkg == packageName) { SettingsActivity.open(this); return }
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        safeStart(i)
    }

    private fun appMenu(app: AppEntry, inDock: Boolean) {
        val dock = store.dock() ?: emptyList()
        val items = mutableListOf<String>()
        if (inDock || app.pkg in dock) items.add("고정줄에서 빼기") else items.add("고정줄에 넣기")
        items.add("폴더 옮기기"); items.add("숨기기"); items.add("앱 정보"); items.add("삭제")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(items.toTypedArray()) { _, w ->
                when (items[w]) {
                    "고정줄에서 빼기" -> { store.setDock(dock - app.pkg); drawDock() }
                    "고정줄에 넣기" -> {
                        if (dock.size >= 5) {
                            AlertDialog.Builder(this).setMessage("고정줄은 5개까지예요. 하나를 먼저 빼 주세요.")
                                .setPositiveButton("확인", null).show()
                        } else { store.setDock(dock + app.pkg); drawDock() }
                    }
                    "폴더 옮기기" -> {
                        val order = store.folderOrder()
                        val choices = order + "폴더 밖 (맨 아래)"
                        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("${app.label} → 어느 폴더로?")
                            .setItems(choices.toTypedArray()) { _, f ->
                                store.moveToFolder(app.pkg, if (f < order.size) order[f] else Folders.TOP)
                                appAdapter.filter(search.text.toString())
                            }
                            .show()
                    }
                    "숨기기" -> {
                        store.setHidden(app.pkg, true)
                        if (app.pkg in dock) store.setDock(dock - app.pkg)
                        appAdapter.filter(search.text.toString()); drawDock()
                        android.widget.Toast.makeText(this, "${app.label}을(를) 숨겼어요. 쿠비스 설정 > 숨긴 앱에서 다시 보이게 할 수 있어요.", android.widget.Toast.LENGTH_LONG).show()
                    }
                    "앱 정보" -> safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
                    "삭제" -> safeStart(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")))
                }
            }.show()
    }

    // ---------- 전체 앱 서랍 ----------
    private fun buildDrawer(): LinearLayout {
        val d = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(238, 5, 8, 15))
            setPadding(dp(12), dp(16), dp(12), 0)
            isClickable = true
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        search = EditText(this).apply {
            hint = "앱 검색"; setHintTextColor(C.dim); setTextColor(C.text); textSize = 16f
            background = rounded(C.panel, dpf(14f), dp(1), C.line)
            setPadding(dp(14), dp(10), dp(14), dp(10)); isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { appAdapter.filter(s?.toString() ?: "") }
            })
        }
        top.addView(search, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(txt("닫기", 15f, C.text).apply {
            setPadding(dp(14), dp(10), dp(6), dp(10)); setOnClickListener { closeDrawer() }
        })
        d.addView(top)
        folderTitle = txt("", 18f, C.text, bold = true).apply {
            setPadding(dp(6), dp(14), 0, 0); visibility = View.GONE
            setOnClickListener { openFolder = null; appAdapter.filter("") }
        }
        d.addView(folderTitle)
        appList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@HomeActivity, 4)
            adapter = this@HomeActivity.appAdapter
            setPadding(0, dp(12), 0, dp(12)); clipToPadding = false
        }
        d.addView(appList, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return d
    }

    private fun openDrawer() {
        if (drawer.visibility == View.VISIBLE) return
        prepareDrawer()
        drawer.translationY = drawerHeight()
        drawer.visibility = View.VISIBLE
        settleDrawer(true)
    }

    private fun closeDrawer() {
        if (drawer.visibility != View.VISIBLE) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(search.windowToken, 0)
        settleDrawer(false)
    }

    private var openFolder: String? = null
    private lateinit var folderTitle: TextView

    private fun renameFolderDialog(name: String) {
        val et = EditText(this).apply { setText(name); setSelection(name.length) }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("폴더 이름 바꾸기")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("바꾸기") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty() && n !in store.folderOrder()) { store.renameFolder(name, n); if (openFolder == name) openFolder = n }
                appAdapter.filter(search.text.toString())
            }
            .setNegativeButton("취소", null).show()
    }

    /** 서랍 칸: 폴더 또는 앱 */
    private class Cell(val folder: String?, val apps: List<AppEntry>, val app: AppEntry?)

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.VH>() {
        private var shown: List<Cell> = emptyList()

        inner class VH(val box: LinearLayout, val icon: FrameLayout, val label: TextView) : RecyclerView.ViewHolder(box)

        fun filter(q: String) {
            val k = q.replace(" ", "").lowercase()
            val assign = if (apps.isEmpty()) emptyMap() else Folders.assign(this@HomeActivity, apps)
            val hidden = store.hiddenApps()
            val apps = apps.filter { it.pkg !in hidden }
            shown = when {
                k.isNotEmpty() -> apps.filter { it.label.replace(" ", "").lowercase().contains(k) }.map { Cell(null, emptyList(), it) }
                openFolder != null -> apps.filter { assign[it.pkg] == openFolder }.map { Cell(null, emptyList(), it) }
                else -> store.folderOrder().map { f -> Cell(f, apps.filter { assign[it.pkg] == f }, null) } +
                        apps.filter { assign[it.pkg] == Folders.TOP }.map { Cell(null, emptyList(), it) }
            }
            if (::folderTitle.isInitialized) {
                val f = openFolder
                folderTitle.visibility = if (f != null && k.isEmpty()) View.VISIBLE else View.GONE
                folderTitle.text = "‹  $f"
            }
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
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
                textSize = 12f; setTextColor(C.text); gravity = Gravity.CENTER
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            }
            box.addView(label, LinearLayout.LayoutParams(MATCH, WRAP))
            return VH(box, icon, label)
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = shown[position]
            h.icon.removeAllViews()
            val app = c.app
            if (app != null) {
                h.icon.background = null
                h.icon.addView(ImageView(this@HomeActivity).apply { setImageDrawable(app.icon) },
                    FrameLayout.LayoutParams(MATCH, MATCH))
                h.label.text = app.label
                h.box.setOnClickListener { launch(app.pkg); closeDrawer() }
                h.box.setOnLongClickListener { appMenu(app, inDock = false); true }
            } else {
                val name = c.folder ?: ""
                // 폴더: 앞의 앱 4개 아이콘을 2x2로
                h.icon.background = rounded(Color.argb(90, 255, 255, 255), dpf(16f))
                val grid = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
                for (r in 0 until 2) {
                    val row = LinearLayout(this@HomeActivity).apply { orientation = LinearLayout.HORIZONTAL }
                    for (col in 0 until 2) {
                        val iv = ImageView(this@HomeActivity).apply {
                            setImageDrawable(c.apps.getOrNull(r * 2 + col)?.icon?.constantState?.newDrawable()?.mutate())
                            setPadding(dp(2), dp(2), dp(2), dp(2))
                        }
                        row.addView(iv, LinearLayout.LayoutParams(0, dp(22), 1f))
                    }
                    grid.addView(row)
                }
                h.icon.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
                h.label.text = "$name ${c.apps.size}"
                h.box.setOnClickListener { openFolder = name; filter("") ; appList.scrollToPosition(0) }
                h.box.setOnLongClickListener { renameFolderDialog(name); true }
            }
        }
    }
}

/** 세 페이지를 그대로 끼워 넣는 어댑터 */
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
