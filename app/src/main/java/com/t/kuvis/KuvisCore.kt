package com.t.kuvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 설정값 (모든 화면이 같이 씀) */
class Prefs(ctx: Context) {
    val p: SharedPreferences = ctx.getSharedPreferences("kuvis", Context.MODE_PRIVATE)
    val key get() = p.getString("key", "") ?: ""
    /** "auto" = 간단한 명령은 Haiku, 나머지는 Sonnet */
    val model get() = p.getString("model", "auto") ?: "auto"
    val rate get() = p.getFloat("rate", 1.1f)
    val useSearch get() = p.getBoolean("search", true)
    var loop: Boolean
        get() = p.getBoolean("loop", true)
        set(v) { p.edit().putBoolean("loop", v).apply() }
    val extra get() = p.getString("extra", "") ?: ""
    val engine get() = p.getString("engine", "") ?: ""
    val voice get() = p.getString("voice", "") ?: ""
    val pitch get() = p.getFloat("pitch", 1.0f)
    /** 대화 창의 스피커 버튼: 소리로 읽기 켜기/끄기 */
    var speak: Boolean
        get() = p.getBoolean("speak", true)
        set(v) { p.edit().putBoolean("speak", v).apply() }
}

/**
 * 쿠비스 두뇌: 음성 인식 → Claude(도구 사용 반복) → 문장 단위 음성 출력.
 * 화면(KuvisPanel)과 Activity 하나에 붙어서 돈다.
 */
class KuvisCore(private val act: Activity, private val panel: KuvisPanel, private val listener: Listener) {

    interface Listener {
        /** 대화가 멈춤. ended=true 이면 대화가 끝난 것(그만, 다른 앱으로 이동 등) */
        fun onIdle(ended: Boolean)
    }

    private val main = Handler(Looper.getMainLooper())
    val prefs = Prefs(act)
    val phone = PhoneTools(act)

    var state = "idle"; private set
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var startWhenReady = false
    private var utterSeq = 0
    private var pendingUtter = 0
    private var streamDone = false

    private val history = mutableListOf<Pair<String, String>>()
    private val maxHistory = 24

    private var gen = 0
    private var conn: HttpURLConnection? = null
    private var turnText = StringBuilder()
    private var turnActive = false
    private var needSpace = false
    private var turnModel = "claude-sonnet-5-5"

    /** 간단한 명령인지 대충 가늠 (짧고 명령어가 들어 있음) */
    private fun pickModel(userText: String): String {
        val m = prefs.model
        if (m != "auto") return m
        val t = userText.replace(" ", "")
        val simple = t.length <= 30 && Regex("(켜|꺼|알람|타이머|열어|넣어|추가|메모|할일|완료|끝났|일정|볼륨|손전등|배터리|날씨|비와|몇시|전화|문자|숨겨|옮겨|보여|틀어|지워)").containsMatchIn(t)
        return if (simple) "claude-haiku-5-5" else "claude-sonnet-5-5"
    }
    private var splitter = Splitter { }
    var askingPermission = false; private set
    /** 대화 창이 가려졌을 때(다른 앱·알림창) 다시 듣기를 멈춤 */
    var halted = false; private set
    private var listenRetries = 0

