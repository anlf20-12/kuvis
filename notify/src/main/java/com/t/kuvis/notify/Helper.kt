package com.t.kuvis.notify

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 알림을 받는 서비스 (설정 > 알림 접근에서 켜야 동작) */
class HelperListener : NotificationListenerService() {
    companion object {
        @Volatile var instance: HelperListener? = null

        fun enabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return s.contains(ctx.packageName)
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { super.onDestroy(); if (instance === this) instance = null }
}

/** 쿠비스 본체가 부르는 창구: call("status" | "list" | "reply") */
class HelperProvider : ContentProvider() {
    override fun onCreate() = true

    /** 같은 서명 키로 만든 앱(쿠비스 본체)만 허락 */
    private fun allowed(): Boolean {
        val ctx = context ?: return false
        val uid = Binder.getCallingUid()
        return ctx.packageManager.checkSignatures(uid, android.os.Process.myUid()) == PackageManager.SIGNATURE_MATCH
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val out = Bundle()
        val ctx = context ?: return out.apply { putString("error", "준비 안 됨") }
        if (!allowed()) return out.apply { putString("error", "허락되지 않은 앱") }
        try {
            when (method) {
                "status" -> {
                    out.putBoolean("enabled", HelperListener.enabled(ctx))
                    out.putBoolean("connected", HelperListener.instance != null)
                    out.putInt("version", 1)
                }
                "list" -> out.putString("text", Notifs.read(ctx, arg ?: ""))
                "reply" -> out.putString("text", Notifs.reply(ctx, extras?.getInt("index") ?: 0, extras?.getString("message") ?: ""))
                else -> out.putString("error", "모르는 요청: $method")
            }
        } catch (e: Exception) {
            out.putString("error", e.message ?: e.toString())
        }
        return out
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

/** 도우미 화면: 알림 접근 켜기 안내 */
class HelperActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#05080F"))
            setPadding((24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt(), (24 * d).toInt())
        }
        box.addView(TextView(this).apply { text = "쿠비스 알림 도우미"; textSize = 22f; setTextColor(Color.WHITE); gravity = Gravity.CENTER })
        box.addView(TextView(this).apply {
            text = "쿠비스가 카톡·문자 같은 알림을 읽고 답장할 수 있게 도와주는 작은 앱이에요. 알림 내용은 쿠비스에게만 전달되고 다른 곳으로 보내지 않아요."
            textSize = 15f; setTextColor(Color.parseColor("#AAB4C8")); gravity = Gravity.CENTER
            setPadding(0, (16 * d).toInt(), 0, (16 * d).toInt())
        })
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 0, 0, (16 * d).toInt()) }
        box.addView(status)
        box.addView(Button(this).apply {
            text = "알림 접근 켜기"; isAllCaps = false
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        val on = HelperListener.enabled(this)
        status.text = if (on) "✓ 켜져 있어요. 이제 이 앱은 닫아도 돼요." else "• 아직 꺼져 있어요. 아래 버튼을 눌러 '쿠비스 알림 도우미'를 켜 주세요."
        status.setTextColor(if (on) Color.parseColor("#22C55E") else Color.parseColor("#FBBF24"))
    }
}

object Notifs {
    /** 마지막으로 읽어 준 목록 (번호 → 알림 키) */
    private var lastKeys: List<String> = emptyList()

    private fun appLabel(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun service(ctx: Context): HelperListener {
        if (!HelperListener.enabled(ctx))
            throw IllegalStateException("알림 접근이 꺼져 있음. 휴대폰에서 '쿠비스 알림 도우미' 앱을 열어 알림 접근을 켜라고 안내")
        return HelperListener.instance ?: throw IllegalStateException("알림 서비스가 아직 연결되지 않음. 잠시 뒤 다시 시도")
    }

    private fun texts(n: Notification): Pair<String, String> {
        val e = n.extras
        val title = e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: e.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        val body = if (msgs != null && msgs.isNotEmpty()) {
            msgs.takeLast(5).mapNotNull { b ->
                (b as? Bundle)?.let { bb ->
                    val who = bb.getCharSequence("sender")?.toString()
                    val t = bb.getCharSequence("text")?.toString() ?: return@let null
                    if (who.isNullOrBlank()) t else "$who: $t"
                }
            }.joinToString(" / ")
        } else {
            (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        }
        return title to body
    }

    private fun replyAction(n: Notification): Notification.Action? =
        n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }

    fun read(ctx: Context, appQuery: String): String {
        val svc = service(ctx)
        val q = appQuery.replace(" ", "").lowercase()
        val list: List<StatusBarNotification> = (svc.activeNotifications ?: emptyArray())
            .filter { it.packageName != ctx.packageName && !it.isOngoing }
            .filter { sbn ->
                if (q.isEmpty()) true
                else (appLabel(ctx, sbn.packageName) + sbn.packageName).replace(" ", "").lowercase().contains(q)
            }
            .sortedByDescending { it.postTime }
            .take(15)
        lastKeys = list.map { it.key }
        if (list.isEmpty()) return if (q.isEmpty()) "지금 와 있는 알림 없음" else "'$appQuery' 알림 없음"
        return list.mapIndexed { i, sbn ->
            val (t, b) = texts(sbn.notification)
            val ago = (System.currentTimeMillis() - sbn.postTime) / 60000
            val canReply = replyAction(sbn.notification) != null
            "${i + 1}. [${appLabel(ctx, sbn.packageName)}] $t: ${b.take(300)} (${ago}분 전${if (canReply) ", 답장 가능" else ""})"
        }.joinToString("\n")
    }

    fun reply(ctx: Context, index: Int, message: String): String {
        val svc = service(ctx)
        val key = lastKeys.getOrNull(index - 1) ?: throw IllegalArgumentException("그 번호의 알림이 없음. read_notifications를 먼저")
        val sbn = (svc.activeNotifications ?: emptyArray()).firstOrNull { it.key == key }
            ?: throw IllegalStateException("그 알림이 이미 사라짐")
        val action = replyAction(sbn.notification) ?: throw IllegalStateException("이 알림에는 바로 답장 기능이 없음")
        val inputs = action.remoteInputs
        val results = Bundle()
        for (ri in inputs) results.putCharSequence(ri.resultKey, message)
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        action.actionIntent.send(ctx, 0, intent)
        val (t, _) = texts(sbn.notification)
        return "답장 보냄 → [${appLabel(ctx, sbn.packageName)}] $t: $message"
    }
}
