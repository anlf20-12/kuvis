package com.t.kuvis

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * 알림 읽기·답장은 별도 앱 '쿠비스 알림 도우미'(com.t.kuvis.notify)가 맡는다.
 * (알림 권한이 있는 앱은 Play 프로텍트가 휴대폰에서 바로 설치를 막기 때문에 본체에서 분리)
 */
object NotifyHelper {
    const val PKG = "com.t.kuvis.notify"
    private val URI: Uri = Uri.parse("content://com.t.kuvis.notify")

    fun installed(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    /** 켜져 있으면 true, 꺼져 있으면 false, 설치 안 됐으면 null */
    fun enabled(ctx: Context): Boolean? {
        if (!installed(ctx)) return null
        return try { ctx.contentResolver.call(URI, "status", null, null)?.getBoolean("enabled") ?: false } catch (_: Exception) { false }
    }

    private fun call(ctx: Context, method: String, arg: String?, extras: Bundle?): String {
        if (!installed(ctx)) throw IllegalStateException("알림 도우미 앱이 설치되어 있지 않음. 컴퓨터로 한 번 설치해야 한다고 짧게 안내 (쿠비스 설정 > 알림 읽기·답장에 방법이 있음)")
        val b = ctx.contentResolver.call(URI, method, arg, extras) ?: throw IllegalStateException("알림 도우미가 응답하지 않음")
        b.getString("error")?.let { throw IllegalStateException(it) }
        return b.getString("text") ?: ""
    }

    fun read(ctx: Context, app: String) = call(ctx, "list", app, null)

    fun reply(ctx: Context, index: Int, message: String) =
        call(ctx, "reply", null, Bundle().apply { putInt("index", index); putString("message", message) })
}
