package com.t.kuvis

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 앱 전체: 멈춤(오류) 기록 */
class KuvisApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                ErrorLog.add(this, "멈춤 (${t.name})", sw.toString())
                getSharedPreferences("kuvis", Context.MODE_PRIVATE).edit().putBoolean("crashedLastTime", true).commit()
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        Hotword.watch(this)
    }
}

/** 오류 기록: 앱 안 파일에 최근 것만 보관 (API 키 같은 건 적지 않음) */
object ErrorLog {
    private const val MAX_CHARS = 60_000

    private fun file(ctx: Context) = File(ctx.filesDir, "errors.txt")

    fun add(ctx: Context, title: String, detail: String) {
        try {
            val f = file(ctx)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date())
            val ver = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { "?" }
            val clean = detail.replace(Regex("sk-ant-[A-Za-z0-9_\\-]+"), "sk-ant-***")
            val entry = "=== $time · 쿠비스 $ver · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n$title\n${clean.take(6000)}\n\n"
            val old = if (f.exists()) f.readText() else ""
            f.writeText((old + entry).takeLast(MAX_CHARS))
        } catch (_: Exception) {}
    }

    fun read(ctx: Context): String = try { file(ctx).takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) {} }
}
