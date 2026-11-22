package com.t.kuvis

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/**
 * 기억장: GitHub 비공개 저장소(anlf20-12/kuvis-memory)를 Claude 앱과 함께 씀.
 * - 기억.md  : '- '로 시작하는 한 줄 = 기억 하나
 * - 일지/YYYY-MM.md : 하루 대화 요약
 * - 요청.md  : 쿠비스 기능 요청
 */
object MemorySync {
    const val REPO = "anlf20-12/kuvis-memory"
    private const val MEM = "기억.md"
    private const val REQ = "요청.md"

    private const val MEM_HEADER = "# 쿠비스 기억장\n\n" +
            "T에 대해 쿠비스(휴대폰)와 Claude(앱)가 함께 기억하는 것. 한 줄에 하나씩 `- `로 적어요.\n" +
            "쿠비스에게 \"기억해 줘\"라고 하거나, Claude에게 \"기억장에 적어 줘\"라고 하면 여기 쌓여요.\n\n"

    fun token(ctx: Context) = Prefs(ctx).p.getString("ghToken", "") ?: ""
    fun enabled(ctx: Context) = token(ctx).isNotBlank()

    // ---------- GitHub 파일 읽고 쓰기 ----------
    private fun enc(path: String) = path.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    private fun call(ctx: Context, method: String, url: String, body: JSONObject? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Bearer ${token(ctx)}")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "kuvis-app")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
            code to text
        } finally { c.disconnect() }
    }

    /** (내용, sha). 파일이 없으면 null */
    fun getFile(ctx: Context, path: String): Pair<String, String>? {
        val (code, text) = call(ctx, "GET", "https://api.github.com/repos/$REPO/contents/${enc(path)}")
        if (code == 404) return null
        if (code !in 200..299) throw IllegalStateException(errorText(code, text))
        val o = JSONObject(text)
        val raw = String(Base64.decode(o.getString("content").replace("\n", ""), Base64.DEFAULT), Charsets.UTF_8)
        return raw to o.getString("sha")
    }

    fun putFile(ctx: Context, path: String, content: String, sha: String?, message: String) {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
        if (sha != null) body.put("sha", sha)
        val (code, text) = call(ctx, "PUT", "https://api.github.com/repos/$REPO/contents/${enc(path)}", body)
        if (code !in 200..299) throw IllegalStateException(errorText(code, text))
    }

    private fun errorText(code: Int, text: String): String = when (code) {
        401 -> "기억장 토큰이 맞지 않아요 (쿠비스 설정에서 다시 넣기)"
        403 -> "기억장 토큰에 쓰기 권한이 없어요 (Contents: Read and write)"
        404 -> "기억장 저장소를 못 찾았어요 (토큰에 kuvis-memory 접근을 줬는지 확인)"
        409 -> "동시에 고쳐져서 충돌했어요. 다시 해 주세요"
        else -> "기억장 오류 $code ${try { JSONObject(text).optString("message") } catch (_: Exception) { "" }}"
    }

    /** 연결 확인 */
    fun test(ctx: Context): String {
        val (code, text) = call(ctx, "GET", "https://api.github.com/repos/$REPO")
        if (code !in 200..299) throw IllegalStateException(errorText(code, text))
        return "연결됐어요: $REPO"
    }

    // ---------- 기억 ----------
    private fun parse(md: String) = md.lines().filter { it.trimStart().startsWith("- ") }.map { it.trimStart().removePrefix("- ").trim() }.filter { it.isNotEmpty() }

    /** 기억장에서 받아 와 휴대폰 기억을 맞춤 */
    fun pullMemory(ctx: Context): List<String> {
        val f = getFile(ctx, MEM) ?: return Store(ctx).memory()
        val list = parse(f.first)
        Store(ctx).setMemory(list)
        return list
    }

    /** 기억장 최신 내용에 change를 적용해 저장 (Claude가 고친 것도 살림) */
    fun editMemory(ctx: Context, why: String, change: (List<String>) -> List<String>): List<String> {
        repeat(2) { attempt ->
            val f = getFile(ctx, MEM)
            val cur = f?.let { parse(it.first) } ?: Store(ctx).memory()
            val next = change(cur)
            // 맨 위 설명은 원래 파일 것을 유지
            val header = f?.first?.let { md -> md.substringBefore("\n- ").let { h -> if (h == md) MEM_HEADER else h.trimEnd() + "\n\n" } } ?: MEM_HEADER
            try {
                putFile(ctx, MEM, header + next.joinToString("\n") { "- $it" } + "\n", f?.second, "쿠비스: $why")
                Store(ctx).setMemory(next)
                return next
            } catch (e: IllegalStateException) {
                if (attempt == 1 || e.message?.contains("충돌") != true) throw e
            }
        }
        return Store(ctx).memory()
    }

    // ---------- 요청 ----------
    fun addRequest(ctx: Context, text: String) {
        val today = LocalDate.now().toString()
        val f = getFile(ctx, REQ)
        val base = f?.first ?: "# 쿠비스 고칠 것·넣을 것\n\n"
        putFile(ctx, REQ, base.trimEnd() + "\n- [ ] $today · $text\n", f?.second, "쿠비스 요청: ${text.take(40)}")
    }

    // ---------- 일지 ----------
    fun appendJournal(ctx: Context, day: LocalDate, summary: String) {
        val path = "일지/" + day.format(DateTimeFormatter.ofPattern("yyyy-MM")) + ".md"
        val title = "## " + day.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN))
        val f = getFile(ctx, path)
        val base = f?.first ?: ("# " + day.format(DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREAN)) + " 쿠비스 일지\n")
        putFile(ctx, path, base.trimEnd() + "\n\n$title\n${summary.trim()}\n", f?.second, "쿠비스 일지 ${day}")
    }
}

