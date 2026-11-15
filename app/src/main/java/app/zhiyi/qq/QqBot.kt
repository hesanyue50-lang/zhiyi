package app.zhiyi.qq

import android.content.Context
import app.zhiyi.core.ChatCore
import app.zhiyi.data.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * QQ 与知忆之间的桥（多账号）。
 *
 * 一个 AppID = 一个机器人 = 一个人格。可以有多个账号同时在线，
 * 互不干扰：各自的长连接、各自的白名单、各自的记忆。
 *
 * 三条硬规则：
 * 1. **白名单制**：不在名单里的 openid 一律不回复。陌生人开口时只记一条"待批准"，
 *    等用户在 App 里点同意——否则任何人加上机器人就能跟她聊，记忆也会被污染。
 * 2. **一人一条记忆线**：走 ChatCore 时传 scope = openid，
 *    消息、事实、摘要、心境都按 `pid#openid` 分开。人格设定共享，记忆各记各的。
 * 3. **重复推送去重**：同一个 msg_id 可能被推多次，必须挡掉，否则她会把同一句话说两遍。
 */
object QqBot {

    private val bots = ConcurrentHashMap<String, QqGateway>()
    private val states = ConcurrentHashMap<String, String>()

    @Volatile var lastError: String = ""

    private val seen = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---------------------------------------------------------------- 生命周期

    /** 启动所有 enabled 且凭据齐全的账号 */
    fun startAll(ctx: Context) {
        val db = Db.get(ctx)
        for (a in QqAccounts.all(db)) {
            if (a.enabled && a.ready) startOne(ctx.applicationContext, a)
        }
    }

    fun startOne(ctx: Context, acc: QqAccount) {
        if (bots[acc.appId]?.isRunning() == true) return
        bots.remove(acc.appId)?.stop()
        states[acc.appId] = "启动中"
        val g = QqGateway(
            ctx = ctx.applicationContext,
            acc = acc,
            onEvent = { a, type, data -> onEvent(ctx.applicationContext, a, type, data) },
            onState = { s -> states[acc.appId] = s },
        )
        bots[acc.appId] = g
        g.start()
    }

    fun stop(appId: String) {
        bots.remove(appId)?.stop()
        states[appId] = "未启动"
    }

    fun stopAll() {
        bots.values.forEach { runCatching { it.stop() } }
        bots.clear()
        states.clear()
    }

    fun isRunning(appId: String): Boolean = bots[appId]?.isRunning() == true

    fun state(appId: String): String = states[appId] ?: "未启动"

    fun anyRunning(): Boolean = bots.values.any { it.isRunning() }

    /** 给界面用的一句话总览 */
    fun summary(ctx: Context): String {
        val list = QqAccounts.all(Db.get(ctx)).filter { it.enabled && it.ready }
        if (list.isEmpty()) return "未接入"
        val on = list.count { isRunning(it.appId) }
        return if (on == 0) "已配置 ${list.size} 个，未连接" else "$on / ${list.size} 在线"
    }

    /** 只验证凭据能不能用，不建长连接 */
    fun test(ctx: Context, acc: QqAccount): String {
        val me = QqApi.me(ctx, acc)
        val name = me.optString("username", me.optString("nickname", ""))
        val id = me.optString("id", "")
        return if (name.isNotBlank() || id.isNotBlank()) {
            "凭据可用：$name ${if (id.isNotBlank()) "($id)" else ""}".trim()
        } else "凭据可用"
    }

    // ---------------------------------------------------------------- 事件

    private fun onEvent(ctx: Context, acc: QqAccount, type: String, d: JSONObject) {
        when (type) {
            "C2C_MESSAGE_CREATE" -> handleC2C(ctx, acc, d)
            "GROUP_AT_MESSAGE_CREATE" -> handleGroup(ctx, acc, d)
            "C2C_MSG_REJECT" -> { lastError = "用户在 QQ 资料卡关闭了「允许主动发送」" }
        }
    }

