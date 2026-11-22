package com.t.kuvis

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject

/** 전체 앱 폴더 정리 */
object Folders {

    /** 폴더 밖(폴더 아래에 따로 보이는 앱) */
    const val TOP = "_top"

    /** T가 폴더 밖에 두기로 한 앱 (이름·패키지로 찾음) */
    private val TOP_KEYS = listOf("mybox", "ndrive", "내 파일", "myfiles", "갤러리", "gallery3d", "play 스토어", "com.android.vending",
        "솜노트", "app.notes", "헤이홈", "heyhome", "goqual", "tapo", "tplink", "qcy", "accuweather")

    fun isTopDefault(label: String, pkg: String): Boolean {
        val s = "$label $pkg".lowercase()
        return TOP_KEYS.any { s.contains(it) }
    }

    val DEFAULT = listOf("성경", "소통", "금융", "미디어·쇼핑", "생활", "업무", "도구", "안 쓰는 앱")

    /** T가 숨기기로 한 앱 (홈 고정줄·말로는 그대로 쓸 수 있음) */
    private val HIDE_KEYS = listOf("com.kakao.talk", "com.samsung.android.dialer", "com.android.dialer", "com.nhn.android.nmap")

    /** 앞에 있는 규칙이 먼저 맞으면 그 폴더 (이름 + 패키지명을 소문자로 보고 찾음) */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "성경" to listOf("성경", "bible", "찬송", "godpeople", "갓피플", "교회", "dimode", "디모데", "설교", "큐티", "cgntv", "극동방송", "기독"),
        // 켤 일이 거의 없는 기본·통신사·기기 연결 앱
        "안 쓰는 앱" to listOf("samsungapps", "galaxy store", "갤럭시 스토어", "삼성 멤버스", "samsung members", "voc", "galaxy wearable", "wearable",
            "global goals", "globalgoals", "글로벌 골", "samsung free", "tips", "도움말", "kids", "키즈", "ar zone", "arzone", "bixby", "빅스비",
            "tworld", "t world", "sktelecom", "skt ", "t멤버십", "tmembership", "olleh", "my kt", "mykt", "ktfamily", "uplus", "u+", "lguplus",
            "smart switch", "스마트 스위치", "samsung pass", "삼성 패스", "carrier", "통신사", "google one", "구글 원", "google tv", "tachyon",
            "qcy", "buds", "earphone", "이어폰", "remote", "리모컨", "galaxy store", "블루투스", "mobile fax", "모바일 팩스"),
        "금융" to listOf("bank", "은행", "뱅킹", "kbstar", "kbcard", "kb pay", "kb국민", "국민카드", "nonghyup", "nhbank", "nh스마트", "nh pay", "nhcard", "nh농협", "농협", "신한", "shinhan", "우리은행", "wooribank",
            "하나", "hana", "토스", "viva.republica", "카카오뱅크", "kakaobank", "페이북", "bccard", "pay", "페이", "tauth", "ktauth", "pass by", "증권", "카드", "보험",
            "meritz", "메리츠", "metlife", "메트라이프", "상품권", "chak", "지역화폐", "hometax", "홈택스", "연금", "새마을", "우체국", "신협"),
        "미디어·쇼핑" to listOf("coupang", "쿠팡", "배달", "baemin", "yogiyo", "요기요", "eats", "11st", "11번가", "gmarket", "지마켓", "auction", "옥션",
            "daangn", "당근", "ssg", "무신사", "musinsa", "kurly", "컬리", "temu", "테무", "aliexpress", "알리", "오늘의집", "다이소", "올리브영", "쇼핑",
            "gallery", "갤러리", "camera", "카메라", "youtube", "유튜브", "netflix", "넷플릭스", "music", "음악", "멜론", "melon", "genie", "지니",
            "spotify", "tving", "티빙", "wavve", "웨이브", "disney", "photo", "사진", "video", "영상", "capcut", "캡컷", "snow", "리디", "ridi", "ebook", "밀리", "웹툰", "webtoon", "podcast", "guitartuna", "녹음"),
        "소통" to listOf("kakao.talk", "카카오톡", "instagram", "인스타", "facebook", "페이스북", "com.nhn.android.band", "밴드", "messenger", "jp.naver.line",
            "telegram", "messaging", "메시지", "dialer", "전화", "contacts", "연락처", "threads", "discord", "zoom", "whatsapp"),
        "생활" to listOf("map", "지도", "nmap", "tmap", "티맵", "kakao.taxi", "카카오 t", "택시", "버스", "지하철", "korail", "코레일", "srt", "weather", "날씨",
            "accuweather", "쏘카", "socar", "주차", "헤이홈", "tapo", "tplink", "smartthings", "스마트싱스", "아파트", "병원", "약국", "health", "헬스", "건강"),
        "업무" to listOf("office", "hancom", "한컴", "한글", "word", "excel", "powerpoint", "docs", "drive", "드라이브", "mybox", "files", "파일", "notes", "노트",
            "메모", "mail", "메일", "gmail", "calendar", "캘린더", "windows", "chrome", "whale", "웨일", "브라우저", "internet", "인터넷", "notion", "slack", "pdf", "scanner", "스캐너", "claude", "잡코리아", "jobkorea")
    )

    fun isHideDefault(pkg: String) = HIDE_KEYS.any { pkg == it }

    fun guess(label: String, pkg: String, category: Int): String {
        val s = "$label $pkg".lowercase()
        for ((folder, keys) in RULES) if (keys.any { s.contains(it) }) return folder
        return when (category) {
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> "미디어·쇼핑"
            ApplicationInfo.CATEGORY_SOCIAL -> "소통"
            ApplicationInfo.CATEGORY_MAPS -> "생활"
            ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_NEWS -> "업무"
            else -> "도구"
        }
    }

    /** 각 앱의 폴더. 저장된 게 있으면 그대로, 새 앱은 추측해서 저장 */
    fun assign(ctx: Context, apps: List<AppEntry>): Map<String, String> {
        val store = Store(ctx)
        if (store.rawGet("folderV2") == null && apps.isNotEmpty()) {
            store.setFolderOrder(DEFAULT)
            resortAll(ctx, apps)
            for (a in apps) if (isHideDefault(a.pkg)) store.setHidden(a.pkg, true)
            store.rawPut("folderV2", "1")
        }
        val order = store.folderOrder()
        val saved = store.folderAssign().toMutableMap()
        var changed = false
        // 한 번만: 정해 둔 9개 앱을 폴더 밖으로
        if (store.rawGet("topSeeded") == null) {
            for (a in apps) if (isTopDefault(a.label, a.pkg)) saved[a.pkg] = TOP
            store.rawPut("topSeeded", "1")
            changed = true
        }
        for (a in apps) {
            val cur = saved[a.pkg]
            if (cur == null || (cur != TOP && cur !in order)) {
                val g = guess(a.label, a.pkg, a.category)
                saved[a.pkg] = if (g in order) g else order.last()
                changed = true
            }
        }
        if (changed) store.setFolderAssign(saved)
        return saved
    }

    /** 처음부터 다시 자동 정리 */
    fun resortAll(ctx: Context, apps: List<AppEntry>) {
        val store = Store(ctx)
        val order = store.folderOrder()
        val m = apps.associate { a ->
            a.pkg to (if (isTopDefault(a.label, a.pkg)) TOP else guess(a.label, a.pkg, a.category).let { if (it in order) it else order.last() })
        }
        store.setFolderAssign(m)
    }
}

