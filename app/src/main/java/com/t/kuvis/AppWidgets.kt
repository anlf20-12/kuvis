package com.t.kuvis

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import kotlin.math.ceil

/** 다른 앱 위젯을 쿠비스 홈에 붙이는 도우미 */
object WidgetHost {
    const val HOST_ID = 4207
    private var host: AppWidgetHost? = null

    /** 홈 화면 한 칸 크기(px). HomeActivity가 그린 뒤 채워 줌 */
    var cellW = 0f
    var cellH = 0f

    fun host(ctx: Context): AppWidgetHost = host ?: AppWidgetHost(ctx.applicationContext, HOST_ID).also { host = it }
    fun mgr(ctx: Context): AppWidgetManager = AppWidgetManager.getInstance(ctx)
    fun info(ctx: Context, id: Int): AppWidgetProviderInfo? = try { mgr(ctx).getAppWidgetInfo(id) } catch (_: Exception) { null }

    private fun cell(ctx: Context): Pair<Float, Float> {
        if (cellW > 0 && cellH > 0) return cellW to cellH
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels / HomeGrid.COLS.toFloat() to dm.heightPixels * 0.72f / HomeGrid.ROWS
    }

    /** [기본 가로, 기본 세로, 최소 가로, 최소 세로] 칸 수 */
    fun cells(ctx: Context, info: AppWidgetProviderInfo): IntArray {
        val (cw, ch) = cell(ctx)
        fun n(px: Int, c: Float) = ceil(px / c).toInt()
        var w = n(info.minWidth, cw)
        var h = n(info.minHeight, ch)
        if (Build.VERSION.SDK_INT >= 31) {
            if (info.targetCellWidth > 0) w = info.targetCellWidth
            if (info.targetCellHeight > 0) h = info.targetCellHeight
        }
        w = w.coerceIn(1, HomeGrid.COLS); h = h.coerceIn(1, HomeGrid.ROWS)
        val mw = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0 && info.minResizeWidth > 0) n(info.minResizeWidth, cw) else w
        val mh = if (info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0 && info.minResizeHeight > 0) n(info.minResizeHeight, ch) else h
        return intArrayOf(w, h, mw.coerceIn(1, w), mh.coerceIn(1, h))
    }

    fun resizable(info: AppWidgetProviderInfo) = info.resizeMode != AppWidgetProviderInfo.RESIZE_NONE

    /** 위젯에 지금 크기(dp)를 알려 줌 */
    fun sizeOptions(ctx: Context, w: Int, h: Int): Bundle {
        val (cw, ch) = cell(ctx)
        val d = ctx.resources.displayMetrics.density
        val wd = (w * cw / d).toInt(); val hd = (h * ch / d).toInt()
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, wd)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, wd)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, hd)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, hd)
        }
    }

    fun delete(ctx: Context, id: Int) { try { host(ctx).deleteAppWidgetId(id) } catch (_: Exception) {} }

    fun label(ctx: Context, info: AppWidgetProviderInfo): String = try { info.loadLabel(ctx.packageManager) } catch (_: Exception) { "" } ?: ""
}

/** 다른 앱이 만들어 준 바로가기 (카톡 채팅방 등) */
object Shortcuts {
    fun key(pkg: String, id: String) = "$pkg|$id"
    fun pkgOf(key: String) = key.substringBefore('|')
    private fun la(ctx: Context) = ctx.getSystemService(LauncherApps::class.java)

    fun find(ctx: Context, key: String): ShortcutInfo? = try {
        val q = LauncherApps.ShortcutQuery().setPackage(pkgOf(key)).setShortcutIds(listOf(key.substringAfter('|')))
            .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        la(ctx).getShortcuts(q, Process.myUserHandle())?.firstOrNull()
    } catch (_: Exception) { null }

    fun icon(ctx: Context, key: String): Drawable? {
        val si = find(ctx, key)
        if (si != null) try { la(ctx).getShortcutIconDrawable(si, 0)?.let { return it } } catch (_: Exception) {}
        return try { ctx.packageManager.getApplicationIcon(pkgOf(key)) } catch (_: Exception) { null }
    }

