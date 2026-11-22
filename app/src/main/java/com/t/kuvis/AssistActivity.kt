package com.t.kuvis

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout

/** 측면 버튼(또는 홈의 쿠비스 버튼)으로 뜨는 대화 창. 지금 보던 화면 위에 아래쪽으로 뜬다. */
class AssistActivity : Activity(), KuvisCore.Listener {

    private lateinit var core: KuvisCore
    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { if (core.state == "idle") finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply {
            setOnClickListener { finish() }  // 바깥을 누르면 닫기
            fitsSystemWindows = true
        }
        val panel = KuvisPanel(this)
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply { setMargins(dp(8), 0, dp(8), dp(8)) })
        setContentView(root)

        core = KuvisCore(this, panel, this)
        core.startSoon()
    }

    /** 측면 버튼을 또 누르면: 말하는 중이면 끊고 다시 듣기 */
    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        main.removeCallbacks(autoClose)
        core.tap()
    }

    override fun onIdle(ended: Boolean) {
        main.removeCallbacks(autoClose)
        // 대화가 끝났으면 잠깐 보여 주고 닫기, 못 들었으면 10초 기다렸다 닫기
        main.postDelayed(autoClose, if (ended) 900L else 10_000L)
    }

    override fun onPause() {
        super.onPause()
        if (!core.askingPermission) core.interrupt()
    }

    override fun onStop() {
        super.onStop()
        // 다른 앱으로 넘어가면 창을 닫음 (권한 묻는 중은 제외)
        if (!core.askingPermission && !isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(autoClose)
        core.destroy()
        KuvisSession.current?.let { try { it.hide() } catch (_: Exception) {} }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        core.onPermissionsResult(code, results)
    }
}
