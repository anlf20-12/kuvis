package com.t.kuvis

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Todo(val id: Long, val text: String, val due: String?, val done: Boolean, val doneAt: Long)
data class Memo(val id: Long, val text: String, val pinned: Boolean, val updated: Long)

/** 할 일·메모·고정줄을 휴대폰 안에 저장 */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("kuvis_data", Context.MODE_PRIVATE)

    companion object { private val lock = Any() }

    // ---------- 할 일 ----------
    fun todos(): List<Todo> = synchronized(lock) {
        val out = mutableListOf<Todo>()
        try {
            val a = JSONArray(p.getString("todos", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Todo(o.getLong("id"), o.getString("text"),
                    o.optString("due", "").ifBlank { null }, o.optBoolean("done"), o.optLong("doneAt")))
            }
        } catch (_: Exception) {}
        out
    }

    private fun saveTodos(list: List<Todo>) {
        val a = JSONArray()
        for (t in list) a.put(JSONObject().put("id", t.id).put("text", t.text).put("due", t.due ?: "")
            .put("done", t.done).put("doneAt", t.doneAt))
        p.edit().putString("todos", a.toString()).apply()
    }

    fun addTodo(text: String, due: String?): Todo = synchronized(lock) {
        val list = todos().toMutableList()
        val t = Todo((list.maxOfOrNull { it.id } ?: 0L) + 1, text.trim(), due?.take(10)?.ifBlank { null }, false, 0L)
        list.add(t); saveTodos(list); t
    }

    /** f가 null을 돌려주면 지움 */
    fun editTodo(id: Long, f: (Todo) -> Todo?): Boolean = synchronized(lock) {
        val list = todos().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val n = f(list[i])
        if (n == null) list.removeAt(i) else list[i] = n
        // 완료된 지 30일 지난 것은 정리
        val cut = System.currentTimeMillis() - 30L * 86_400_000L
        saveTodos(list.filter { !(it.done && it.doneAt in 1 until cut) })
        true
    }

    fun setDone(id: Long, done: Boolean) =
        editTodo(id) { it.copy(done = done, doneAt = if (done) System.currentTimeMillis() else 0L) }

    // ---------- 메모 ----------
    fun memos(): List<Memo> = synchronized(lock) {
        val out = mutableListOf<Memo>()
        try {
            val a = JSONArray(p.getString("memos", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(Memo(o.getLong("id"), o.getString("text"), o.optBoolean("pinned"), o.optLong("updated")))
            }
        } catch (_: Exception) {}
        out.sortedWith(compareByDescending<Memo> { it.pinned }.thenByDescending { it.updated })
    }

    private fun saveMemos(list: List<Memo>) {
        val a = JSONArray()
        for (m in list) a.put(JSONObject().put("id", m.id).put("text", m.text).put("pinned", m.pinned).put("updated", m.updated))
        p.edit().putString("memos", a.toString()).apply()
    }

    fun addMemo(text: String): Memo = synchronized(lock) {
        val list = memos().toMutableList()
        val m = Memo((list.maxOfOrNull { it.id } ?: 0L) + 1, text.trim(), false, System.currentTimeMillis())
        list.add(m); saveMemos(list); m
    }

    fun editMemo(id: Long, f: (Memo) -> Memo?): Boolean = synchronized(lock) {
        val list = memos().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return false
        val n = f(list[i])
        if (n == null) list.removeAt(i) else list[i] = n.copy(updated = System.currentTimeMillis())
        saveMemos(list); true
    }

    // ---------- 고정줄 ----------
    fun dock(): List<String>? = synchronized(lock) {
        val s = p.getString("dock", null) ?: return null
        try { val a = JSONArray(s); List(a.length()) { a.getString(it) } } catch (_: Exception) { null }
    }

    fun setDock(pkgs: List<String>) = synchronized(lock) {
        p.edit().putString("dock", JSONArray(pkgs.take(5)).toString()).apply()
    }

    // 다른 파일(폴더 정리 등)에서 쓰는 단순 저장
    fun rawGet(k: String): String? = synchronized(lock) { p.getString(k, null) }
    fun rawPut(k: String, v: String) = synchronized(lock) { p.edit().putString(k, v).apply() }

    /** 백업용 */
    fun exportJson(): String = synchronized(lock) {
        JSONObject()
            .put("todos", JSONArray(p.getString("todos", "[]")))
            .put("memos", JSONArray(p.getString("memos", "[]")))
            .toString(2)
    }
}
