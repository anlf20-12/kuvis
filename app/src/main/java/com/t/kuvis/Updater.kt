package com.t.kuvis

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
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
                if (a.optString("name") == "kuvis.apk") { url = a.optString("browser_download_url"); break }
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

    /**
     * 브라우저로 새 버전 파일을 받게 함.
     * (쿠비스가 직접 받아서 설치하면 휴대폰 보안이 '악성 앱'으로 의심해 막음)
     */
    private fun start(act: Activity, r: Release) {
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("브라우저에서 새 버전 파일(kuvis.apk)을 받아요.\n\n받기가 끝나면 알림이나 다운로드 목록에서 파일을 눌러 '설치' → '업데이트'를 누르면 돼요.")
            .setPositiveButton("받기") { _, _ ->
                try {
                    act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.apkUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    ErrorLog.add(act, "업데이트 받기 화면 열기 실패", e.toString())
                    Toast.makeText(act, "브라우저를 열지 못했어요: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("취소", null).show()
    }
}
