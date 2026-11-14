package app.zhiyi.memory

import android.content.Context
import app.zhiyi.core.ChatCore
import app.zhiyi.data.Db
import app.zhiyi.data.Prefs
import app.zhiyi.data.modelList
import app.zhiyi.llm.Llm
import org.json.JSONArray
import org.json.JSONObject

/**
 * 人格自进化。
 *
 * 灵感来自「大肥鱼」的设定：性格不是写死的，是从每天跟你聊天里慢慢长出来的。
 *
 * 设计约束（很重要）：
 * - **绝不动用户写的人格本体**（personality / speech）。那些是作者意图。
 * - 只维护一份「最近调整」，每天重算一次，作为一小段附加指令注入 prompt。
 * - 每天都要有结果：哪怕判断"不需要调整"，也记一条，用户能看到它在想。
 * - 只做行为层面的调整（长短、玩笑、主动度、关心方式），不写"要更温柔"这种空话。
 */
object PersonaEvolve {

    private const val MAX_KEEP = 7          // 保留最近 7 天
    private const val MIN_MSGS = 8          // 一天至少聊这么多句才谈得上"提炼"

    private fun kvKey(pid: String) = "evolve_$pid"

    /**
     * 已生效的调整。
     *
     * 存在人格的 evolveNote 字段里，不再存 kv —— 因为"她变成了什么样"是人格的一部分，
     * 应该跟人格一起进版本历史：每一次微调都能回溯、能回滚。
     * （kv 里只留 log，纯展示用。）
     */
    fun active(db: Db, pid: String): List<String> =
        db.persona(pid)?.evolveNote?.split("\n")
            ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()

    /** 清空：让她回到用户写的样子（会记一个新版本，可再回滚回来） */
    fun reset(db: Db, pid: String): Boolean {
        val p = db.persona(pid) ?: return false
        if (p.evolveNote.isBlank()) return false
        db.savePersona(p.copy(evolveNote = ""))
        return true
    }

    /** 注入用的提示块；没有就返回空串 */
    fun hint(db: Db, pid: String): String {
        val list = active(db, pid)
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n# 你最近的变化\n")
        sb.append("（这些是从你和对方相处里慢慢长出来的，已经变成你的习惯。自然去做，不要提起「你在调整」这件事。）\n")
        list.take(4).forEach { sb.append("- ").append(it).append("\n") }
        return sb.toString()
    }

    /** 最近几天的记录，给设置页看 */
    fun history(db: Db, pid: String): List<Triple<Long, String, List<String>>> {
        val raw = db.get(kvKey(pid), "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONObject(raw).optJSONArray("log") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val ts = o.optLong("ts", 0L)
                val note = o.optString("note", "")
                val a = o.optJSONArray("items")
                val items = if (a == null) emptyList()
                else (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.isNotBlank() } }
                Triple(ts, note, items)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 每天跑一次。返回 true 表示今天确实更新了（有新调整或记了新日志）。
     */
    suspend fun daily(ctx: Context, pid: String): Boolean {
        val db = Db.get(ctx)
        val cfg = Prefs.get(ctx).data
        val persona = db.persona(pid) ?: return false
        val tk = ChatCore.threadKey(pid, cfg.chatMode)

        // 今天是否已经跑过
        val today = dayKey()
        if (db.get("evolve_day_$pid", "") == today) return false

        val recent = db.recentMsgs(tk, 60).filter { it.role == "user" || it.role == "assistant" }
        if (recent.size < MIN_MSGS) {
            // 聊得太少，记一条"今天没材料"，明天再说（保证每天有记录，用户能看到它在动）
            appendLog(db, pid, today, "今天聊得少，没什么可调整的", emptyList())
            db.put("evolve_day_$pid", today)
            return true
        }

        val prov = ChatCore.providerFor(ctx, pid) ?: ChatCore.activeProvider(ctx) ?: return false
        val model = prov.cheapModel.modelList().firstOrNull()
            ?: prov.chatModel.modelList().firstOrNull() ?: return false

        val convo = recent.takeLast(40).joinToString("\n") {
            (if (it.role == "user") "他" else persona.name) + "：" + it.content.take(160)
        }
        val prev = active(db, pid)

        val sys = "你是一个角色行为调优器。只输出 JSON，不要任何解释。"
        val ask = """
下面是一个 AI 陪伴角色「${persona.name}」和用户最近的聊天。
她的原始性格：${persona.personality.take(300)}
${if (prev.isNotEmpty()) "她目前已经在做这些调整：\n" + prev.joinToString("\n") { "- $it" } else ""}

请判断：**为了让这个用户更舒服**，她应该怎么调整？
输出 JSON 数组，最多 3 条，每项 {"t":"调整内容","why":"依据"}。

硬性要求：
- 只写**行为层面能执行**的改动，比如：说话更短、少用问句、别急着给建议、主动提自己的事、累的时候不开玩笑
- 不要写"更温柔""更贴心""更懂他"这种无法执行的空话
- 必须有依据（从聊天里看出来的，不是猜的）
- 如果看不出明确信号，输出 []
- 已有的调整不要重复写

聊天记录：
$convo
""".trimIndent()

        val raw = runCatching { Llm(prov).chat(model, listOf("system" to sys, "user" to ask), 0.3, 320) }
            .getOrNull() ?: return false
        val arr = JsonUtil.array(raw) ?: run {
            appendLog(db, pid, today, "模型没给出有效结果", emptyList())
            db.put("evolve_day_$pid", today)
            return true
        }

        val items = ArrayList<String>()
        val notes = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 3)) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t", "").trim()
            if (t.length < 4) continue
            items.add(t.take(60))
            val why = o.optString("why", "").trim()
            if (why.isNotBlank()) notes.add(why.take(40))
        }

        // 新调整合并进 active（去重、最多留 4 条，老的先淘汰）
        val merged = (items + prev).distinctBy { it.take(12) }.take(4)
        // 写回人格本体 → 触发一个可回滚的新版本
        if (merged != prev) db.savePersona(persona.copy(evolveNote = merged.joinToString("\n")))
        appendLog(db, pid, today, if (items.isEmpty()) "今天没有需要调整的地方" else "调整了 ${items.size} 处", items)
        db.put("evolve_day_$pid", today)
        return true
    }

    private fun appendLog(db: Db, pid: String, day: String, note: String, items: List<String>) {
        val raw = db.get(kvKey(pid), "")
        val o = runCatching { JSONObject(if (raw.isBlank()) "{}" else raw) }.getOrElse { JSONObject() }
        val log = o.optJSONArray("log") ?: JSONArray()
        val entry = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("day", day)
            .put("note", note)
            .put("items", JSONArray(items))
        val arr = JSONArray()
        arr.put(entry)
        for (i in 0 until minOf(log.length(), MAX_KEEP - 1)) arr.put(log.optJSONObject(i) ?: continue)
        o.put("log", arr)
        // active 已经搬进人格字段，这里只留日志
        db.put(kvKey(pid), o.toString())
    }

    private fun dayKey(): String {
        val f = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        return f.format(java.util.Date())
    }
}
