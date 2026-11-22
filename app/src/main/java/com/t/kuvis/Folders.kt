package com.t.kuvis

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONArray
import org.json.JSONObject

/** 전체 앱 폴더 정리 */
object Folders {

    val DEFAULT = listOf("교회·성경", "소통", "금융", "쇼핑·배달", "미디어", "생활·교통", "업무·문서", "도구·기타")

    /** 앞에 있는 규칙이 먼저 맞으면 그 폴더 (이름 + 패키지명을 소문자로 보고 찾음) */
    private val RULES: List<Pair<String, List<String>>> = listOf(
        "교회·성경" to listOf("성경", "bible", "찬송", "godpeople", "갓피플", "교회", "dimode", "디모데", "설교", "큐티", "cgntv", "극동방송", "기독"),
        "금융" to listOf("bank", "은행", "뱅킹", "kbstar", "kbcard", "kb pay", "kb국민", "국민카드", "nonghyup", "nhbank", "nh스마트", "nh pay", "nhcard", "nh농협", "농협", "신한", "shinhan", "우리은행", "wooribank",
            "하나", "hana", "토스", "viva.republica", "카카오뱅크", "kakaobank", "페이북", "bccard", "pay", "페이", "tauth", "ktauth", "pass by", "증권", "카드", "보험",
            "meritz", "메리츠", "metlife", "메트라이프", "상품권", "chak", "지역화폐", "hometax", "홈택스", "연금", "새마을", "우체국", "신협"),
        "쇼핑·배달" to listOf("coupang", "쿠팡", "배달", "baemin", "yogiyo", "요기요", "eats", "11st", "11번가", "gmarket", "지마켓", "auction", "옥션",
            "daangn", "당근", "ssg", "무신사", "musinsa", "kurly", "컬리", "temu", "테무", "aliexpress", "알리", "오늘의집", "다이소", "올리브영", "쇼핑"),
        "소통" to listOf("kakao.talk", "카카오톡", "instagram", "인스타", "facebook", "페이스북", "com.nhn.android.band", "밴드", "messenger", "jp.naver.line",
            "telegram", "messaging", "메시지", "dialer", "전화", "contacts", "연락처", "threads", "discord", "zoom", "whatsapp"),
        "미디어" to listOf("gallery", "갤러리", "camera", "카메라", "youtube", "유튜브", "netflix", "넷플릭스", "music", "음악", "멜론", "melon", "genie", "지니",
            "spotify", "tving", "티빙", "wavve", "웨이브", "disney", "photo", "사진", "video", "영상", "capcut", "캡컷", "snow", "리디", "ridi", "ebook", "밀리", "웹툰", "webtoon", "podcast"),
        "생활·교통" to listOf("map", "지도", "nmap", "tmap", "티맵", "kakao.taxi", "카카오 t", "택시", "버스", "지하철", "korail", "코레일", "srt", "weather", "날씨",
            "accuweather", "쏘카", "socar", "주차", "헤이홈", "tapo", "tplink", "smartthings", "스마트싱스", "아파트", "병원", "약국", "health", "헬스", "건강"),
        "업무·문서" to listOf("office", "hancom", "한컴", "한글", "word", "excel", "powerpoint", "docs", "drive", "드라이브", "mybox", "files", "파일", "notes", "노트",
            "메모", "mail", "메일", "gmail", "calendar", "캘린더", "windows", "chrome", "whale", "웨일", "브라우저", "internet", "인터넷", "notion", "slack", "pdf", "scanner", "스캐너", "claude")
    )

    fun guess(label: String, pkg: String, category: Int): String {
        val s = "$label $pkg".lowercase()
        for ((folder, keys) in RULES) if (keys.any { s.contains(it) }) return folder
        return when (category) {
            ApplicationInfo.CATEGORY_AUDIO, ApplicationInfo.CATEGORY_VIDEO, ApplicationInfo.CATEGORY_IMAGE -> "미디어"
            ApplicationInfo.CATEGORY_SOCIAL -> "소통"
            ApplicationInfo.CATEGORY_MAPS -> "생활·교통"
            ApplicationInfo.CATEGORY_PRODUCTIVITY, ApplicationInfo.CATEGORY_NEWS -> "업무·문서"
            else -> "도구·기타"
        }
    }

    /** 각 앱의 폴더. 저장된 게 있으면 그대로, 새 앱은 추측해서 저장 */
    fun assign(ctx: Context, apps: List<AppEntry>): Map<String, String> {
        val store = Store(ctx)
        val order = store.folderOrder()
        val saved = store.folderAssign().toMutableMap()
        var changed = false
        for (a in apps) {
            val cur = saved[a.pkg]
            if (cur == null || cur !in order) {
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
        val m = apps.associate { a -> a.pkg to guess(a.label, a.pkg, a.category).let { if (it in order) it else order.last() } }
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

fun Store.moveToFolder(pkg: String, folder: String) {
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