    /**
     * 群聊：只在被 @ 时回复（订阅的是 GROUP_AT_MESSAGE_CREATE，不是全量消息）。
     *
     * 两处和私聊不同：
     * 1. 被动回复窗口是 **5 分钟 / 5 次**（私聊 60 分钟 / 4 次），群里更要克制；
     * 2. 记忆用**群级**（scope = g:<group_openid>），不是按人分——
     *    否则她在群里对 A 说"你上次提到…"时，B 完全接不上话，像两个人各聊各的。
     *    但每次会告诉模型这话是谁说的，免得把 A 的事安到 B 头上。
     */
    private fun handleGroup(ctx: Context, acc: QqAccount, d: JSONObject) {
        val msgId = d.optString("id", "")
        val groupId = d.optString("group_openid", "")
        val memberId = d.optJSONObject("author")?.optString("member_openid", "").orEmpty()
        var content = d.optString("content", "").trim()
        if (msgId.isBlank() || groupId.isBlank() || content.isBlank()) return

        // 群里 content 前面带 <@!bot_id> 这类标记，去掉
        content = content.replace(Regex("<@!?[^>]*>"), "").trim()
        if (content.isBlank()) return

        val now = System.currentTimeMillis()
        if (seen.putIfAbsent("${acc.appId}:$msgId", now) != null) return
        if (seen.size > 300) {
            val cut = now - 10 * 60_000
            seen.entries.removeIf { it.value < cut }
        }

        val groupKey = "g:$groupId"
        scope.launch {
            val db = Db.get(ctx)
            try {
                // 群也要在白名单里，否则谁把机器人拉进群都能用
                if (!QqAccounts.allowed(acc, groupKey)) {
                    QqAccounts.pendingAdd(ctx, acc.appId, groupKey)
                    return@launch
                }
                var pid = acc.personaId
                if (pid.isBlank()) pid = db.personas().firstOrNull()?.id ?: ""
                if (pid.isBlank()) {
                    QqApi.sendGroup(ctx, acc, groupId, "还没有人格，去 App 里建一个。", msgId, 1)
                    return@launch
                }

                // 记忆按群分
                val memScope = groupKey
                val speaker = if (memberId.length > 10) "…" + memberId.takeLast(8) else memberId
                val task = "（补充：这条是群里发的，说话的人是【$speaker】。群里有别人，别把别人的事安到他头上；" +
                    "回复简短一点，像群里插一句话。）"

                ChatCore.saveUserMsg(ctx, content, explicitPid = pid, scope = memScope)
                val raw = ChatCore.reply(ctx, explicitPid = pid, scene = "qq", scope = memScope, task = task)
                QqCommand.rememberOpenId(db, acc.appId, pid, groupId)   // 主动发话也能发到群

                val p = runCatching { db.persona(pid) }.getOrNull()
                val pre = p?.sayPrefix?.ifBlank { "「" } ?: "「"
                val suf = p?.saySuffix?.ifBlank { "」" } ?: "」"
                val stickerKw = QqText.stickerKeyword(raw)
                val speech = QqText.onlySpeech(QqText.removeStickerTag(raw), pre, suf)

                var seq = 1
                // 群聊最多 5 次，留 1 次给表情 → 文本最多 3 条
                for (part in QqText.lines(speech, maxLines = 3)) {
                    if (part.isBlank()) continue
                    QqApi.sendGroup(ctx, acc, groupId, part, msgId, seq)
                    seq++
                }
                if (stickerKw != null && seq <= 5) {
                    runCatching { QqMedia.sendRandomStickerGroup(ctx, acc, groupId, msgId, seq, stickerKw) }
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                runCatching {
                    QqApi.sendGroup(ctx, acc, groupId, "（她好像卡了一下：${(e.message ?: "未知错误").take(50)}）", msgId, 1)
                }
            }
        }
    }

    private fun handleC2C(ctx: Context, acc: QqAccount, d: JSONObject) {
        val msgId = d.optString("id", "")
        val openId = d.optJSONObject("author")?.optString("user_openid", "").orEmpty()
        var content = d.optString("content", "").trim()
        if (msgId.isBlank() || openId.isBlank() || content.isBlank()) return

        // 去重
        val now = System.currentTimeMillis()
        if (seen.putIfAbsent("${acc.appId}:$msgId", now) != null) return
        if (seen.size > 300) {
            val cut = now - 10 * 60_000
            seen.entries.removeIf { it.value < cut }
        }

        scope.launch {
            val db = Db.get(ctx)
            try {
                // ---- 白名单：不在名单里一律不理，只记一笔等人批准 ----
                if (!QqAccounts.allowed(acc, openId)) {
                    QqAccounts.pendingAdd(ctx, acc.appId, openId)
                    return@launch
                }

                var pid = acc.personaId
                if (pid.isBlank()) pid = db.personas().firstOrNull()?.id ?: ""
                if (pid.isBlank()) {
                    QqApi.sendC2C(ctx, acc, openId, "还没有人格。先去 App 里建一个。", msgId, 1)
                    return@launch
                }
                QqCommand.rememberOpenId(db, acc.appId, pid, openId)

                // ---- 固定指令：不经过模型 ----
                QqCommand.handle(ctx, acc, openId, content)?.let { reply ->
                    runCatching {
                        db.addMsg(app.zhiyi.data.Msg(pid = "$pid#$openId", role = "assistant", content = reply))
                    }
                    QqApi.sendC2C(ctx, acc, openId, reply, msgId, 1)
                    return@launch
                }

                // ---- 正常对话：scope = openId，记忆按人分开 ----
                ChatCore.saveUserMsg(ctx, content, explicitPid = pid, scope = openId)
                val raw = ChatCore.reply(ctx, explicitPid = pid, scene = "qq", scope = openId)

                val p = runCatching { db.persona(pid) }.getOrNull()
                val pre = p?.sayPrefix?.ifBlank { "「" } ?: "「"
                val suf = p?.saySuffix?.ifBlank { "」" } ?: "」"

                val stickerKw = QqText.stickerKeyword(raw)
                val speech = QqText.onlySpeech(QqText.removeStickerTag(raw), pre, suf)

                // 同一条原消息最多回 4 次：文本最多 3 片，留 1 次给表情
                var seq = 1
                for (part in QqText.lines(speech, maxLines = 3)) {
                    if (part.isBlank()) continue
                    QqApi.sendC2C(ctx, acc, openId, part, msgId, seq)
                    seq++
                }
                if (stickerKw != null && seq <= 4) {
                    runCatching { QqMedia.sendRandomSticker(ctx, acc, openId, msgId, seq, stickerKw) }
                }
            } catch (e: Throwable) {
                lastError = e.message ?: e.javaClass.simpleName
                runCatching {
                    QqApi.sendC2C(
                        ctx, acc, openId,
                        "（她好像卡了一下：${(e.message ?: "未知错误").take(60)}）", msgId, 1
                    )
                }
            }
        }
    }
}
