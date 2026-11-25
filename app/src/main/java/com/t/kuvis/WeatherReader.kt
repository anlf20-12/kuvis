package com.t.kuvis

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * T가 쓰는 날씨 앱(달을 누르면 열리는 앱)의 위젯을 보이지 않게 띄워 두고,
 * 위젯에 쓰인 "흐림", "맑음" 같은 글자를 읽어서 다누 그림 날씨를 맞춘다. (2026-11-25 T 요청)
 * 위젯을 못 띄우면 예전처럼 Open-Meteo 값으로.
 */
object WeatherReader {
    private const val PREF = "wxWidgetId"
    const val REQ_BIND = 63
    const val REQ_CONFIG = 64

    private var view: AppWidgetHostView? = null
    private var pendingId = -1

    /** 날씨 앱 패키지 (WeatherApp.open과 같은 순서로 찾음) */
    private fun weatherPkg(home: HomeActivity): String? {
        val pm = home.packageManager
        WeatherApp.known.firstOrNull { pm.getLaunchIntentForPackage(it) != null }?.let { return it }
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).firstOrNull {
            val l = it.loadLabel(pm).toString(); l.contains("날씨") || l.contains("weather", true)
        }?.activityInfo?.packageName
    }

    /** 그 앱의 위젯 중 가장 작은 것 (지금 날씨만 보이는 것일 가능성이 큼) */
    private fun provider(home: HomeActivity): AppWidgetProviderInfo? {
        val pkg = weatherPkg(home) ?: return null
        val list = try { WidgetHost.mgr(home).installedProviders.filter { it.provider.packageName == pkg } } catch (_: Exception) { emptyList() }
        return list.minByOrNull { it.minWidth.toLong() * it.minHeight }
    }

    /** 홈이 열릴 때: 이미 있으면 다시 붙이고, 없으면 한 번만 만들기(안드로이드가 허용을 물을 수 있음) */
    fun ensure(home: HomeActivity, holder: FrameLayout) {
        val p = Prefs(home).p
        val id = p.getInt(PREF, -1)
        if (id >= 0 && WidgetHost.info(home, id) != null) { attach(home, holder, id); return }
        if (p.getBoolean("wxAsked", false)) return   // 한 번 물어봤는데 안 됐으면 다시 안 물음
        val info = provider(home) ?: return
        p.edit().putBoolean("wxAsked", true).apply()
        val nid = WidgetHost.host(home).allocateAppWidgetId()
        pendingId = nid
        val ok = try { WidgetHost.mgr(home).bindAppWidgetIdIfAllowed(nid, info.profile, info.provider, null) } catch (_: Exception) { false }
        if (ok) configured(home, holder, info) else try {
            @Suppress("DEPRECATION")
            home.startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, nid)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile), REQ_BIND)
        } catch (_: Exception) { cancel(home) }
    }

    private fun configured(home: HomeActivity, holder: FrameLayout, info: AppWidgetProviderInfo) {
        if (info.configure != null) {
            try { WidgetHost.host(home).startAppWidgetConfigureActivityForResult(home, pendingId, 0, REQ_CONFIG, null); return }
            catch (_: Exception) {}
        }
        done(home, holder)
    }

    private fun done(home: HomeActivity, holder: FrameLayout) {
        if (pendingId < 0) return
        Prefs(home).p.edit().putInt(PREF, pendingId).apply()
        attach(home, holder, pendingId)
        pendingId = -1
    }

    private fun cancel(home: HomeActivity) {
        if (pendingId >= 0) WidgetHost.delete(home, pendingId)
        pendingId = -1
    }

    /** HomeActivity.onActivityResult에서 부름. 처리했으면 true */
    fun onResult(home: HomeActivity, holder: FrameLayout, req: Int, ok: Boolean): Boolean {
        when (req) {
            REQ_BIND -> { if (ok) provider(home)?.let { configured(home, holder, it) } ?: cancel(home) else cancel(home) }
            REQ_CONFIG -> { if (ok) done(home, holder) else cancel(home) }
            else -> return false
        }
        return true
    }

    private fun attach(home: HomeActivity, holder: FrameLayout, id: Int) {
        if (view != null && view?.appWidgetId == id && view?.parent == holder) return
        (view?.parent as? ViewGroup)?.removeView(view)
        val info = WidgetHost.info(home, id) ?: return
        val v = WidgetHost.host(home).createView(home.applicationContext, id, info)
        view = v
        // 다누 그림 뒤에 깔려서 보이지도 눌리지도 않음
        holder.addView(v, 0, FrameLayout.LayoutParams(home.dp(320), home.dp(180)))
    }

    /** 위젯에서 읽은 날씨 글자 → 그림 이름 (rain/snow/cloudy/clear), 못 읽으면 null */
    fun condition(): String? {
        val v = view ?: return null
        val texts = mutableListOf<String>()
        fun walk(x: View) {
            if (x is TextView) x.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { texts.add(it) }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(v)
        for (t in texts) {
            val s = t.replace(" ", "")
            when {
                s.contains("눈") && !s.contains("눈금") -> return "snow"
                s.contains("비") || s.contains("소나기") || s.contains("빗") || s.contains("뇌우") -> return "rain"
                s.contains("흐림") || s.contains("흐리") || s.contains("구름많") || s.contains("안개") || s.contains("황사") || s.contains("박무") || s.contains("연무") -> return "cloudy"
                s.contains("맑") || s.contains("구름조금") || s.contains("화창") -> return "clear"
            }
        }
        return null
    }
}
