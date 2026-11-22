package com.t.kuvis

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/** 할 일·메모·기억·폴더를 휴대폰 '다운로드/쿠비스' 폴더에 하루 한 번 자동 백업 */
object Backup {
    private const val NAME = "kuvis-backup.json"
    private val DIR = Environment.DIRECTORY_DOWNLOADS + "/쿠비스/"
    private val KEYS = listOf("todos", "memos", "memory", "folderOrder", "folderAssign", "hiddenApps", "dock")

    fun exportJson(ctx: Context): String {
        val store = Store(ctx)
        val o = JSONObject().put("kuvisBackup", 1).put("savedAt", System.currentTimeMillis())
        for (k in KEYS) store.rawGet(k)?.let { o.put(k, it) }
        return o.toString(2)
    }

    /** 오늘 아직 안 했으면 백업 (안드로이드 10 이상) */
    fun autoDaily(ctx: Context) {
        if (Build.VERSION.SDK_INT < 29) return
        val p = Prefs(ctx).p
        val today = java.time.LocalDate.now().toString()
        if (p.getString("lastBackupDay", "") == today) return
        Thread {
            try { save(ctx); p.edit().putString("lastBackupDay", today).apply() }
            catch (e: Exception) { ErrorLog.add(ctx, "자동 백업 실패", e.toString()) }
        }.start()
    }

    /** 지금 백업. 저장한 곳을 돌려줌 */
    fun save(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 29) throw IllegalStateException("안드로이드 10 이상에서만 돼요")
        val cr = ctx.contentResolver
        val coll = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var uri: Uri? = null
        cr.query(coll, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?", arrayOf(NAME, DIR), null)?.use { c ->
            if (c.moveToFirst()) uri = android.content.ContentUris.withAppendedId(coll, c.getLong(0))
        }
        if (uri == null) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, DIR)
            }
            uri = cr.insert(coll, v) ?: throw IllegalStateException("백업 파일을 만들지 못함")
        }
        cr.openOutputStream(uri!!, "wt")?.use { it.write(exportJson(ctx).toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("백업 파일에 쓰지 못함")
        return "다운로드/쿠비스/$NAME"
    }

    /** 백업 파일 내용으로 되돌리기 */
    fun restore(ctx: Context, text: String): Int {
        val o = JSONObject(text)
        if (!o.has("kuvisBackup")) throw IllegalArgumentException("쿠비스 백업 파일이 아니에요")
        val store = Store(ctx)
        var n = 0
        for (k in KEYS) if (o.has(k)) { store.rawPut(k, o.getString(k)); n++ }
        Bus.changed()
        return n
    }
}