/** 하루 대화를 모아 두었다가 다음 날 요약해서 일지에 남김 */
object Journal {
    private fun store(ctx: Context) = Store(ctx)

    /** 한 번 주고받은 대화 적기 */
    fun add(ctx: Context, user: String, reply: String) {
        val s = store(ctx)
        val today = LocalDate.now().toString()
        val day = s.rawGet("logDay")
        if (day != null && day != today) moveToPending(ctx, day)
        val time = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())
        val old = if (day == today) s.rawGet("logText") ?: "" else ""
        s.rawPut("logDay", today)
        s.rawPut("logText", (old + "[$time] T: ${user.take(500)}\n쿠비스: ${reply.take(800)}\n").takeLast(40_000))
    }

    private fun moveToPending(ctx: Context, day: String) {
        val s = store(ctx)
        val text = s.rawGet("logText") ?: ""
        if (text.isNotBlank()) {
            val arr = try { JSONArray(s.rawGet("logPending") ?: "[]") } catch (_: Exception) { JSONArray() }
            arr.put(JSONObject().put("day", day).put("text", text))
            s.rawPut("logPending", arr.toString())
        }
        s.rawPut("logText", "")
        s.rawPut("logDay", LocalDate.now().toString())
    }

    /** 지난 날 대화를 요약해서 기억장 일지에 올림 (백그라운드에서 부를 것) */
    fun flush(ctx: Context) {
        if (!MemorySync.enabled(ctx) || Prefs(ctx).key.isBlank()) return
        val s = store(ctx)
        val day = s.rawGet("logDay")
        if (day != null && day != LocalDate.now().toString()) moveToPending(ctx, day)
        val arr = try { JSONArray(s.rawGet("logPending") ?: "[]") } catch (_: Exception) { return }
        if (arr.length() == 0) return
        val left = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            try {
                val summary = summarize(ctx, o.getString("text"))
                MemorySync.appendJournal(ctx, LocalDate.parse(o.getString("day")), summary)
            } catch (e: Exception) {
                ErrorLog.add(ctx, "일지 올리기 실패", e.toString())
                left.put(o)
            }
        }
        s.rawPut("logPending", left.toString())
    }

    /** 가장 싼 모델로 짧게 요약 (스트리밍 없이) */
    private fun summarize(ctx: Context, log: String): String {
        val body = JSONObject()
            .put("model", "claude-haiku-5-5")
            .put("max_tokens", 600)
            .put("system", "다음은 T와 휴대폰 비서 쿠비스가 하루 동안 나눈 대화다. 나중에 T와 Claude가 다시 볼 수 있게 한국어로 3~8줄 요약하라. " +
                    "한 일, 정한 것, 남은 일 위주로. 각 줄은 '- '로 시작. 비밀번호·카드·계좌 번호 같은 건 적지 않는다.")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", log.takeLast(30_000))))
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", Prefs(ctx).key)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) throw IllegalStateException("요약 실패 ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            j.optJSONObject("usage")?.let { u -> Usage.add(ctx, "claude-haiku-5-5", u.optLong("input_tokens"), u.optLong("output_tokens")) }
            val content = j.getJSONArray("content")
            val sb = StringBuilder()
            for (i in 0 until content.length()) content.getJSONObject(i).optString("text").let { sb.append(it) }
            return sb.toString().ifBlank { "- (요약 없음)" }
        } finally { c.disconnect() }
    }
}
