package com.t.kuvis

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale

/**
 * 쿠비스가 휴대폰에서 직접 하는 일들.
 * 다른 화면을 띄우는 일(앱 열기, 전화, 삭제 창 등)은 쿠비스가 말을 마친 뒤에 실행되도록 모아 둔다.
 */
class PhoneTools(private val ctx: Context) {

    /** 말을 마친 뒤 띄울 화면. second=true 이면 화면이 바뀌므로 다시 듣지 않음 */
    val pendingLaunches: MutableList<Pair<Intent, Boolean>> = Collections.synchronizedList(mutableListOf())
    /** 말을 마친 뒤 요청할 권한 */
    val pendingPerms: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    private val cr get() = ctx.contentResolver
    private val store = Store(ctx)
    private val zone: ZoneId get() = ZoneId.systemDefault()

    // ------------------------------------------------------------------
    // 도구 정의 (Claude에게 알려 주는 목록)
    fun definitions(): JSONArray {
        fun p(type: String, desc: String) = JSONObject().put("type", type).put("description", desc)
        fun tool(name: String, desc: String, props: JSONObject, required: List<String> = emptyList()) =
            JSONObject().put("name", name).put("description", desc).put(
                "input_schema",
                JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required))
            )

        return JSONArray()
            .put(tool("add_calendar_event", "휴대폰 기본 캘린더에 일정을 추가한다.",
                JSONObject()
                    .put("title", p("string", "일정 제목"))
                    .put("start", p("string", "시작. 형식 2026-11-23T15:00 (종일이면 2026-11-23)"))
                    .put("end", p("string", "끝(선택). 없으면 1시간 뒤. 종일 일정이면 마지막 날짜"))
                    .put("all_day", p("boolean", "종일 일정 여부"))
                    .put("location", p("string", "장소(선택)"))
                    .put("description", p("string", "메모(선택)"))
                    .put("reminder_minutes", p("integer", "몇 분 전에 알림(선택)")),
                listOf("title", "start")))
            .put(tool("list_calendar_events", "기간 안의 일정을 가져온다. 일정 id도 함께 준다.",
                JSONObject()
                    .put("from_date", p("string", "시작 날짜 2026-11-23"))
                    .put("to_date", p("string", "끝 날짜(선택, 포함). 없으면 하루만")),
                listOf("from_date")))
            .put(tool("delete_calendar_event", "일정을 지운다. 반드시 사용자에게 확인받은 뒤에만.",
                JSONObject().put("event_id", p("integer", "list_calendar_events로 얻은 id")), listOf("event_id")))
            .put(tool("set_alarm", "알람을 맞춘다.",
                JSONObject()
                    .put("hour", p("integer", "0~23"))
                    .put("minute", p("integer", "0~59"))
                    .put("label", p("string", "알람 이름(선택)"))
                    .put("days", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer"))
                        .put("description", "반복 요일(선택). 1=일 2=월 3=화 4=수 5=목 6=금 7=토")),
                listOf("hour", "minute")))
            .put(tool("set_timer", "타이머를 맞춘다.",
                JSONObject().put("seconds", p("integer", "몇 초")).put("label", p("string", "이름(선택)")),
                listOf("seconds")))
            .put(tool("list_apps", "설치된 앱 목록. 사용 기록 권한이 있으면 마지막 사용일도 준다. 앱 정리할 때 쓴다.",
                JSONObject().put("sort_by", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("name").put("last_used")).put("description", "정렬 기준"))))
            .put(tool("open_app", "앱을 연다. 앱 이름이나 패키지명.",
                JSONObject().put("app", p("string", "앱 이름 또는 패키지명")), listOf("app")))
            .put(tool("uninstall_app", "앱 삭제 확인 창을 띄운다(사용자가 휴대폰에서 확인을 눌러야 지워짐). 여러 개면 여러 번 호출.",
                JSONObject().put("app", p("string", "앱 이름 또는 패키지명")), listOf("app")))
            .put(tool("open_app_settings", "앱 정보 화면(저장공간·캐시·권한·알림)을 연다.",
                JSONObject().put("app", p("string", "앱 이름 또는 패키지명")), listOf("app")))
            .put(tool("find_contacts", "연락처에서 이름으로 전화번호를 찾는다.",
                JSONObject().put("name", p("string", "이름 일부")), listOf("name")))
            .put(tool("call_phone", "전화를 건다. 반드시 누구에게 걸지 사용자에게 확인받은 뒤에만.",
                JSONObject().put("number", p("string", "전화번호")), listOf("number")))
            .put(tool("send_sms", "문자를 바로 보낸다. 반드시 받는 사람과 내용을 읽어 주고 확인받은 뒤에만.",
                JSONObject().put("number", p("string", "전화번호")).put("message", p("string", "내용")),
                listOf("number", "message")))
            .put(tool("flashlight", "손전등을 켜거나 끈다.",
                JSONObject().put("on", p("boolean", "켜기 true, 끄기 false")), listOf("on")))
            .put(tool("set_volume", "볼륨을 바꾼다.",
                JSONObject()
                    .put("percent", p("integer", "0~100"))
                    .put("stream", JSONObject().put("type", "string")
                        .put("enum", JSONArray().put("media").put("ring").put("alarm")).put("description", "기본 media")),
                listOf("percent")))
            .put(tool("open_settings", "휴대폰 설정 화면을 연다.",
                JSONObject().put("page", JSONObject().put("type", "string").put("enum", JSONArray()
                    .put("main").put("wifi").put("bluetooth").put("display").put("sound").put("battery")
                    .put("storage").put("apps").put("location").put("dnd").put("usage_access"))),
                listOf("page")))
            .put(tool("open_map", "지도에서 장소를 찾거나 길찾기를 연다.",
                JSONObject().put("query", p("string", "장소 이름이나 주소")), listOf("query")))
            .put(tool("open_url", "웹 주소를 브라우저로 연다.",
                JSONObject().put("url", p("string", "https 주소")), listOf("url")))
            .put(tool("todo_add", "할 일을 추가한다(홈 가운데 할 일 목록).",
                JSONObject().put("text", p("string", "할 일 내용"))
                    .put("due", p("string", "기한 날짜(선택) 2026-11-23")),
                listOf("text")))
            .put(tool("todo_list", "할 일 목록과 id를 본다.",
                JSONObject().put("include_done", p("boolean", "완료한 것도 볼지(기본 false)"))))
            .put(tool("todo_done", "할 일을 완료(또는 완료 취소)로 표시한다.",
                JSONObject().put("id", p("integer", "할 일 id")).put("done", p("boolean", "기본 true")),
                listOf("id")))
            .put(tool("todo_edit", "할 일 내용이나 기한을 바꾼다. 기한을 없애려면 due를 빈 문자열로.",
                JSONObject().put("id", p("integer", "할 일 id")).put("text", p("string", "새 내용(선택)"))
                    .put("due", p("string", "새 기한(선택)")),
                listOf("id")))
            .put(tool("todo_delete", "할 일을 지운다. 확인받은 뒤에만.",
                JSONObject().put("id", p("integer", "할 일 id")), listOf("id")))
            .put(tool("memo_add", "메모를 저장한다(홈 오른쪽 메모 페이지).",
                JSONObject().put("text", p("string", "메모 내용. 첫 줄이 제목처럼 보임")), listOf("text")))
            .put(tool("memo_list", "메모 목록과 id를 본다. query가 있으면 그 말이 들어간 메모만.",
                JSONObject().put("query", p("string", "찾을 말(선택)"))))
            .put(tool("memo_edit", "메모를 고친다. mode=append면 끝에 덧붙이고, replace면 통째로 바꾼다.",
                JSONObject().put("id", p("integer", "메모 id")).put("text", p("string", "내용"))
                    .put("mode", JSONObject().put("type", "string").put("enum", JSONArray().put("append").put("replace"))),
                listOf("id", "text")))
            .put(tool("memo_pin", "메모를 맨 위에 고정하거나 푼다.",
                JSONObject().put("id", p("integer", "메모 id")).put("pinned", p("boolean", "고정 true")),
                listOf("id", "pinned")))
            .put(tool("memo_delete", "메모를 지운다. 확인받은 뒤에만.",
                JSONObject().put("id", p("integer", "메모 id")), listOf("id")))
            .put(tool("update_calendar_event", "일정의 제목·시간·장소를 바꾼다.",
                JSONObject().put("event_id", p("integer", "일정 id"))
                    .put("title", p("string", "새 제목(선택)"))
                    .put("start", p("string", "새 시작(선택) 2026-11-23T15:00"))
                    .put("end", p("string", "새 끝(선택)"))
                    .put("location", p("string", "새 장소(선택)")),
                listOf("event_id")))
            .put(tool("get_weather", "지금 있는 곳의 현재 날씨와 5일 예보.", JSONObject()))
            .put(tool("set_dock", "홈 화면 아래 고정 앱(최대 5개)을 바꾼다. 순서대로.",
                JSONObject().put("apps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "앱 이름 또는 패키지명 목록")),
                listOf("apps")))
            .put(tool("list_folders", "전체 앱 서랍의 폴더와 각 폴더에 든 앱 목록.", JSONObject()))
            .put(tool("move_apps_to_folder", "앱들을 전체 앱 서랍의 한 폴더로 옮긴다. 없는 폴더 이름이면 새로 만든다. folder를 '폴더 밖'으로 하면 폴더 아래에 따로 둔다.",
                JSONObject().put("apps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "앱 이름 또는 패키지명 목록"))
                    .put("folder", p("string", "폴더 이름")),
                listOf("apps", "folder")))
            .put(tool("rename_folder", "앱 서랍 폴더 이름을 바꾼다.",
                JSONObject().put("from", p("string", "지금 이름")).put("to", p("string", "새 이름")), listOf("from", "to")))
            .put(tool("resort_folders", "모든 앱을 이름을 보고 폴더에 처음부터 다시 자동 정리한다. 사용자가 손으로 옮긴 것도 다시 정해지니 확인받은 뒤에만.", JSONObject()))
            .put(tool("hide_apps", "앱을 전체 앱 서랍에서 숨기거나(hidden=true) 다시 보이게(false) 한다. 앱은 지워지지 않음.",
                JSONObject().put("apps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string"))
                    .put("description", "앱 이름 또는 패키지명 목록"))
                    .put("hidden", p("boolean", "숨기기 true, 다시 보이기 false")),
                listOf("apps", "hidden")))
            .put(tool("list_hidden_apps", "숨긴 앱 목록.", JSONObject()))
            .put(tool("remember", "T에 대해 앞으로도 기억할 사실 한 줄을 저장한다 (예: '다누 어린이집은 ○○').",
                JSONObject().put("fact", p("string", "기억할 내용 한 줄")), listOf("fact")))
            .put(tool("forget", "기억한 것 중 이 말이 들어간 것을 지운다.",
                JSONObject().put("query", p("string", "지울 내용의 일부")), listOf("query")))
            .put(tool("list_memory", "쿠비스가 기억하고 있는 것 목록.", JSONObject()))
            .put(tool("read_notifications", "지금 휴대폰에 와 있는 알림(카톡·문자 등)을 읽는다. 번호를 함께 준다.",
                JSONObject().put("app", p("string", "앱 이름으로 거르기(선택). 예: 카카오톡"))))
            .put(tool("reply_notification", "알림에 바로 답장한다(카톡 등). 반드시 받는 사람과 내용을 말하고 확인받은 뒤에만.",
                JSONObject().put("index", p("integer", "read_notifications에서 받은 번호")).put("message", p("string", "보낼 내용")),
                listOf("index", "message")))
            .put(tool("backup_now", "할 일·메모·기억을 휴대폰 다운로드/쿠비스 폴더에 지금 백업한다.", JSONObject()))
            .put(tool("show_page", "홈 화면에서 특정 페이지를 보여 준다.",
                JSONObject().put("page", JSONObject().put("type", "string")
                    .put("enum", JSONArray().put("calendar").put("home").put("memo"))),
                listOf("page")))
            .put(tool("device_status", "배터리·충전 상태·남은 저장공간을 확인한다.", JSONObject()))
    }

    /** 사람이 볼 짧은 작업 이름 */
    fun label(name: String) = when (name) {
        "add_calendar_event" -> "일정 넣는 중…"
        "list_calendar_events" -> "일정 보는 중…"
        "delete_calendar_event" -> "일정 지우는 중…"
        "set_alarm" -> "알람 맞추는 중…"
        "set_timer" -> "타이머 맞추는 중…"
        "list_apps" -> "앱 살펴보는 중…"
        "find_contacts" -> "연락처 찾는 중…"
        "send_sms" -> "문자 보내는 중…"
        "todo_add", "todo_done", "todo_edit" -> "할 일 정리 중…"
        "memo_add", "memo_edit" -> "메모하는 중…"
        "get_weather" -> "날씨 보는 중…"
        "read_notifications" -> "알림 보는 중…"
        "reply_notification" -> "답장 보내는 중…"
        else -> "작업 중…"
    }

    // ------------------------------------------------------------------
    fun execute(name: String, a: JSONObject): String = when (name) {
        "add_calendar_event" -> addEvent(a)
        "list_calendar_events" -> listEvents(a)
        "delete_calendar_event" -> deleteEvent(a)
        "set_alarm" -> setAlarm(a)
        "set_timer" -> setTimer(a)
        "list_apps" -> listApps(a.optString("sort_by", "name"))
        "open_app" -> openApp(a.getString("app"))
        "uninstall_app" -> uninstall(a.getString("app"))
        "open_app_settings" -> appSettings(a.getString("app"))
        "find_contacts" -> findContacts(a.getString("name"))
        "call_phone" -> call(a.getString("number"))
        "send_sms" -> sms(a.getString("number"), a.getString("message"))
        "flashlight" -> torch(a.getBoolean("on"))
        "set_volume" -> volume(a.getInt("percent"), a.optString("stream", "media"))
        "open_settings" -> settings(a.getString("page"))
        "open_map" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(a.getString("query")))), "지도를 열게요")
        "open_url" -> launch(Intent(Intent.ACTION_VIEW, Uri.parse(a.getString("url"))), "브라우저를 열게요")
        "device_status" -> status()
        "todo_add" -> { val t = store.addTodo(a.getString("text"), a.optString("due", "")); Bus.changed(); "추가됨 id=${t.id}" + (t.due?.let { " 기한 $it" } ?: "") }
        "todo_list" -> todoList(a.optBoolean("include_done", false))
        "todo_done" -> { val ok = store.setDone(a.getLong("id"), a.optBoolean("done", true)); Bus.changed(); if (ok) "표시함" else "그 id 없음" }
        "todo_edit" -> {
            val ok = store.editTodo(a.getLong("id")) { t ->
                t.copy(
                    text = a.optString("text", "").ifBlank { t.text },
                    due = if (a.has("due")) a.optString("due", "").take(10).ifBlank { null } else t.due
                )
            }
            Bus.changed(); if (ok) "고침" else "그 id 없음"
        }
        "todo_delete" -> { val ok = store.editTodo(a.getLong("id")) { null }; Bus.changed(); if (ok) "지움" else "그 id 없음" }
        "memo_add" -> { val m = store.addMemo(a.getString("text")); Bus.changed(); "저장됨 id=${m.id}" }
        "memo_list" -> memoList(a.optString("query", ""))
        "memo_edit" -> {
            val text = a.getString("text")
            val append = a.optString("mode", "append") != "replace"
            val ok = store.editMemo(a.getLong("id")) { m -> m.copy(text = if (append) m.text + "\n" + text else text) }
            Bus.changed(); if (ok) "고침" else "그 id 없음"
        }
        "memo_pin" -> { val ok = store.editMemo(a.getLong("id")) { it.copy(pinned = a.getBoolean("pinned")) }; Bus.changed(); if (ok) "됨" else "그 id 없음" }
        "memo_delete" -> { val ok = store.editMemo(a.getLong("id")) { null }; Bus.changed(); if (ok) "지움" else "그 id 없음" }
        "update_calendar_event" -> updateEvent(a)
        "get_weather" -> Weather.get(ctx)?.let { Weather.summary(it) } ?: "날씨를 가져오지 못함"
        "set_dock" -> setDock(a.getJSONArray("apps"))
        "show_page" -> { Bus.showPage(a.getString("page")); "보여 줌" }
        "remember" -> {
            val f = a.getString("fact").trim()
            store.setMemory(store.memory().filter { it != f } + f); "기억함: $f"
        }
        "forget" -> {
            val q = a.getString("query").trim()
            val before = store.memory()
            val after = before.filter { !it.contains(q, ignoreCase = true) }
            store.setMemory(after); "지움 ${before.size - after.size}개"
        }
        "list_memory" -> store.memory().ifEmpty { listOf("기억한 것 없음") }.joinToString("\n")
        "read_notifications" -> Notifs.read(ctx, a.optString("app", ""))
        "reply_notification" -> Notifs.reply(ctx, a.getInt("index"), a.getString("message"))
        "backup_now" -> "백업함: " + Backup.save(ctx)
        "hide_apps" -> {
            val arr = a.getJSONArray("apps"); val hide = a.optBoolean("hidden", true)
            val done = mutableListOf<String>(); val failed = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val q = arr.getString(i)
                try {
                    // 숨긴 앱도 찾을 수 있게 숨김 목록에서 먼저 찾음
                    val pkg = store.hiddenApps().firstOrNull { h -> h == q || labelOf(h).replace(" ", "").contains(q.replace(" ", ""), true) } ?: resolvePkg(q)
                    store.setHidden(pkg, hide)
                    if (hide) store.dock()?.let { d -> if (pkg in d) store.setDock(d - pkg) }
                    done.add(labelOf(pkg))
                } catch (_: Exception) { failed.add(q) }
            }
            Bus.changed()
            (if (hide) "숨김: " else "다시 보이게 함: ") + done.joinToString(", ") + (if (failed.isNotEmpty()) " / 못 찾음: ${failed.joinToString(", ")}" else "")
        }
        "list_hidden_apps" -> store.hiddenApps().map { labelOf(it) }.ifEmpty { listOf("숨긴 앱 없음") }.joinToString(", ")
        "list_folders" -> {
            val apps = entries()
            val assign = Folders.assign(ctx, apps)
            store.folderOrder().joinToString("\n") { f ->
                val names = apps.filter { assign[it.pkg] == f }.map { it.label }
                "$f (${names.size}): " + names.joinToString(", ")
            } + "\n폴더 밖: " + apps.filter { assign[it.pkg] == Folders.TOP }.joinToString(", ") { it.label }
        }
        "move_apps_to_folder" -> {
            val arr = a.getJSONArray("apps"); val folder = a.getString("folder").trim()
            val moved = mutableListOf<String>(); val failed = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val q = arr.getString(i)
                try { store.moveToFolder(resolvePkg(q), folder); moved.add(q) } catch (_: Exception) { failed.add(q) }
            }
            Bus.changed()
            "옮김: ${moved.joinToString(", ")} → $folder" + (if (failed.isNotEmpty()) " / 못 찾음: ${failed.joinToString(", ")}" else "")
        }
        "rename_folder" -> { val ok = store.renameFolder(a.getString("from"), a.getString("to").trim()); Bus.changed(); if (ok) "바꿈" else "그 폴더 없음" }
        "resort_folders" -> { Folders.resortAll(ctx, entries()); Bus.changed(); "다시 정리함: " + store.folderOrder().joinToString(", ") }
        else -> throw IllegalArgumentException("모르는 도구: $name")
    }

    private fun need(vararg perms: String) {
        val missing = perms.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingPerms.addAll(missing)
            throw SecurityException("권한이 없음. 말을 마치면 권한 창이 뜨니, 사용자에게 '허용'을 누른 뒤 다시 말해 달라고 짧게 안내해.")
        }
    }

    private fun launch(i: Intent, msg: String, leavesScreen: Boolean = true): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        pendingLaunches.add(i to leavesScreen)
        return "$msg (말을 마치면 실행됨)"
    }

    // ---------- 날짜 ----------
    private fun parseDt(s: String): LocalDateTime {
        val t = s.trim()
        return try { LocalDateTime.parse(t) } catch (_: Exception) {
            try { OffsetDateTime.parse(t).atZoneSameInstant(zone).toLocalDateTime() } catch (_: Exception) {
                LocalDate.parse(t.take(10)).atStartOfDay()
            }
        }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("M/d(E)", Locale.KOREAN)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    // ---------- 캘린더 ----------
    private fun calendarId(): Long {
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.ACCOUNT_TYPE
        )
        val sel = "${CalendarContract.Calendars.VISIBLE}=1 AND " +
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        cr.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, null, null)?.use { c ->
            var best = -1L
            var bestScore = -1
            while (c.moveToNext()) {
                val score = (if (c.getString(2) == "com.google") 2 else 0) + (if (c.getInt(1) == 1) 1 else 0)
                if (score > bestScore) { best = c.getLong(0); bestScore = score }
            }
            if (best >= 0) return best
        }
        throw IllegalStateException("쓸 수 있는 캘린더가 없음(휴대폰 설정 > 계정에서 구글 캘린더 동기화를 켜라고 안내)")
    }

    private fun addEvent(a: JSONObject): String {
        need(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val title = a.getString("title")
        val allDay = a.optBoolean("all_day", false)
        val v = ContentValues()
        val desc: String
        if (allDay) {
            val d1 = LocalDate.parse(a.getString("start").take(10))
            val endStr = a.optString("end", "")
            val d2 = if (endStr.length >= 10) LocalDate.parse(endStr.take(10)).plusDays(1) else d1.plusDays(1)
            v.put(CalendarContract.Events.DTSTART, d1.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.DTEND, d2.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            v.put(CalendarContract.Events.ALL_DAY, 1)
            desc = "${d1.format(dayFmt)} 종일"
        } else {
            val s = parseDt(a.getString("start"))
            val endStr = a.optString("end", "")
            val e = if (endStr.isNotBlank()) parseDt(endStr) else s.plusHours(1)
            v.put(CalendarContract.Events.DTSTART, s.atZone(zone).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.DTEND, e.atZone(zone).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            v.put(CalendarContract.Events.ALL_DAY, 0)
            desc = "${s.format(dayFmt)} ${s.format(timeFmt)}~${e.format(timeFmt)}"
        }
        v.put(CalendarContract.Events.TITLE, title)
        v.put(CalendarContract.Events.CALENDAR_ID, calendarId())
        a.optString("location", "").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it) }
        a.optString("description", "").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.DESCRIPTION, it) }
        val uri = cr.insert(CalendarContract.Events.CONTENT_URI, v) ?: throw IllegalStateException("일정 저장 실패")
        val id = uri.lastPathSegment?.toLongOrNull() ?: -1L
        val rem = a.optInt("reminder_minutes", -1)
        if (rem >= 0 && id >= 0) {
            val r = ContentValues()
            r.put(CalendarContract.Reminders.EVENT_ID, id)
            r.put(CalendarContract.Reminders.MINUTES, rem)
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r)
        }
        Bus.changed()
        return "추가됨 id=$id: $title, $desc" + (if (rem >= 0) ", ${rem}분 전 알림" else "")
    }

    private fun listEvents(a: JSONObject): String {
        need(Manifest.permission.READ_CALENDAR)
        val from = LocalDate.parse(a.getString("from_date").take(10))
        val toStr = a.optString("to_date", "")
        val to = if (toStr.length >= 10) LocalDate.parse(toStr.take(10)) else from
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val proj = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION
        )
        val out = StringBuilder()
        val c = CalendarContract.Instances.query(cr, proj, begin, end)
        c?.use {
            val rows = mutableListOf<Pair<Long, String>>()
            while (it.moveToNext()) {
                val allDay = it.getInt(4) == 1
                val z = if (allDay) ZoneOffset.UTC else zone
                val b = Instant.ofEpochMilli(it.getLong(2)).atZone(z)
                val e = Instant.ofEpochMilli(it.getLong(3)).atZone(z)
                val time = if (allDay) "${b.format(dayFmt)} 종일" else "${b.format(dayFmt)} ${b.format(timeFmt)}~${e.format(timeFmt)}"
                val loc = it.getString(5)?.takeIf { s -> s.isNotBlank() }?.let { s -> " @ $s" } ?: ""
                rows.add(it.getLong(2) to "id=${it.getLong(0)} | $time | ${it.getString(1) ?: "(제목 없음)"}$loc")
            }
            rows.sortedBy { r -> r.first }.forEach { r -> out.append(r.second).append('\n') }
        }
        return if (out.isEmpty()) "그 기간에 일정 없음" else out.toString().trim()
    }

    private fun deleteEvent(a: JSONObject): String {
        need(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val id = a.getLong("event_id")
        val n = cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null)
        Bus.changed()
        return if (n > 0) "지움 id=$id" else "그 id의 일정을 못 찾음"
    }

    // ---------- 알람·타이머 ----------
    private fun setAlarm(a: JSONObject): String {
        val h = a.getInt("hour"); val m = a.getInt("minute")
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label", "").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        a.optJSONArray("days")?.let { arr ->
            val days = ArrayList<Int>()
            for (k in 0 until arr.length()) days.add(arr.getInt(k))
            if (days.isNotEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, days)
        }
        return launch(i, "%02d:%02d 알람".format(h, m), leavesScreen = false)
    }

    private fun setTimer(a: JSONObject): String {
        val s = a.getInt("seconds")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, s)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label", "").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        return launch(i, "${s}초 타이머", leavesScreen = false)
    }

    // ---------- 앱 ----------
    private data class AppItem(val label: String, val pkg: String)

    private fun launcherApps(): List<AppItem> {
        val pm = ctx.packageManager
        val ris = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return ris.map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .filter { it.pkg != ctx.packageName }
    }

    private fun labelOf(pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun entries(): List<AppEntry> {
        val pm = ctx.packageManager
        return launcherApps().map { a ->
            val cat = if (Build.VERSION.SDK_INT >= 26) try { pm.getApplicationInfo(a.pkg, 0).category } catch (_: Exception) { -1 } else -1
            AppEntry(a.label, a.pkg, null, cat)
        }
    }

    private fun resolvePkg(q: String): String {
        val apps = launcherApps()
        apps.firstOrNull { it.pkg == q }?.let { return it.pkg }
        val n = q.replace(" ", "").lowercase()
        apps.firstOrNull { it.label.replace(" ", "").lowercase() == n }?.let { return it.pkg }
        apps.firstOrNull { it.label.replace(" ", "").lowercase().contains(n) }?.let { return it.pkg }
        throw IllegalArgumentException("'$q' 앱을 못 찾음. list_apps로 정확한 이름을 확인해.")
    }

    private fun hasUsageAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun listApps(sortBy: String): String {
        val pm = ctx.packageManager
        val now = System.currentTimeMillis()
        val usage: Map<String, Long> = if (hasUsageAccess()) {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            usm.queryAndAggregateUsageStats(now - 365L * 24 * 3600 * 1000, now).mapValues { it.value.lastTimeUsed }
        } else emptyMap()
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        data class Row(val label: String, val pkg: String, val last: Long, val line: String)
        val rows = launcherApps().map { app ->
            val info = try { pm.getPackageInfo(app.pkg, 0) } catch (_: Exception) { null }
            val sys = info?.applicationInfo?.let { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: false
            val last = usage[app.pkg] ?: 0L
            val lastStr = when {
                usage.isEmpty() -> ""
                last <= 0L -> " | 마지막 사용: 1년 넘게 기록 없음"
                else -> " | 마지막 사용: ${(now - last) / 86_400_000L}일 전"
            }
            val inst = info?.firstInstallTime?.let { t ->
                " | 설치: " + Instant.ofEpochMilli(t).atZone(zone).format(dateFmt)
            } ?: ""
            Row(app.label, app.pkg, last, "${app.label} | ${app.pkg}$lastStr$inst" + (if (sys) " | 기본앱" else ""))
        }
        val sorted = if (sortBy == "last_used") rows.sortedBy { it.last } else rows.sortedBy { it.label }
        val head = if (usage.isEmpty())
            "(사용 기록 권한이 없어 마지막 사용일을 모름. 필요하면 open_settings page=usage_access 로 '쿠비스'를 허용하게 안내)\n"
        else ""
        return head + "앱 ${sorted.size}개\n" + sorted.joinToString("\n") { it.line }
    }

    private fun openApp(q: String): String {
        val pkg = resolvePkg(q)
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: throw IllegalStateException("이 앱은 열 수 없음")
        return launch(i, "$pkg 열기")
    }

    private fun uninstall(q: String): String {
        val pkg = resolvePkg(q)
        return launch(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")), "$pkg 삭제 확인 창")
    }

    private fun appSettings(q: String): String {
        val pkg = resolvePkg(q)
        return launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")), "$pkg 앱 정보 화면")
    }

    // ---------- 연락·전화·문자 ----------
    private fun findContacts(name: String): String {
        need(Manifest.permission.READ_CONTACTS)
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val out = LinkedHashSet<String>()
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$name%"), null
        )?.use { c ->
            while (c.moveToNext() && out.size < 15) out.add("${c.getString(0)}: ${c.getString(1)}")
        }
        return if (out.isEmpty()) "'$name' 연락처 없음" else out.joinToString("\n")
    }

    private fun call(number: String): String {
        need(Manifest.permission.CALL_PHONE)
        return launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })), "$number 로 전화")
    }

    private fun sms(number: String, msg: String): String {
        need(Manifest.permission.SEND_SMS)
        @Suppress("DEPRECATION")
        val mgr: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)!!
        else SmsManager.getDefault()
        val parts = mgr.divideMessage(msg)
        mgr.sendMultipartTextMessage(number, null, parts, null, null)
        return "문자 보냄: $number"
    }

    // ---------- 기기 ----------
    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw IllegalStateException("손전등이 없음")
        cm.setTorchMode(id, on)
        return if (on) "손전등 켬" else "손전등 끔"
    }

    private fun volume(pct: Int, stream: String): String {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val s = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            else -> AudioManager.STREAM_MUSIC
        }
        val max = am.getStreamMaxVolume(s)
        val v = Math.round(max * pct.coerceIn(0, 100) / 100f)
        try {
            am.setStreamVolume(s, v, AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            throw IllegalStateException("방해 금지 모드라 벨소리 볼륨을 못 바꿈")
        }
        return "$stream 볼륨 $pct%"
    }

    private fun settings(page: String): String {
        val action = when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "dnd" -> "android.settings.ZEN_MODE_SETTINGS"
            "usage_access" -> Settings.ACTION_USAGE_ACCESS_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return launch(Intent(action), "$page 설정 화면")
    }

    // ---------- 할 일·메모 ----------
    private fun todoList(includeDone: Boolean): String {
        val list = store.todos().filter { includeDone || !it.done }
        if (list.isEmpty()) return "할 일 없음"
        return list.sortedWith(compareBy<Todo> { it.done }.thenBy { it.due ?: "9999" }).joinToString("\n") {
            "id=${it.id} | ${it.text}" + (it.due?.let { d -> " | 기한 $d" } ?: "") + (if (it.done) " | 완료" else "")
        }
    }

    private fun memoList(q: String): String {
        val list = store.memos().filter { q.isBlank() || it.text.contains(q, ignoreCase = true) }
        if (list.isEmpty()) return if (q.isBlank()) "메모 없음" else "'$q' 들어간 메모 없음"
        return list.joinToString("\n---\n") { "id=${it.id}" + (if (it.pinned) " (고정)" else "") + "\n" + it.text.take(400) }
    }

    private fun updateEvent(a: JSONObject): String {
        need(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val id = a.getLong("event_id")
        val v = ContentValues()
        a.optString("title", "").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.TITLE, it) }
        a.optString("location", "").takeIf { it.isNotBlank() }?.let { v.put(CalendarContract.Events.EVENT_LOCATION, it) }
        val st = a.optString("start", "")
        if (st.isNotBlank()) {
            val s = parseDt(st)
            val en = a.optString("end", "")
            val e = if (en.isNotBlank()) parseDt(en) else s.plusHours(1)
            v.put(CalendarContract.Events.DTSTART, s.atZone(zone).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.DTEND, e.atZone(zone).toInstant().toEpochMilli())
            v.put(CalendarContract.Events.EVENT_TIMEZONE, zone.id)
            v.put(CalendarContract.Events.ALL_DAY, 0)
        }
        if (v.size() == 0) return "바꿀 내용이 없음"
        val n = cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), v, null, null)
        Bus.changed()
        return if (n > 0) "고침 id=$id" else "그 id의 일정을 못 찾음"
    }

    private fun setDock(arr: JSONArray): String {
        val pkgs = (0 until arr.length()).map { resolvePkg(arr.getString(it)) }.distinct().take(5)
        store.setDock(pkgs)
        Bus.changed()
        return "고정줄: " + pkgs.joinToString(", ")
    }

    private fun status(): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val st = StatFs(Environment.getDataDirectory().path)
        val freeGb = st.availableBytes / 1_000_000_000.0
        val totalGb = st.totalBytes / 1_000_000_000.0
        return "배터리 $pct%" + (if (charging) " (충전 중)" else "") +
                ", 저장공간 남음 %.1fGB / 전체 %.0fGB".format(freeGb, totalGb)
    }
}