    fun label(ctx: Context, key: String): String =
        Store(ctx).rawGet("scLabel_$key") ?: find(ctx, key)?.shortLabel?.toString() ?: pkgOf(key)

    fun start(ctx: Context, key: String) {
        try { la(ctx).startShortcut(pkgOf(key), key.substringAfter('|'), null, null, Process.myUserHandle()) }
        catch (e: Exception) { Toast.makeText(ctx, "바로가기를 열 수 없어요.", Toast.LENGTH_SHORT).show() }
    }
}

/** 빈자리 찾아 넣기: 바라는 장부터, 큰 크기부터 최소 크기까지 */
fun HomeGrid.placeAnywhere(ctx: Context, kind: String, pkg: String, w: Int, h: Int, mw: Int, mh: Int, preferPage: Int, cell: Pair<Int, Int>? = null): Boolean {
    val pages = listOf(0)   // 바둑판은 달력 장(0번)만 보임
    for (p in pages) {
        val list = items(ctx, p)
        for (hh in h downTo mh) for (ww in w downTo mw) {
            val spot = cell?.takeIf { p == preferPage && isFree(list, it.first, it.second, ww, hh) } ?: firstFree(list, ww, hh)
            if (spot != null) { save(ctx, p, list + GridItem(kind, pkg, spot.first, spot.second, ww, hh)); return true }
        }
    }
    return false
}

/** 다른 앱이 '홈 화면에 추가'를 요청할 때 (바로가기·위젯) */
class PinActivity : Activity() {
    private var req: LauncherApps.PinItemRequest? = null
    private var widgetId = -1
    private var info: AppWidgetProviderInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val r = try { getSystemService(LauncherApps::class.java).getPinItemRequest(intent) } catch (_: Exception) { null }
        if (r == null || !r.isValid) { finish(); return }
        req = r
        when (r.requestType) {
            LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT -> {
                val si = r.shortcutInfo ?: run { finish(); return }
                val label = si.shortLabel?.toString() ?: si.`package`
                confirm("'$label' 바로가기를 홈 화면에 놓을까요?") {
                    if (r.accept()) {
                        val key = Shortcuts.key(si.`package`, si.id)
                        Store(this).rawPut("scLabel_$key", label)
                        if (HomeGrid.placeAnywhere(this, "shortcut", key, 1, 1, 1, 1, 1)) done("홈 화면에 놓았어요.")
                        else done("홈 화면에 빈 칸이 없어요.")
                    } else done("추가하지 못했어요.")
                }
            }
            LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET -> {
                val i = r.getAppWidgetProviderInfo(this) ?: run { finish(); return }
                info = i
                confirm("'${WidgetHost.label(this, i)}' 위젯을 홈 화면에 놓을까요?") {
                    widgetId = WidgetHost.host(this).allocateAppWidgetId()
                    if (WidgetHost.mgr(this).bindAppWidgetIdIfAllowed(widgetId, i.provider)) placeWidget()
                    else {
                        @Suppress("DEPRECATION")
                        startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, i.provider), 1)
                    }
                }
            }
            else -> finish()
        }
    }

    private fun confirm(msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage(msg)
            .setPositiveButton("놓기") { _, _ -> yes() }
            .setNegativeButton("취소") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun placeWidget() {
        val i = info ?: return finish()
        val r = req ?: return finish()
        if (!r.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) })) {
            WidgetHost.delete(this, widgetId); done("추가하지 못했어요."); return
        }
        val c = WidgetHost.cells(this, i)
        if (HomeGrid.placeAnywhere(this, "widget", widgetId.toString(), c[0], c[1], c[2], c[3], 1)) done("홈 화면에 놓았어요.")
        else { WidgetHost.delete(this, widgetId); done("홈 화면에 빈 칸이 모자라요.") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1) {
            if (resultCode == RESULT_OK) placeWidget() else { WidgetHost.delete(this, widgetId); finish() }
        }
    }

    private fun done(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        Bus.changed()
        finish()
    }
}
