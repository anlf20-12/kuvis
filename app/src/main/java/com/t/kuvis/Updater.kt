package com.t.kuvis

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub에 올라온 새 버전을 확인하고, 한 번 눌러 업데이트.
 * 새 버전은 GitHub Actions가 'build-N' 이름으로 올리고, 앱의 버전 번호는 100+N 이다.
 */
object Updater {
    /** GitHub 저장소 (사용자이름/저장소이름) */
    const val REPO = "anlf20-12/kuvis"

    private const val CHECK_EVERY_MS = 6 * 3_600_000L

    class Release(val code: Long, val name: String, val notes: String, val apkUrl: String)

    fun localCode(ctx: Context): Long {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    }

    /** 홈 화면이 켜질 때: 6시간에 한 번만 조용히 확인 */
    fun checkQuietly(act: Activity) {
        val p = Prefs(act).p
        val now = System.currentTimeMillis()
        if (now - p.getLong("lastUpdateCheck", 0L) < CHECK_EVERY_MS) return
        p.edit().putLong("lastUpdateCheck", now).apply()
        check(act, silent = true)
    }

    /** silent=false면 '최신 버전이에요'도 알려 줌 */
    fun check(act: Activity, silent: Boolean) {
        if (REPO.startsWith("__")) {
            if (!silent) Toast.makeText(act, "업데이트 저장소가 아직 연결되지 않았어요.", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val r = try { latest() } catch (_: Exception) { null }
            act.runOnUiThread {
                if (act.isFinishing) return@runOnUiThread
                when {
                    r == null -> if (!silent) Toast.makeText(act, "업데이트를 확인하지 못했어요. 인터넷을 확인해 주세요.", Toast.LENGTH_SHORT).show()
                    r.code <= localCode(act) -> if (!silent) Toast.makeText(act, "지금이 최신 버전이에요.", Toast.LENGTH_SHORT).show()
                    else -> ask(act, r)
                }
            }
        }.start()
    }

    private fun latest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "kuvis-app")
        c.connectTimeout = 10000; c.readTimeout = 10000
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val n = j.optString("tag_name").removePrefix("build-").toLongOrNull() ?: return null
            val assets = j.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            return Release(100 + n, j.optString("name"), j.optString("body").trim(), url ?: return null)
        } finally { c.disconnect() }
    }

    private fun ask(act: Activity, r: Release) {
        val notes = r.notes.lines().filter { it.isNotBlank() && !it.startsWith("Co-Authored") && !it.startsWith("Claude-Session") }
            .take(8).joinToString("\n")
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("쿠비스 새 버전이 있어요")
            .setMessage((if (notes.isNotBlank()) "$notes\n\n" else "") + "지금 업데이트할까요? API 키·할 일·메모는 그대로 남아요.")
            .setPositiveButton("업데이트") { _, _ -> start(act, r) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun start(act: Activity, r: Release) {
        // 직접 만든 앱은 '이 출처 허용'을 한 번 켜야 설치할 수 있음
        if (!act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("처음 한 번만: 다음 화면에서 '이 출처 허용'을 켜고 돌아와서 다시 '업데이트'를 눌러 주세요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${act.packageName}")))
                    Prefs(act).p.edit().putLong("lastUpdateCheck", 0L).apply()
                }
                .setNegativeButton("취소", null).show()
            return
        }
        Toast.makeText(act, "새 버전을 받는 중이에요…", Toast.LENGTH_LONG).show()
        Thread {
            try {
                val f = File(act.cacheDir, "kuvis-update.apk")
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000; c.readTimeout = 60000
                c.setRequestProperty("User-Agent", "kuvis-app")
                try { c.inputStream.use { inp -> f.outputStream().use { inp.copyTo(it) } } } finally { c.disconnect() }
                install(act, f)
            } catch (e: Exception) {
                act.runOnUiThread { Toast.makeText(act, "받지 못했어요: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun install(ctx: Context, f: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("kuvis.apk", 0, f.length()).use { out ->
                f.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags)
            s.commit(pending.intentSender)
        }
    }
}

/** 설치 진행 결과를 받음. 사용자 확인이 필요하면 확인 창을 띄움 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { ctx.startActivity(confirm) } catch (_: Exception) {}
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 이유"
                Toast.makeText(ctx, "업데이트 실패: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
