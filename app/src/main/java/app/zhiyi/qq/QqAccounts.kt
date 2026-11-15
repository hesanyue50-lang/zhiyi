package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 QQ 机器人账号 = 一个 AppID = 一个人格。
 *
 * 为什么这样设计：腾讯的 openid 是**按机器人**分配的，同一个人在不同机器人下
 * 拿到的 openid 完全不同，也没法换算成 QQ 号。所以"一个账号一个人格"是最省事、
 * 也最不容易串的隔离单位——每个人格有自己独立的机器人、独立的白名单、独立的记忆。
 *
 * 记忆隔离靠 `pid#openid`：人格仍然是同一份设定，但每个白名单用户看到的是
 * 属于他自己的那条记忆线（她记得他说过的话，不会跟别人混）。
 */
data class QqAccount(
    val appId: String,
    val secret: String = "",
    val personaId: String = "",
    /** 允许对话的 openid 列表；空 = 谁都不理，避免陌生人一上来就能聊 */
    val whitelist: List<String> = emptyList(),
    val enabled: Boolean = false,
    /** 开了以后，主动发话发到群里（白名单里的群），而不是私聊 */
    val groupProactive: Boolean = false,
) {
    val id: String get() = appId
    val ready: Boolean get() = appId.isNotBlank() && secret.isNotBlank()
}

object QqAccounts {

    private const val KEY = "qq_accounts"

    fun all(db: Db): List<QqAccount> {
        migrateLegacy(db)
        val raw = db.get(KEY, "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<QqAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val wl = ArrayList<String>()
            o.optJSONArray("whitelist")?.let { w ->
                for (k in 0 until w.length()) w.optString(k).takeIf { it.isNotBlank() }?.let { wl.add(it) }
            }
            out.add(
                QqAccount(
                    appId = o.optString("appId"),
                    secret = o.optString("secret"),
                    personaId = o.optString("personaId"),
                    whitelist = wl,
                    enabled = o.optBoolean("enabled", false),
                    groupProactive = o.optBoolean("groupProactive", false),
                )
            )
        }
        return out
    }

    fun save(db: Db, list: List<QqAccount>) {
        val arr = JSONArray()
        for (a in list) {
            arr.put(
                JSONObject()
                    .put("appId", a.appId)
                    .put("secret", a.secret)
                    .put("personaId", a.personaId)
                    .put("whitelist", JSONArray(a.whitelist))
                    .put("enabled", a.enabled)
                    .put("groupProactive", a.groupProactive)
            )
        }
        db.put(KEY, arr.toString())
    }

    fun upsert(ctx: Context, a: QqAccount) {
        val db = Db.get(ctx)
        val list = all(db).toMutableList()
        val i = list.indexOfFirst { it.appId == a.appId }
        if (i >= 0) list[i] = a else list.add(a)
        save(db, list)
    }

    fun remove(ctx: Context, appId: String) {
        val db = Db.get(ctx)
        save(db, all(db).filterNot { it.appId == appId })
        // 顺手把这个账号的运行时缓存清掉
        db.put("qq_token_$appId", "")
        db.put("qq_token_exp_$appId", "0")
    }

    fun get(db: Db, appId: String): QqAccount? = all(db).firstOrNull { it.appId == appId }

    /** 某个 openid 在某账号下是否被允许 */
    fun allowed(a: QqAccount, openId: String): Boolean = a.whitelist.contains(openId)

    fun allow(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        if (a.whitelist.contains(openId)) return
        upsert(ctx, a.copy(whitelist = a.whitelist + openId))
        pendingRemove(ctx, appId, openId)
    }

    fun deny(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        upsert(ctx, a.copy(whitelist = a.whitelist - openId))
        pendingRemove(ctx, appId, openId)
    }

    // ---- 待批准：陌生人发消息时不回复，先记下来让用户在 App 里点 ----

    fun pending(db: Db, appId: String): List<Pair<String, Long>> {
        val raw = db.get("qq_pending_$appId", "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<Pair<String, Long>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("openId")
            if (id.isNotBlank()) out.add(id to o.optLong("ts", 0))
        }
        return out
    }

    fun pendingAdd(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val now = System.currentTimeMillis()
        val list = pending(db, appId).filterNot { it.first == openId } + (openId to now)
        // 只留最近 30 条，别无限长
        val arr = JSONArray()
        list.takeLast(30).forEach { (id, ts) ->
            arr.put(JSONObject().put("openId", id).put("ts", ts))
        }
        db.put("qq_pending_$appId", arr.toString())
    }

    private fun pendingRemove(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val arr = JSONArray()
        pending(db, appId).filterNot { it.first == openId }.forEach { (id, ts) ->
            arr.put(JSONObject().put("openId", id).put("ts", ts))
        }
        db.put("qq_pending_$appId", arr.toString())
    }

    /**
     * 老版本只有单账号（qq_app_id / qq_secret / qq_enabled）。
     * 第一次读到新结构时把老的搬过来，免得用户重填。
     */
    private fun migrateLegacy(db: Db) {
        if (db.get("qq_migrated", "0") == "1") return
        val appId = db.get("qq_app_id", "")
        if (appId.isNotBlank()) {
            val a = QqAccount(
                appId = appId,
                secret = db.get("qq_secret", ""),
                personaId = db.personas().firstOrNull()?.id ?: "",
                whitelist = emptyList(),
                enabled = db.get("qq_enabled", "0") == "1",
            )
            save(db, listOf(a))
        }
        db.put("qq_migrated", "1")
    }
}