// ---------- Store에 붙는 폴더 저장 ----------
fun Store.folderOrder(): List<String> {
    val s = rawGet("folderOrder") ?: return Folders.DEFAULT
    return try { val a = JSONArray(s); List(a.length()) { a.getString(it) }.ifEmpty { Folders.DEFAULT } } catch (_: Exception) { Folders.DEFAULT }
}

fun Store.setFolderOrder(list: List<String>) = rawPut("folderOrder", JSONArray(list.distinct()).toString())

fun Store.folderAssign(): Map<String, String> {
    val s = rawGet("folderAssign") ?: return emptyMap()
    return try {
        val o = JSONObject(s); o.keys().asSequence().associateWith { o.getString(it) }
    } catch (_: Exception) { emptyMap() }
}

fun Store.setFolderAssign(m: Map<String, String>) {
    val o = JSONObject(); for ((k, v) in m) o.put(k, v)
    rawPut("folderAssign", o.toString())
}

/** folder가 "밖"·"폴더 밖"이면 폴더 밖으로 */
fun Store.moveToFolder(pkg: String, folder: String) {
    val f = if (folder in listOf(Folders.TOP, "밖", "폴더 밖", "바깥", "폴더밖")) Folders.TOP else folder
    if (f == Folders.TOP) { setFolderAssign(folderAssign() + (pkg to Folders.TOP)); return }
    val order = folderOrder()
    if (folder !in order) setFolderOrder(order + folder)
    setFolderAssign(folderAssign() + (pkg to folder))
}

fun Store.renameFolder(from: String, to: String): Boolean {
    val order = folderOrder()
    if (from !in order || to.isBlank()) return false
    setFolderOrder(order.map { if (it == from) to else it })
    setFolderAssign(folderAssign().mapValues { if (it.value == from) to else it.value })
    return true
}

// ---------- 숨긴 앱 ----------
fun Store.hiddenApps(): Set<String> {
    val s = rawGet("hiddenApps") ?: return emptySet()
    return try { val a = JSONArray(s); (0 until a.length()).map { a.getString(it) }.toSet() } catch (_: Exception) { emptySet() }
}

fun Store.setHidden(pkg: String, hidden: Boolean) {
    val cur = hiddenApps().toMutableSet()
    if (hidden) cur.add(pkg) else cur.remove(pkg)
    rawPut("hiddenApps", JSONArray(cur.toList()).toString())
}

// ---------- 쿠비스가 기억하는 것 ----------
fun Store.memory(): List<String> {
    val s = rawGet("memory") ?: return emptyList()
    return try { val a = JSONArray(s); List(a.length()) { a.getString(it) } } catch (_: Exception) { emptyList() }
}

fun Store.setMemory(list: List<String>) = rawPut("memory", JSONArray(list.takeLast(80)).toString())
