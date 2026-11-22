package com.t.kuvis

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.AdapterView
import java.util.Locale

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** 쿠비스 설정 화면 (앱 목록에 '쿠비스 설정'으로 따로 보임) */
class SettingsActivity : Activity() {

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val PERMS = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }

    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var voiceList: LinearLayout
    private lateinit var keyEt: EditText
    private lateinit var extraEt: EditText
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = C.bg
        window.navigationBarColor = C.bg

        val scroll = ScrollView(this).apply { setBackgroundColor(C.bg); fitsSystemWindows = true }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
        }
        scroll.addView(box)
        setContentView(scroll)

        box.addView(txt("쿠비스 설정", 24f, C.text, bold = true))
        box.addView(txt("바꾼 내용은 바로 저장돼요.", 13f, C.dim).apply { setPadding(0, dp(4), 0, 0) })

        // 1. 기본 설정
        setupBox = section("휴대폰에 쿠비스 연결")
        // 2. Claude
        buildClaude(section("Claude (두뇌)"))
        // 3. 목소리
        buildVoice(section("목소리와 대화"))
        // 4. 할 수 있는 일
        buildAbilities(section("쿠비스가 할 수 있는 일"))
        // 5. 기타
        buildEtc(section("기타"))

        startTts()
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onPause() {
        super.onPause()
        saveTexts()
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        refreshSetup()
    }

    private fun saveTexts() {
        prefs.p.edit()
            .putString("key", keyEt.text.toString().trim())
            .putString("extra", extraEt.text.toString().trim())
            .apply()
    }

    // =====================================================================
    // 화면 조각
    private fun section(title: String): LinearLayout {
        box.addView(txt(title, 15f, C.accent, bold = true).apply { setPadding(dp(4), dp(24), 0, dp(8)) })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.panel, dpf(18f), dp(1), C.line)
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        box.addView(c, LinearLayout.LayoutParams(MATCH, WRAP))
        return c
    }

    private fun label(t: String) = txt(t, 13f, C.dim).apply { setPadding(0, dp(14), 0, dp(4)) }

    private fun button(t: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) Color.parseColor("#04121F") else C.text)
        background = rounded(if (primary) C.accent else C.line, dpf(12f))
        minHeight = 0; minimumHeight = 0
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setOnClickListener { onClick() }
    }

    private fun switchRow(t: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; textSize = 15f; setTextColor(C.text); isChecked = on
        setPadding(0, dp(10), 0, dp(4))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun seek(title: String, lo: Float, hi: Float, value: Float, onChange: (Float) -> Unit): List<View> {
        val steps = Math.round((hi - lo) / 0.1f)
        val l = label("$title ${"%.1f".format(value)}")
        val s = SeekBar(this).apply {
            this.max = steps
            progress = Math.round((value - lo) / 0.1f).coerceIn(0, steps)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = lo + p * 0.1f
                    l.text = "$title ${"%.1f".format(v)}"
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { preview() }
            })
        }
        return listOf(l, s)
    }

    // =====================================================================
    // 1. 휴대폰에 쿠비스 연결
    private fun isHome(): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val r = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == packageName
    }

    private fun isAssistant(): Boolean {
        val a = Settings.Secure.getString(contentResolver, "assistant") ?: ""
        val v = Settings.Secure.getString(contentResolver, "voice_interaction_service") ?: ""
        return a.startsWith("$packageName/") || v.startsWith("$packageName/")
    }

    private fun usageAllowed(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        return ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) ==
                android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun setupRow(title: String, ok: Boolean, okText: String, noText: String, btn: String?, onClick: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(txt(title, 15f, C.text))
        col.addView(txt(if (ok) "✓ $okText" else "• $noText", 13f, if (ok) C.green else Color.parseColor("#FBBF24")))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (btn != null && onClick != null) row.addView(button(btn, primary = !ok, onClick = onClick))
        setupBox.addView(row)
    }

    private fun refreshSetup() {
        setupBox.removeAllViews()

        val home = isHome()
        setupRow("홈 화면", home, "쿠비스가 홈 화면이에요", "지금은 다른 앱이 홈 화면이에요",
            if (home) "바꾸기" else "쿠비스로") { setHome() }

        val assist = isAssistant()
        setupRow("측면 버튼으로 부르기", assist, "쿠비스가 디지털 어시스턴트예요",
            "디지털 어시스턴트 앱을 쿠비스로 골라 주세요", "설정 열기") { openAssistantSettings() }
        setupBox.addView(txt("그다음 휴대폰 설정 > 유용한 기능(고급 기능) > 측면 버튼 > 길게 누르기를 '디지털 어시스턴트 깨우기'로 바꾸세요.",
            12f, C.dim).apply { setPadding(0, 0, 0, dp(4)) })
        setupBox.addView(button("측면 버튼 설정 찾기") { openSideKey() }, LinearLayout.LayoutParams(WRAP, WRAP))

        val missing = PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        setupRow("권한 (마이크·캘린더·연락처·전화·문자·위치)", missing.isEmpty(), "모두 허용됨",
            "${PERMS.size - missing.size}/${PERMS.size}개 허용됨", if (missing.isEmpty()) null else "허용하기") {
            if (missing.any { shouldShowRequestPermissionRationale(it) } || !prefs.p.getBoolean("askedPermsOnce", false)) {
                prefs.p.edit().putBoolean("askedPermsOnce", true).apply()
                requestPermissions(missing.toTypedArray(), 3)
            } else {
                // 두 번 거절하면 창이 안 뜨므로 앱 정보로
                startSafe(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }

        val usage = usageAllowed()
        setupRow("사용 기록 접근 (오래 안 쓴 앱 찾기)", usage, "허용됨", "앱 정리를 쓰려면 '쿠비스'를 허용해 주세요",
            if (usage) null else "열기") { startSafe(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun setHome() {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                @Suppress("DEPRECATION")
                startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), 7)
                return
            }
        }
        startSafe(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun openAssistantSettings() {
        val tries = listOf(
            Intent("android.settings.VOICE_INPUT_SETTINGS"),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        startSafe(Intent(Settings.ACTION_SETTINGS))
    }

    private fun openSideKey() {
        // 삼성 측면 버튼 설정 화면 (휴대폰마다 다를 수 있어 안 되면 설정 첫 화면)
        val tries = listOf(
            Intent().setClassName("com.android.settings", "com.android.settings.Settings\$FunctionKeySettingsActivity"),
            Intent("com.samsung.settings.SIDE_KEY_SETTINGS")
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setMessage("설정 화면이 열리면 위쪽 검색에 '측면 버튼'을 치세요.")
            .setPositiveButton("설정 열기") { _, _ -> startSafe(Intent(Settings.ACTION_SETTINGS)) }
            .show()
    }

    private fun startSafe(i: Intent) {
        try { startActivity(i) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    // =====================================================================
    // 2. Claude
    private fun buildClaude(c: LinearLayout) {
        c.addView(label("API 키"))
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        keyEt = EditText(this).apply {
            setText(prefs.key); hint = "sk-ant-..."; setHintTextColor(C.dim); setTextColor(C.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        keyRow.addView(keyEt, LinearLayout.LayoutParams(0, WRAP, 1f))
        keyRow.addView(button("붙여넣기") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
            if (t.startsWith("sk-ant-")) { keyEt.setText(t); saveTexts(); toastLine(c, "키를 넣었어요.") }
            else toastLine(c, "복사해 둔 글이 API 키가 아니에요 (sk-ant- 로 시작해야 해요).")
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        c.addView(keyRow)
        c.addView(txt("키는 이 휴대폰 안에만 저장되고 Anthropic 서버로만 보내져요.", 12f, C.dim))

        c.addView(label("모델"))
        val ids = listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5")
        val names = listOf("Haiku 5.5 — 가장 빠르고 저렴", "Sonnet 5.5 — 균형 (추천)", "Opus 5.5 — 가장 똑똑, 비쌈")
        val rg = RadioGroup(this)
        ids.forEachIndexed { i, id ->
            rg.addView(RadioButton(this).apply {
                text = names[i]; setTextColor(C.text); textSize = 15f; this.id = 1000 + i
                isChecked = prefs.model == id
            })
        }
        rg.setOnCheckedChangeListener { _, checked -> prefs.p.edit().putString("model", ids[checked - 1000]).apply() }
        c.addView(rg)

        c.addView(switchRow("웹 검색 쓰기 (뉴스·최신 정보)", prefs.useSearch) { prefs.p.edit().putBoolean("search", it).apply() })

        c.addView(label("쿠비스에게 알려 둘 것"))
        extraEt = EditText(this).apply {
            setText(prefs.extra); setTextColor(C.text); setHintTextColor(C.dim)
            hint = "예: 나는 군산에 사는 전도사이고, 성경은 개역개정으로 인용해 줘."
            minLines = 2; gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        c.addView(extraEt)

        c.addView(button("크레딧 충전 · 사용량 보기") {
            startSafe(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.claude.com/settings/billing")))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(12) })
    }

    private fun toastLine(c: LinearLayout, msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // =====================================================================
    // 3. 목소리
    private fun buildVoice(c: LinearLayout) {
        c.addView(label("음성 엔진 (휴대폰에 설치된 무료 엔진)"))
        val engineSpinner = Spinner(this)
        c.addView(engineSpinner)

        c.addView(label("목소리 — 누르면 바로 들려줘요"))
        voiceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(voiceList)

        seek("말하기 속도", 0.6f, 2.0f, prefs.rate) { prefs.p.edit().putFloat("rate", it).apply() }.forEach { c.addView(it) }
        seek("음높이", 0.5f, 2.0f, prefs.pitch) { prefs.p.edit().putFloat("pitch", it).apply() }.forEach { c.addView(it) }

        c.addView(switchRow("연속 대화 (답한 뒤 자동으로 다시 듣기)", prefs.loop) { prefs.loop = it })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
        row.addView(button("미리 듣기", primary = true) { preview() })
        row.addView(button("목소리 더 받기") {
            startSafe(Intent("com.android.settings.TTS_SETTINGS"))
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        c.addView(row)
        c.addView(txt("목소리가 적으면 '목소리 더 받기' > 엔진 옆 ⚙ > 음성 데이터 설치에서 한국어 목소리를 받을 수 있어요.",
            12f, C.dim).apply { setPadding(0, dp(8), 0, 0) })

        // 엔진 목록은 TTS가 준비되면 채움
        engineSpinner.tag = "engines"
        this.engineSpinner = engineSpinner
    }

    private var engineSpinner: Spinner? = null

    private fun startTts() {
        ttsReady = false
        tts?.shutdown()
        val engine = prefs.engine
        val listener = TextToSpeech.OnInitListener { st ->
            runOnUiThread {
                if (st == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.language = Locale.KOREAN
                }
                fillEngines()
                fillVoices()
            }
        }
        tts = if (engine.isBlank()) TextToSpeech(this, listener) else TextToSpeech(this, listener, engine)
    }

    private fun fillEngines() {
        val sp = engineSpinner ?: return
        val t = tts ?: return
        val engines = t.engines
        val labels = engines.map { it.label }
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val cur = prefs.engine.ifBlank { t.defaultEngine }
        sp.setSelection(engines.indexOfFirst { it.name == cur }.coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val name = engines[pos].name
                if (name != prefs.engine.ifBlank { t.defaultEngine }) {
                    prefs.p.edit().putString("engine", name).putString("voice", "").apply()
                    startTts()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun koreanVoices(): List<Voice> =
        try {
            tts?.voices?.filter { it.locale.language == "ko" }?.sortedBy { it.name } ?: emptyList()
        } catch (_: Exception) { emptyList() }

    private fun fillVoices() {
        voiceList.removeAllViews()
        if (!ttsReady) {
            voiceList.addView(txt("이 엔진을 쓸 수 없어요. 다른 엔진을 골라 주세요.", 13f, C.err))
            return
        }
        val voices = koreanVoices()
        val rg = RadioGroup(this)
        rg.addView(RadioButton(this).apply {
            text = "기본 목소리"; setTextColor(C.text); textSize = 15f; id = 2000
            isChecked = prefs.voice.isBlank()
        })
        voices.forEachIndexed { i, v ->
            val notes = mutableListOf<String>()
            if (v.isNetworkConnectionRequired) notes.add("인터넷 필요")
            if (v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true) notes.add("받아야 함")
            if (v.quality >= Voice.QUALITY_HIGH) notes.add("고음질")
            rg.addView(RadioButton(this).apply {
                text = "목소리 ${i + 1}" + (if (notes.isEmpty()) "" else "  (${notes.joinToString(", ")})")
                setTextColor(C.text); textSize = 15f; id = 2001 + i
                isChecked = prefs.voice == v.name
            })
        }
        rg.setOnCheckedChangeListener { _, checked ->
            val name = if (checked == 2000) "" else voices.getOrNull(checked - 2001)?.name ?: ""
            prefs.p.edit().putString("voice", name).apply()
            preview()
        }
        voiceList.addView(rg)
        if (voices.isEmpty()) voiceList.addView(txt("이 엔진에는 고를 수 있는 한국어 목소리가 없어요.", 12f, C.dim))
    }

    private fun preview() {
        val t = tts ?: return
        if (!ttsReady) return
        val v = koreanVoices().firstOrNull { it.name == prefs.voice }
        if (v != null) t.voice = v else t.language = Locale.KOREAN
        t.setSpeechRate(prefs.rate)
        t.setPitch(prefs.pitch)
        t.speak("안녕하세요 T, 저는 쿠비스예요. 오늘 일정을 알려 드릴까요?", TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    // =====================================================================
    // 4. 할 수 있는 일
    private fun buildAbilities(c: LinearLayout) {
        val list = listOf(
            "📅 일정" to listOf("내일 오후 3시에 심방 일정 넣어 줘, 30분 전에 알려 주고", "이번 주 일정 뭐 있어?", "금요일 회의를 4시로 옮겨 줘"),
            "✅ 할 일" to listOf("내일까지 중고 시험기간 확인 할 일 넣어 줘", "오늘 할 일 뭐 남았어?", "시험기간 확인 끝났어"),
            "📝 메모" to listOf("메모해 줘, 수련회 장소 후보는 ○○ 수양관", "수련회 메모에 '예산 50만 원' 덧붙여 줘", "수련회 메모 찾아 줘"),
            "🌤 날씨" to listOf("내일 비 와?", "이번 주 날씨 어때?"),
            "⏰ 알람·타이머" to listOf("내일 아침 6시 알람", "라면 3분 타이머"),
            "📱 앱" to listOf("유튜브 열어 줘", "안 쓰는 앱 정리하자", "고정줄에 카카오톡 넣어 줘", "메모 페이지 보여 줘"),
            "📞 연락" to listOf("○○ 전도사님께 전화해 줘 (확인 후 걸어요)", "○○에게 '조금 늦어요' 문자 보내 줘 (확인 후 보내요)"),
            "🔦 기기" to listOf("손전등 켜 줘", "볼륨 50으로", "배터리 얼마야?", "와이파이 설정 열어 줘"),
            "🔎 그 밖에" to listOf("오늘 뉴스 알려 줘 (웹 검색)", "로마서 8장 28절이 어떤 내용이야?", "그만 / 새 대화")
        )
        for ((title, examples) in list) {
            c.addView(txt(title, 15f, C.text, bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
            for (e in examples) c.addView(txt("“$e”", 14f, C.dim).apply { setPadding(dp(8), dp(1), 0, dp(1)) })
        }
        c.addView(txt("\n안 되는 것: 홈 화면 아이콘 배치를 말로 바꾸기, 다른 앱 안의 버튼 누르기(예: 카톡 보내기)는 아직 못 해요.",
            12f, C.dim))
    }

    // =====================================================================
    // 5. 기타
    private fun buildEtc(c: LinearLayout) {
        val store = Store(this)
        c.addView(button("할 일·메모 백업 내보내기") {
            startSafe(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "쿠비스 백업")
                    .putExtra(Intent.EXTRA_TEXT, store.exportJson()), "백업 보낼 곳"))
        }, LinearLayout.LayoutParams(WRAP, WRAP))
        c.addView(button("쿠비스 대화 기록 지우기") {
            AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage("쿠비스와 나눈 대화 기록을 지울까요? 할 일·메모·일정은 그대로예요.")
                .setPositiveButton("지우기") { _, _ -> prefs.p.edit().putString("history", "[]").apply() }
                .setNegativeButton("아니요", null).show()
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        c.addView(button("휴대폰 설정 열기") { startSafe(Intent(Settings.ACTION_SETTINGS)) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        c.addView(button("업데이트 확인", primary = true) { Updater.check(this, silent = false) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        c.addView(txt("쿠비스 $ver · 새 버전이 나오면 홈 화면에서 알려 줘요", 12f, C.dim).apply { setPadding(0, dp(12), 0, 0) })
    }
}
