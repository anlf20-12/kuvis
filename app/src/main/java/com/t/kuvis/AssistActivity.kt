package com.t.kuvis

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 측면 버튼(또는 홈의 쿠비스 버튼, "쿠비스" 부르기)으로 뜨는 대화 창.
 * Claude 앱 음성 대화처럼 보던 화면 위에 아래에서 올라오고,
 * 부를 때마다 새 창으로 시작하며, X를 누를 때까지 꺼지지 않고 계속 듣는다.
 */
@Suppress("DEPRECATION")
class AssistActivity : Activity(), KuvisCore.Listener {

    private lateinit var core: KuvisCore
    private lateinit var panel: KuvisPanel
    private val main = Handler(Looper.getMainLooper())
    private val closeNow = Runnable { finish() }
    private var expanded = false
    private var navInset = 0
    private var statusInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }

        val root = FrameLayout(this).apply {
            setOnClickListener { finish() }  // 위쪽 빈 곳을 누르면 닫기
        }
        panel = KuvisPanel(this)
        root.addView(panel, sheetParams())
        root.setOnApplyWindowInsetsListener { _, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                navInset = ins.getInsets(WindowInsets.Type.navigationBars()).bottom
                statusInset = ins.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                navInset = ins.systemWindowInsetBottom
                statusInset = ins.systemWindowInsetTop
            }
            panel.setPadding(0, 0, 0, navInset)
            panel.layoutParams = sheetParams()
            ins
        }
        setContentView(root)

        panel.closeBtn.setOnClickListener { finish() }
        panel.expandBtn.setOnClickListener { expanded = !expanded; panel.setExpanded(expanded); panel.layoutParams = sheetParams() }
        panel.handle.setOnClickListener { if (expanded) { expanded = false; panel.setExpanded(false); panel.layoutParams = sheetParams() } }

        HotwordService.pause() // 말로 부르기 듣기는 잠깐 멈추고 마이크를 대화에 씀
        core = KuvisCore(this, panel, this)
        core.startSoon()
    }

    /** 평소엔 화면 아래 57%, 크게 보기면 상태 표시줄 아래까지 */
    private fun sheetParams(): FrameLayout.LayoutParams {
        val h = if (expanded) ViewGroup.LayoutParams.MATCH_PARENT
                else (resources.displayMetrics.heightPixels * 0.57f).toInt() + navInset
        return FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h, Gravity.BOTTOM).apply {
            if (expanded) topMargin = statusInset + dp(8)
        }
    }

    /** 측면 버튼을 또 누르면: 말하는 중이면 끊고 다시 듣기 */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        main.removeCallbacks(closeNow)
        core.tap()
    }

    /** 말이 없어도 닫지 않음. '그만'이라고 하거나 다른 앱을 열었을 때만 닫음 */
    override fun onIdle(ended: Boolean) {
        main.removeCallbacks(closeNow)
        if (ended) main.postDelayed(closeNow, 900L)
    }

    override fun onResume() {
        super.onResume()
        if (::core.isInitialized) core.unhalt()
    }

    override fun onPause() {
        super.onPause()
        if (!core.askingPermission) core.halt()
    }

    override fun onStop() {
        super.onStop()
        // 다른 앱으로 넘어가면 창을 닫음 (권한 묻는 중은 제외)
        if (!core.askingPermission && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(closeNow)
        core.destroy()
        KuvisSession.current?.let { try { it.hide() } catch (_: Exception) {} }
        HotwordService.resume()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        core.onPermissionsResult(code, results)
    }
}
