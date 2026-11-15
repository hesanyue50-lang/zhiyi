package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db

/**
 * 主动发话的 QQ 出口。
 *
 * 用户把 QQ 配上了，就说明他人就在 QQ 那边——这时候再往手机通知栏塞一条
 * "她发来一条消息"，他根本看不到（App 在后台、通知被划掉）。
 * 所以规则是：**配了 QQ 就优先发 QQ，发不出去再退回通知**。
 *
 * 注意主动推送和被动回复是两回事：
 * - 被动（带 msg_id）：60 分钟内、每条最多 4 次，随便用；
 * - 主动（不带 msg_id）：靠权限 + 频控（个人认证单聊 20 条/分钟、每用户每天 1000 条），
 *   而且用户可以在 QQ 资料卡里关掉「允许主动发送」，关掉后一律失败。
 * 所以这里失败必须安静地退回通知，不能让主动发话整个失败。
 */
object QqOut {

    /** 主动发话黑名单：这个 openid 不希望被主动打扰 */
    fun blocked(db: Db, pid: String, openId: String): Boolean =
        db.get("pro_block_$pid", "").split(",").filter { it.isNotBlank() }.contains(openId)

    fun setBlocked(ctx: Context, pid: String, openId: String, blocked: Boolean) {
        val db = Db.get(ctx)
        val cur = db.get("pro_block_$pid", "").split(",").filter { it.isNotBlank() }.toMutableSet()
        if (blocked) cur.add(openId) else cur.remove(openId)
        db.put("pro_block_$pid", cur.joinToString(","))
    }

    /** 找到绑定了这个人格、并且开着 QQ 的账号 */
    private fun accountFor(db: Db, pid: String): QqAccount? =
        QqAccounts.all(db).firstOrNull { it.personaId == pid && it.enabled && it.ready }

    /** 是否具备"能主动发"的条件（不实际发送） */
    fun ready(ctx: Context, pid: String): Boolean {
        val db = Db.get(ctx)
        val acc = accountFor(db, pid) ?: return false
        // 主动推送是 HTTP 调用，不依赖 WebSocket 长连接——
        // 服务被系统杀掉时照样能发，别因为 isRunning 就把这条路堵死
        if (acc.groupProactive) return acc.groups.isNotEmpty()
        return QqCommand.openIdFor(db, acc.appId, pid).isNotBlank()
    }

    /**
     * 优先走 QQ 说这句话。
     * @return true = 已经通过 QQ 发出去了，调用方不要再发通知
     */
    fun send(ctx: Context, pid: String, text: String): Boolean {
        if (!ready(ctx, pid)) return false
        val db = Db.get(ctx)
        val acc = accountFor(db, pid) ?: return false
        val openId = QqCommand.openIdFor(db, acc.appId, pid)
        if (blocked(db, pid, openId)) return false

        val p = runCatching { db.persona(pid) }.getOrNull()
        val pre = p?.sayPrefix?.ifBlank { "「" } ?: "「"
        val suf = p?.saySuffix?.ifBlank { "」" } ?: "」"
        // 主动消息同样是"打字"，旁白一律不出现
        val speech = QqText.onlySpeech(text, pre, suf)
        if (speech.isBlank()) return false

        // 开了"群聊主动发话"就发群：他在群里聊得多，主动找他也该在群里
        if (acc.groupProactive) {
            if (acc.groups.isNotEmpty()) {
                val g = acc.groups.random()
                return try {
                    QqApi.sendGroup(ctx, acc, g, speech.take(400), msgId = null)
                    db.putNum("qq_last_proactive_ts", System.currentTimeMillis())
                    true
                } catch (e: Throwable) {
                    QqBot.lastError = "群里主动发话失败：${e.message ?: e.javaClass.simpleName}"
                    false
                }
            }
        }

        return try {
            QqApi.sendC2C(ctx, acc, openId, speech.take(400), msgId = null)
            db.putNum("qq_last_proactive_ts", System.currentTimeMillis())
            true
        } catch (e: Throwable) {
            // 常见原因：用户在 QQ 资料卡关了「允许主动发送」、当天额度用完、token 过期
            QqBot.lastError = "主动发话没发出去：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }
}
