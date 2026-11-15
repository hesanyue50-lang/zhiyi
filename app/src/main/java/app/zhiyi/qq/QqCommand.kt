package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db

/**
 * QQ 端固定指令。
 *
 * 指令**不经过模型**——直接回文本。理由两个：
 * 1. 快，而且不花 token；
 * 2. 模型会把 "/help" 当成一句普通的话去共情，反而更糟。
 *
 * 人格现在由**账号**绑定（一个 AppID 一个人格），所以这里不再提供切换，
 * 只告诉你现在是谁——切换要改账号配置，那是 App 里的事。
 */
object QqCommand {

    /**
     * 记住这个会话在跟谁聊，主动发话时用得上。
     *
     * **私聊和群分开存**：之前混在一个键里，最后说话的是群就把 private 的目标也覆盖成群 id，
     * 主动发话会发错地方。
     */
    fun rememberOpenId(db: Db, appId: String, pid: String, targetId: String, group: Boolean) {
        val k = if (group) "qq_group_${appId}_$pid" else "qq_openid_${appId}_$pid"
        db.put(k, targetId)
        db.putNum("${k}_ts", System.currentTimeMillis())
    }

    fun openIdFor(db: Db, appId: String, pid: String, group: Boolean = false): String =
        db.get(if (group) "qq_group_${appId}_$pid" else "qq_openid_${appId}_$pid", "")

    /** 处理指令。返回 null 表示"这不是指令，去走模型" */
    fun handle(ctx: Context, acc: QqAccount, openId: String, raw: String): String? {
        val t = raw.trim()
        if (!t.startsWith("/") && !t.startsWith("／")) return null
        if (t.length > 24) return null

        val body = t.substring(1).trim()
        val cmd = body.split(Regex("\\s+"), limit = 2).getOrNull(0).orEmpty().lowercase()

        val db = Db.get(ctx)
        return when (cmd) {
            "help", "帮助", "指令", "?", "？" -> helpText()
            "人格", "persona", "ps" -> personaText(db, acc)
            "现在", "who", "我是谁" -> {
                val p = db.persona(acc.personaId)
                if (p == null) "这个机器人还没绑人格，去 App 里「QQ」页面设一个" else "现在陪你的是「${p.name}」"
            }
            "状态", "status" -> statusText(ctx, db, acc, openId)
            "记忆", "mem" -> memText(db, acc, openId)
            else -> "没有这条指令。发 /help 看看有哪些"
        }
    }

    private fun helpText(): String = """
可以用的指令：

/help  —— 看这份列表
/现在  —— 现在是谁在陪你
/人格  —— 这个机器人绑的是谁
/状态  —— 连接情况和记忆概况
/记忆  —— 她记住了你哪些事

其他话我都当聊天，正常回你。
    """.trim()

    private fun personaText(db: Db, acc: QqAccount): String {
        val all = db.personas()
        if (all.isEmpty()) return "还没有人格，先去 App 里建一个"
        val cur = db.persona(acc.personaId)
        val sb = StringBuilder()
        sb.append("这个机器人绑的是「${cur?.name ?: "（未设置）"}」。\n")
        sb.append("人格是按机器人分开的：一个机器人一个人格，各有各的白名单和记忆。")
        if (all.size > 1) {
            sb.append("\n\nApp 里现在有 ${all.size} 个人格。想换一个陪你的话，去 App 的「QQ」页面新建一个机器人、绑另一个人格。")
        }
        return sb.toString()
    }

    private fun statusText(ctx: Context, db: Db, acc: QqAccount, openId: String): String {
        val p = db.persona(acc.personaId)
        val memKey = "${acc.personaId}#$openId"
        val facts = runCatching { db.factsPage(memKey, 0, 999).size }.getOrDefault(0)
        val msgs = runCatching { db.recentMsgs(memKey, 999).size }.getOrDefault(0)
        return """
连接：${QqBot.state(acc.appId)}
机器人：${acc.appId}
人格：${p?.name ?: "未设置"}
你的记忆：$facts 条事实 · $msgs 条对话（和别人分开的）
        """.trim()
    }

    private fun memText(db: Db, acc: QqAccount, openId: String): String {
        val p = db.persona(acc.personaId) ?: return "这个机器人还没绑人格"
        val memKey = "${acc.personaId}#$openId"
        val facts = runCatching { db.factsPage(memKey, 0, 999) }.getOrDefault(emptyList())
        if (facts.isEmpty()) return "「${p.name}」现在还什么都没记住。多聊几句就有了。"
        val sb = StringBuilder("「${p.name}」记住了关于你的这些：\n")
        facts.take(12).forEach { sb.append("- ").append(it.line()).append("\n") }
        if (facts.size > 12) sb.append("…还有 ${facts.size - 12} 条")
        return sb.toString().trim()
    }
}