    init {
        loadHistory()
        panel.greet()
        panel.micBtn.setOnClickListener { tap() }
        panel.onSend = { t -> interrupt(); handleUser(t) }
        panel.setBtn.setOnClickListener { interrupt(); setState("idle"); SettingsActivity.open(act) }
        panel.setSpeaker(prefs.speak)
        panel.speakerBtn.setOnClickListener {
            prefs.speak = !prefs.speak
            panel.setSpeaker(prefs.speak)
            if (!prefs.speak && state == "speaking") {
                stopSpeaking()
                if (streamDone) finishTurn()
            }
        }
        panel.setModel(modelName(prefs.model))
        panel.modelBtn.setOnClickListener { chooseModel() }

        val onInit = TextToSpeech.OnInitListener { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
                // 설정에서 고른 목소리
                try { tts?.voices?.firstOrNull { it.name == prefs.voice }?.let { tts?.voice = it } } catch (_: Exception) {}
                ttsReady = true
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { main.post { utterFinished() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { main.post { utterFinished() } }
                })
            } else {
                main.post { panel.showError("음성 출력(TTS)을 쓸 수 없어요. 쿠비스 설정 > 목소리에서 다른 엔진을 골라 주세요.") }
            }
            if (startWhenReady) { startWhenReady = false; main.post { start() } }
        }
        tts = if (prefs.engine.isBlank()) TextToSpeech(act, onInit) else TextToSpeech(act, onInit, prefs.engine)
    }

    private val modelIds = listOf("auto", "claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
    private fun modelName(id: String) = when (id) {
        "claude-haiku-5-5" -> "Haiku"; "claude-sonnet-5-5" -> "Sonnet"; "claude-opus-5-5" -> "Opus"; else -> "자동"
    }

    private fun chooseModel() {
        val names = arrayOf("자동 — 간단한 건 Haiku, 나머지는 Sonnet", "Haiku — 가장 빠르고 저렴", "Sonnet — 균형", "Opus — 가장 똑똑, 비쌈")
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setSingleChoiceItems(names, modelIds.indexOf(prefs.model).coerceAtLeast(0)) { d, w ->
                prefs.p.edit().putString("model", modelIds[w]).apply()
                panel.setModel(modelName(modelIds[w]))
                d.dismiss()
            }
            .show()
    }

    /** 대화 창이 가려짐: 하던 것 멈추고 기다림 */
    fun halt() { halted = true; interrupt(); setState("idle") }

    /** 다시 보임: 바로 듣기 */
    fun unhalt() { if (!halted) return; halted = false; if (!askingPermission) start() }

    // =====================================================================
    // 바깥에서 부르는 것
    fun start() {
        if (prefs.key.isBlank()) { SettingsActivity.open(act); return }
        halted = false
        listenRetries = 0
        startListening()
    }

    /** TTS 준비가 끝나면 바로 듣기 (측면 버튼으로 열었을 때) */
    fun startSoon() { if (ttsReady) start() else startWhenReady = true }

    fun tap() {
        when (state) {
            "listening" -> recognizer?.stopListening()
            "speaking", "thinking" -> { interrupt(); startListening() }
            else -> start()
        }
    }

    fun destroy() {
        interrupt()
        recognizer?.destroy(); recognizer = null
        tts?.shutdown()
    }

    fun onPermissionsResult(code: Int, results: IntArray) {
        askingPermission = false
        if (code == 2 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) {
            speakQuick("허용됐어요. 다시 말씀해 주세요.")
        }
        if (code == 1 && hasMic()) start()
    }

    // =====================================================================
    private fun setState(s: String, msg: String? = null) {
        state = s
        panel.setState(s, msg)
    }

    private fun goIdle(msg: String? = null, ended: Boolean = false) {
        setState("idle", msg)
        listener.onIdle(ended)
    }

    private fun hasMic() = act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // =====================================================================
    // 음성 인식 — 쿠비스가 '디지털 어시스턴트'가 되면 기본 인식기가 쿠비스로 바뀌므로 구글 인식기를 직접 고른다
    private fun makeRecognizer(): SpeechRecognizer? {
        val services = act.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != act.packageName }
        val pick = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.contains("google") }
            ?: services.firstOrNull()
            ?: return null
        return SpeechRecognizer.createSpeechRecognizer(act, pick)
    }

    private fun startListening() {
        if (!hasMic()) {
            askingPermission = true
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        stopSpeaking()
        recognizer?.destroy()
        val r = makeRecognizer()
        if (r == null) {
            panel.showError("음성 인식 엔진이 없어요. Play 스토어에서 'Google' 앱을 설치·업데이트해 주세요.")
            goIdle()
            return
        }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { listenRetries = 0 }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { if (state == "listening") panel.setLevel(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) panel.setInterim(t)
            }
            override fun onResults(b: Bundle?) {
                if (state != "listening") return
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (t.isNotEmpty()) handleUser(t) else relisten(250)
            }
            override fun onError(error: Int) {
                if (state != "listening") return
                when (error) {
                    // 말이 없으면 꺼지지 않고 계속 기다림
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> relisten(250)
                    SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                        if (++listenRetries <= 4) relisten(900L * listenRetries)
                        else goIdle("마이크가 잠깐 멈췄어요. 마이크를 눌러 주세요")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> goIdle("마이크 권한을 허용해 주세요")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        goIdle("인터넷 연결을 확인해 주세요")
                    else -> { ErrorLog.add(act, "음성 인식 오류", "code=$error"); goIdle("음성 인식이 멈췄어요 ($error). 마이크를 눌러 주세요") }
                }
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, act.packageName)
        }
        setState("listening")
        r.startListening(intent)
    }

    /** 조용히 다시 듣기 (다시 시작할 때 나는 '띵' 소리는 잠깐 줄임) */
    private fun relisten(delay: Long) {
        main.postDelayed({
            if (halted || state != "listening" || askingPermission) return@postDelayed
            muteBeep(true)
            startListening()
            main.postDelayed({ muteBeep(false) }, 700)
        }, delay)
    }

    private var savedVol = -1
    private fun muteBeep(on: Boolean) {
        try {
            val am = act.getSystemService(android.media.AudioManager::class.java)
            if (on) {
                if (am.isMusicActive || savedVol >= 0) return
                savedVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
            } else if (savedVol >= 0) {
                am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVol, 0); savedVol = -1
            }
        } catch (_: Exception) {}
    }

    private fun stopListening() {
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    // =====================================================================
    // 음성 출력
    private fun cleanForSpeech(t: String): String =
        t.replace(Regex("```[\\s\\S]*?```"), " 코드는 화면에 적어 두었어요. ")
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*#_`>|~]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun enqueueSpeech(sentence: String) {
        val s = cleanForSpeech(sentence)
        if (s.isEmpty() || !ttsReady || !prefs.speak) return
        muteBeep(false)
        if (state != "speaking") setState("speaking")
        pendingUtter++
        tts?.setSpeechRate(prefs.rate)
        tts?.setPitch(prefs.pitch)
        tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "u${utterSeq++}")
    }

    private fun utterFinished() {
        if (pendingUtter > 0) pendingUtter--
        if (pendingUtter == 0 && streamDone) finishTurn()
    }

    private fun stopSpeaking() {
        pendingUtter = 0
        try { tts?.stop() } catch (_: Exception) {}
    }

    private fun speakQuick(t: String) {
        if (!ttsReady || !prefs.speak) return
        muteBeep(false)
        stopSpeaking()
        tts?.setSpeechRate(prefs.rate)
        tts?.setPitch(prefs.pitch)
        tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "quick")
    }

    private fun finishTurn() {
        if (state != "speaking" && state != "thinking") return
        streamDone = false
        val perms = synchronized(phone.pendingPerms) { phone.pendingPerms.toTypedArray().also { phone.pendingPerms.clear() } }
        val launches = synchronized(phone.pendingLaunches) { phone.pendingLaunches.toList().also { phone.pendingLaunches.clear() } }
        if (perms.isNotEmpty()) {
            setState("idle")
            askingPermission = true
            act.requestPermissions(perms, 2)
            return
        }
        if (launches.isNotEmpty()) {
            var leaves = false
            for ((i, l) in launches) {
                try { act.startActivity(i); if (l) leaves = true }
                catch (e: Exception) { panel.showError("실행하지 못했어요: ${e.message}") }
            }
            if (leaves) { goIdle(ended = true); return }
        }
        // 대화 창이 열려 있는 동안은 계속 들음 (X를 눌러야 끝남)
        if (halted) goIdle() else startListening()
    }

    // =====================================================================
    // 사용자 말
    private fun handleUser(text: String) {
        val t = text.trim()
        if (Regex("^(그만|됐어|대화 ?끝|끝내자|고마워|잘 ?자)[.!]?$").matches(t)) {
            panel.showUser(t)
            goIdle("대화를 멈췄어요", ended = true)
            return
        }
        if (Regex("^새 ?대화[.!]?$").matches(t)) { newChat(); speakQuick("새 대화를 시작할게요."); return }
        if (prefs.key.isBlank()) { SettingsActivity.open(act); return }
        history.add("user" to t)
        panel.showUser(t)
        askClaude()
    }

    fun newChat() {
        interrupt()
        history.clear(); saveHistory(); panel.greet(); setState("idle")
    }

    fun interrupt() {
        gen++
        val c = conn; conn = null
        if (c != null) Thread { try { c.disconnect() } catch (_: Exception) {} }.start()
        if (turnActive) {
            turnActive = false
            val partial = turnText.toString()
            if (partial.isNotBlank()) {
                history.add("assistant" to "$partial …(중단됨)")
            } else {
                if (history.isNotEmpty() && history.last().first == "user") history.removeAt(history.size - 1)
            }
            saveHistory()
        }
        stopSpeaking()
        stopListening()
        streamDone = false
    }

    // =====================================================================
    // Claude
    private fun systemPrompt(): String {
        val fmt = SimpleDateFormat("yyyy년 M월 d일 EEEE a h시 m분", Locale.KOREAN)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Seoul")
        val lines = mutableListOf(
            "너는 T의 개인 음성 비서 '쿠비스'다. T의 안드로이드 휴대폰 홈 화면(런처)이자 비서로 돌아가고, 답은 소리로 읽힌다.",
            "규칙: 한국어로, 말하듯 짧고 자연스럽게 답한다. 보통 1~3문장. 목록·표·마크다운·이모지·URL을 쓰지 않는다.",
            "긴 설명이 필요하면 핵심부터 말하고 '더 자세히 말씀드릴까요?'처럼 묻는다.",
            "사용자를 'T'라고 부르되 매번 부르지는 않는다.",
            "음성 인식으로 받은 말이라 잘못 들린 단어가 있을 수 있으니 뜻을 짐작하고, 정말 모호할 때만 되묻는다.",
            "홈 화면은 세 페이지다. 왼쪽: 달력(구글 캘린더 일정), 가운데: 날씨·시계·할 일, 오른쪽: 메모. 아래에 고정 앱 다섯 개.",
            "일정은 캘린더 도구, 할 일은 todo 도구, 저장해 둘 글·생각·번호 같은 건 memo 도구를 쓴다. 할 일과 메모가 헷갈리면: 해야 하는 일이면 할 일, 기억해 둘 내용이면 메모.",
            "시키는 일은 도구로 직접 한다. 도구를 쓰기 전에는 말하지 말고 바로 쓰고, 끝난 뒤 결과를 한두 문장으로 말한다. 바꾼 페이지를 보여 주면 좋을 때는 show_page를 쓴다.",
            "전화 걸기, 문자 보내기, 일정·할 일·메모 지우기는 실행 전에 무엇을 하는지 말하고 '할까요?'로 확인받는다. 사용자가 그러라고 하면 그때 실행한다.",
            "일정·할 일·메모 추가, 완료 표시, 알람·타이머, 앱 열기, 손전등 같은 건 확인 없이 바로 한다. 날짜와 시간은 아래 현재 시각을 기준으로 계산한다.",
            "'쿠비스 설정'·'목소리 설정'처럼 쿠비스 자체 설정을 말하면 open_kuvis_settings를 쓴다. 휴대폰의 음성 출력(TTS) 엔진 설정은 open_settings page=tts.",
            "날씨는 get_weather를 먼저 쓰고, 그걸로 모자랄 때만 웹 검색을 쓴다.",
            "앱 정리: 전체 앱 서랍(홈에서 위로 쓸기)은 폴더로 나뉘어 있다. 폴더 옮기기·이름 바꾸기는 list_folders, move_apps_to_folder, rename_folder로 바로 한다. 안 쓰는 앱 지우기는 list_apps(last_used)로 찾아 말해 주고, 지울지 물은 뒤 uninstall_app으로 삭제 창을 띄운다.",
            "성경 구절은 개역개정 본문이 확실할 때만 그대로 인용하고, 확실하지 않으면 장절과 내용 요약만 말한다.",
            "현재 시각: ${fmt.format(Date())} (한국)."
        )
        if (prefs.extra.isNotBlank()) lines.add("T가 알려 둔 것: ${prefs.extra}")
        val mem = Store(act).memory()
        if (mem.isNotEmpty()) lines.add("T에 대해 기억하고 있는 것(필요할 때만 자연스럽게 쓴다):\n" + mem.joinToString("\n") { "- $it" })
        lines.add("T가 '기억해 줘'라고 하거나 앞으로도 알아야 할 사실을 말하면 remember 도구로 저장한다. 잊어 달라고 하면 forget.")
        lines.add("T가 쿠비스에 새 기능이나 고칠 점을 말하면(예: '이런 기능 있으면 좋겠어') feature_request로 적고, Claude 앱에서 처리되면 업데이트로 나온다고 말한다.")
        lines.add("알림(카톡 등) 읽기·답장: read_notifications, reply_notification (별도 '알림 도우미' 앱이 있어야 함). 답장은 보내기 전에 누구에게 무엇을 보낼지 말하고 확인받는다.")
        return lines.joinToString("\n")
    }

    private fun startMessages(): JSONArray {
        val recent = history.takeLast(maxHistory).toMutableList()
        while (recent.isNotEmpty() && recent.first().first != "user") recent.removeAt(0)
        val msgs = JSONArray()
        for ((r, c) in recent) msgs.put(JSONObject().put("role", r).put("content", c))
        return msgs
    }

    private fun requestBody(msgs: JSONArray): String {
        val tools = phone.definitions()
        if (prefs.useSearch) {
            tools.put(
                JSONObject()
                    .put("type", "web_search_20250305")
                    .put("name", "web_search")
                    .put("max_uses", 3)
                    .put("user_location", JSONObject()
                        .put("type", "approximate").put("city", "Gunsan")
                        .put("country", "KR").put("timezone", "Asia/Seoul"))
            )
        }
        return JSONObject()
            .put("model", turnModel)
            .put("max_tokens", 2000)
            .put("system", systemPrompt())
            .put("messages", msgs)
            .put("tools", tools)
            .put("stream", true)
            .toString()
    }

    private class StreamResult(val content: JSONArray, val stopReason: String)

    private fun streamOnce(msgs: JSONArray, myGen: Int, apiKey: String): StreamResult? {
        val body = requestBody(msgs)
        val cc = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        main.post { if (gen == myGen) conn = cc }
        try {
            cc.requestMethod = "POST"
            cc.doOutput = true
            cc.connectTimeout = 15000
            cc.readTimeout = 90000
            cc.setRequestProperty("content-type", "application/json")
            cc.setRequestProperty("x-api-key", apiKey)
            cc.setRequestProperty("anthropic-version", "2023-06-01")
            cc.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = cc.responseCode
            if (code != 200) {
                val raw = cc.errorStream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
                val m = try { JSONObject(raw).optJSONObject("error")?.optString("message") } catch (_: Exception) { null }
                throw RuntimeException("HTTP $code ${m ?: ""}")
            }
            val blocks = sortedMapOf<Int, JSONObject>()
            val partial = HashMap<Int, StringBuilder>()
            var stop = "end_turn"
            val reader = cc.inputStream.bufferedReader(Charsets.UTF_8)
            while (true) {
                if (gen != myGen) return null
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val j = try { JSONObject(line.substring(5).trim()) } catch (_: Exception) { continue }
                when (j.optString("type")) {
                    "message_start" -> {
                        val u = j.optJSONObject("message")?.optJSONObject("usage")
                        if (u != null) Usage.add(act, turnModel, u.optLong("input_tokens") + u.optLong("cache_read_input_tokens") + u.optLong("cache_creation_input_tokens"), 0)
                    }
                    "content_block_start" -> {
                        val idx = j.getInt("index")
                        val b = JSONObject(j.getJSONObject("content_block").toString())
                        blocks[idx] = b
                        val t = b.optString("type")
                        if (t == "server_tool_use") main.post { if (gen == myGen && state != "speaking") setState("thinking", "검색 중…") }
                        if (t == "tool_use") {
                            val label = phone.label(b.optString("name"))
                            main.post { if (gen == myGen && state != "speaking") setState("thinking", label) }
                        }
                    }
                    "content_block_delta" -> {
                        val idx = j.getInt("index")
                        val b = blocks[idx] ?: continue
                        val d = j.optJSONObject("delta") ?: continue
                        when (d.optString("type")) {
                            "text_delta" -> {
                                val t = d.optString("text")
                                b.put("text", b.optString("text") + t)
                                main.post { if (gen == myGen) appendSpoken(t) }
                            }
                            "input_json_delta" ->
                                partial.getOrPut(idx) { StringBuilder() }.append(d.optString("partial_json"))
                            // 모델이 답하기 전에 생각한 내용: 다음 요청에 그대로 돌려줘야 함
                            "thinking_delta" -> b.put("thinking", b.optString("thinking") + d.optString("thinking"))
                            "signature_delta" -> b.put("signature", b.optString("signature") + d.optString("signature"))
                            "citations_delta" -> {
                                val arr = b.optJSONArray("citations") ?: JSONArray().also { b.put("citations", it) }
                                d.optJSONObject("citation")?.let { arr.put(it) }
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val idx = j.getInt("index")
                        val sb = partial.remove(idx)
                        if (sb != null && sb.isNotBlank()) {
                            blocks[idx]?.put("input", try { JSONObject(sb.toString()) } catch (_: Exception) { JSONObject() })
                        }
                    }
                    "message_delta" -> {
                        j.optJSONObject("usage")?.let { u -> Usage.add(act, turnModel, 0, u.optLong("output_tokens")) }
                        val sr = j.optJSONObject("delta")?.optString("stop_reason") ?: ""
                        if (sr.isNotBlank() && sr != "null") stop = sr
                    }
                    "error" -> throw RuntimeException(j.optJSONObject("error")?.optString("message") ?: "API 오류")
                }
            }
            val content = JSONArray()
            for (b in blocks.values) {
                val bt = b.optString("type")
                if (bt == "text" && b.optString("text").isBlank()) continue
                // 내용이 빈 생각 블록은 다시 보내면 거절되므로 뺌
                if (bt == "thinking" && (b.optString("thinking").isBlank() || b.optString("signature").isBlank())) continue
                content.put(b)
            }
            return StreamResult(content, stop)
        } finally {
            try { cc.disconnect() } catch (_: Exception) {}
        }
    }

    private fun appendSpoken(t: String) {
        if (turnText.isNotEmpty() && t.isNotEmpty() && needSpace) {
            turnText.append(' '); splitter.push(" ")
        }
        needSpace = false
        turnText.append(t)
        panel.showReply(turnText.toString().replace("**", ""))
        splitter.push(t)
    }

    private fun askClaude() {
        stopSpeaking()
        setState("thinking")
        streamDone = false
        val myGen = ++gen
        val apiKey = prefs.key
        turnModel = pickModel(history.lastOrNull { it.first == "user" }?.second ?: "")
        val msgs = startMessages()

        turnText = StringBuilder()
        turnActive = true
        needSpace = false
        splitter = Splitter { s -> if (gen == myGen) enqueueSpeech(s) }
        phone.pendingLaunches.clear()
        phone.pendingPerms.clear()

        Thread {
            var err: String? = null
            try {
                var rounds = 0
                while (rounds++ < 10) {
                    val r = streamOnce(msgs, myGen, apiKey) ?: return@Thread
                    msgs.put(JSONObject().put("role", "assistant").put("content", r.content))
                    if (r.stopReason == "pause_turn") continue
                    if (r.stopReason != "tool_use") break
                    val results = JSONArray()
                    for (k in 0 until r.content.length()) {
                        val b = r.content.getJSONObject(k)
                        if (b.optString("type") != "tool_use") continue
                        val res = JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id"))
                        try {
                            res.put("content", phone.execute(b.optString("name"), b.optJSONObject("input") ?: JSONObject()))
                        } catch (e: Exception) {
                            if (e !is SecurityException) ErrorLog.add(act, "도구 실패: ${b.optString("name")}", (e.message ?: e.toString()) + "\n입력: " + (b.optJSONObject("input")?.toString()?.take(500) ?: ""))
                            res.put("content", "실패: " + (e.message ?: e.toString())).put("is_error", true)
                        }
                        results.put(res)
                    }
                    if (gen != myGen) return@Thread
                    main.post { if (gen == myGen) needSpace = true }
                    msgs.put(JSONObject().put("role", "user").put("content", results))
                }
            } catch (e: Exception) {
                err = e.message ?: e.toString()
            }
            val finalErr = err
            main.post { onStreamEnd(myGen, finalErr) }
        }.start()
    }

    private fun onStreamEnd(myGen: Int, err: String?) {
        if (gen != myGen) return
        conn = null
        turnActive = false
        if (err != null && turnText.isBlank()) {
            if (history.isNotEmpty() && history.last().first == "user") history.removeAt(history.size - 1)
            val msg = when {
                err.contains("401") -> "API 키가 맞지 않아요. 설정에서 확인해 주세요."
                err.contains("429") -> "요청이 너무 많거나 한도에 걸렸어요. 잠시 후 다시 해 주세요."
                err.contains("credit", true) || err.contains("balance", true) ->
                    "API 크레딧이 부족해요. console.anthropic.com에서 충전해 주세요."
                err.contains("Unable to resolve host") || err.contains("timeout", true) -> "인터넷 연결을 확인해 주세요."
                else -> "오류: $err"
            }
            panel.showError(msg)
            ErrorLog.add(act, "Claude 호출 실패 ($turnModel)", err)
            speakQuick(msg.substringBefore("."))
            goIdle()
            return
        }
        if (err != null) ErrorLog.add(act, "Claude 답 중간 오류 ($turnModel)", err)
        splitter.flush()
        var full = turnText.toString()
        if (full.isBlank()) { full = "됐어요."; panel.showReply(full); enqueueSpeech(full) }
        history.add("assistant" to full)
        saveHistory()
        history.lastOrNull { it.first == "user" }?.second?.let { u -> try { Journal.add(act, u, full) } catch (_: Exception) {} }
        streamDone = true
        if (pendingUtter == 0) finishTurn()
    }

    // =====================================================================
    private fun saveHistory() {
        val arr = JSONArray()
        for ((r, c) in history.takeLast(60)) arr.put(JSONObject().put("role", r).put("content", c))
        prefs.p.edit().putString("history", arr.toString()).putLong("historyAt", System.currentTimeMillis()).apply()
    }

    /** 화면은 매번 새로 시작하지만, 앞 대화 흐름은 이어서 기억함 (6시간 넘게 쉬었으면 새 흐름) */
    private fun loadHistory() {
        history.clear()
        if (System.currentTimeMillis() - prefs.p.getLong("historyAt", 0L) > 6 * 3600_000L) return
        try {
            val arr = JSONArray(prefs.p.getString("history", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(o.getString("role") to o.getString("content"))
            }
        } catch (_: Exception) {}
    }
}

/** 스트리밍 글자를 문장 단위로 잘라 바로 읽게 함. 숫자 사이 마침표(3.5)는 자르지 않음. */
class Splitter(private val onSentence: (String) -> Unit) {
    private val buf = StringBuilder()

    fun push(t: String) {
        buf.append(t)
        while (true) {
            val i = findCut()
            if (i < 0) break
            val s = buf.substring(0, i + 1)
            buf.delete(0, i + 1)
            if (s.isNotBlank()) onSentence(s)
        }
    }

    fun flush() {
        if (buf.isNotBlank()) onSentence(buf.toString())
        buf.setLength(0)
    }

    private fun findCut(): Int {
        for (i in 0 until buf.length - 1) {
            val ch = buf[i]
            if (ch == '\n') return i
            if ((ch == '.' || ch == '!' || ch == '?' || ch == '…') && buf[i + 1].isWhitespace()) return i
        }
        return -1
    }
}

/** 이번 달 모델별 토큰 사용량 */
object Usage {
    private fun month() = SimpleDateFormat("yyyy-MM", Locale.KOREA).format(Date())

    @Synchronized
    fun add(ctx: Context, model: String, input: Long, output: Long) {
        if (input == 0L && output == 0L) return
        val p = Prefs(ctx).p
        val k = "usage_${month()}_$model"
        val o = try { JSONObject(p.getString(k, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        o.put("in", o.optLong("in") + input).put("out", o.optLong("out") + output)
        if (input > 0) o.put("calls", o.optLong("calls") + 1)
        p.edit().putString(k, o.toString()).apply()
    }

    /** 사람이 읽을 요약 */
    fun summary(ctx: Context): String {
        val p = Prefs(ctx).p
        val m = month()
        val lines = listOf("claude-haiku-5-5" to "Haiku", "claude-sonnet-5-5" to "Sonnet", "claude-opus-5-5" to "Opus").mapNotNull { (id, name) ->
            val s = p.getString("usage_${m}_$id", null) ?: return@mapNotNull null
            val o = try { JSONObject(s) } catch (_: Exception) { return@mapNotNull null }
            "$name: 호출 ${o.optLong("calls")}번 · 읽은 양 ${"%,d".format(o.optLong("in"))} · 쓴 양 ${"%,d".format(o.optLong("out"))} 토큰"
        }
        return if (lines.isEmpty()) "이번 달(${m}) 아직 사용 기록이 없어요." else "이번 달(${m})\n" + lines.joinToString("\n")
    }
}
