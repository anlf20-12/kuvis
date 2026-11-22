package com.t.kuvis

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** 알림 읽기·답장 (설정에서 '알림 접근'을 허용해야 동작) */
class KuvisNotifService : NotificationListenerService() {
    companion object {
        @Volatile var instance: KuvisNotifService? = null

        fun enabled(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return s.contains(ctx.packageName)
        }
    }

    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { super.onDestroy(); if (instance === this) instance = null }
}

object Notifs {
    /** 마지막으로 읽어 준 목록 (번호 → 알림 키) */
    private var lastKeys: List<String> = emptyList()

    private fun appLabel(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun service(ctx: Context): KuvisNotifService {
        if (!KuvisNotifService.enabled(ctx))
            throw IllegalStateException("알림 접근이 꺼져 있음. 쿠비스 설정 > '알림 읽기'에서 켜라고 안내 (안드로이드 13 이상은 앱 정보 > ⋮ > 제한된 설정 허용을 먼저)")
        return KuvisNotifService.instance ?: throw IllegalStateException("알림 서비스가 아직 연결되지 않음. 잠시 뒤 다시 시도")
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
