package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个 QQ 机器人账号 = 一个 AppID = 一个人格。
 *
 * 隔离单位为什么是"账号"：腾讯的 openid 按机器人分配，同一个人在不同机器人下
 * 拿到的 openid 完全不同，也没法换算成 QQ 号。所以账号是最自然、最不会串的边界。
 */
data class QqAccount(
    val appId: String,
    val secret: String = "",
    val personaId: String = "",
    /** 允许私聊的 openid */
    val people: List<String> = emptyList(),
    /** 允许的群（group_openid，不带 g: 前缀） */
    val groups: List<String> = emptyList(),
    /**
     * 访问模式。**显式开关，不靠列表空不空来猜**——
     * 之前用"名单为空就放行"当默认，结果加了第一个人之后其他人突然全被挡，
     * 而用户完全不知道发生了什么。
     *
     * - open：谁都能聊（自己用的默认）
     * - strict：只认 people / groups 里的
     */
    val mode: String = MODE_OPEN,
    val enabled: Boolean = false,
    /** 开了以后，主动发话发到群里（groups 里的群），而不是私聊 */
    val groupProactive: Boolean = false,
    /**
     * 群里的"主人"（member_openid）。
     *
     * 为什么要单独记：私聊用的是 user_openid，群里用的是 member_openid，
     * 官方**不提供两者之间的对应关系**，所以没法自动认出"群里这个人就是私聊那个人"，
     * 只能在 App 里手动标一次。
     */
    val owners: List<String> = emptyList(),
) {
    companion object {
        const val MODE_OPEN = "open"
        const val MODE_STRICT = "strict"
    }

    val id: String get() = appId
    val ready: Boolean get() = appId.isNotBlank() && secret.isNotBlank()
    val strict: Boolean get() = mode == MODE_STRICT
}

object QqAccounts {

    private const val KEY = "qq_accounts"

    // ---------------------------------------------------------------- 读写

    fun all(db: Db): List<QqAccount> {
        migrateLegacy(db)
        val raw = db.get(KEY, "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<QqAccount>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val people = ArrayList<String>()
            val groups = ArrayList<String>()
            o.optJSONArray("people")?.let { w ->
                for (k in 0 until w.length()) w.optString(k).takeIf { it.isNotBlank() }?.let { people.add(it) }
            }
            o.optJSONArray("groups")?.let { w ->
                for (k in 0 until w.length()) w.optString(k).takeIf { it.isNotBlank() }?.let { groups.add(it) }
            }
            // 老数据：群用 "g:" 前缀混在 whitelist 里，读出来时拆开放
            o.optJSONArray("whitelist")?.let { w ->
                for (k in 0 until w.length()) {
                    val v = w.optString(k)
                    if (v.isBlank()) continue
                    if (v.startsWith("g:")) groups.add(v.removePrefix("g:")) else people.add(v)
                }
            }

            out.add(
                QqAccount(
                    appId = o.optString("appId"),
                    secret = o.optString("secret"),
                    personaId = o.optString("personaId"),
                    people = people,
                    groups = groups,
                    mode = o.optString("mode").ifBlank {
                        // 老数据没有 mode：有名单就当严格，没名单就当开放
                        if (people.isEmpty() && groups.isEmpty()) QqAccount.MODE_OPEN else QqAccount.MODE_STRICT
                    },
                    enabled = o.optBoolean("enabled", false),
                    groupProactive = o.optBoolean("groupProactive", false),
                    owners = run {
                        val l = ArrayList<String>()
                        o.optJSONArray("owners")?.let { w ->
                            for (k in 0 until w.length()) w.optString(k).takeIf { it.isNotBlank() }?.let { l.add(it) }
                        }
                        l
                    },
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
                    .put("people", JSONArray(a.people))
                    .put("groups", JSONArray(a.groups))
                    .put("mode", a.mode)
                    .put("enabled", a.enabled)
                    .put("groupProactive", a.groupProactive)
                    .put("owners", JSONArray(a.owners))
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
        db.put("qq_token_$appId", "")
        db.put("qq_token_exp_$appId", "0")
        db.put("qq_pending_$appId", "[]")
    }

    fun get(db: Db, appId: String): QqAccount? = all(db).firstOrNull { it.appId == appId }

    // ---------------------------------------------------------------- 权限判断

    /** 私聊：这个人能不能对话 */
    fun allowPerson(a: QqAccount, openId: String): Boolean =
        !a.strict || a.people.contains(openId)

    /** 群：这个群能不能对话 */
    fun allowGroup(a: QqAccount, groupOpenId: String): Boolean =
        !a.strict || a.groups.contains(groupOpenId)

    fun addPerson(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        if (!a.people.contains(openId)) upsert(ctx, a.copy(people = a.people + openId))
        pendingRemove(ctx, appId, openId)
    }

    fun removePerson(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        upsert(ctx, a.copy(people = a.people - openId))
    }

    fun addGroup(ctx: Context, appId: String, groupOpenId: String) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        if (!a.groups.contains(groupOpenId)) upsert(ctx, a.copy(groups = a.groups + groupOpenId))
        pendingRemove(ctx, appId, "g:$groupOpenId")
        pendingRemove(ctx, appId, groupOpenId)
    }

    fun removeGroup(ctx: Context, appId: String, groupOpenId: String) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        upsert(ctx, a.copy(groups = a.groups - groupOpenId))
    }

    // ---------------------------------------------------------------- 群成员

    /**
     * 见过面的群成员。私聊和群的 openid 不是一套，没法互相关联，
     * 所以"谁是我"这件事只能靠这张表在 App 里手点一次。
     */
    fun members(db: Db, appId: String): List<String> {
        val arr = runCatching { JSONArray(db.get("qq_members_$appId", "[]")) }.getOrDefault(JSONArray())
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val v = arr.optString(i)
            if (v.isNotBlank()) out.add(v)
        }
        return out
    }

    fun rememberMember(ctx: Context, appId: String, memberId: String) {
        if (memberId.isBlank()) return
        val list = members(Db.get(ctx), appId).toMutableList()
        if (!list.contains(memberId)) {
            list.add(memberId)
            while (list.size > 50) list.removeAt(0)
            Db.get(ctx).put("qq_members_$appId", JSONArray(list).toString())
        }
    }

    fun setOwner(ctx: Context, appId: String, memberId: String, owner: Boolean) {
        val db = Db.get(ctx)
        val a = get(db, appId) ?: return
        val next = if (owner) a.owners + memberId else a.owners - memberId
        upsert(ctx, a.copy(owners = next.distinct()))
    }

    // ---------------------------------------------------------------- 待批准

    /**
     * 谁找过它。两种模式下都记：
     * - open 模式下记，是为了让你知道"有谁在跟它说话"，方便一键收进名单；
     * - strict 模式下记，是因为拦下来的那些人得让你看见。
     */
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
        val a = get(db, appId) ?: return
        // 已经在名单里的不用再记
        val bare = openId.removePrefix("g:")
        val known = a.people.contains(openId) || a.groups.contains(bare)
        val now = System.currentTimeMillis()
        val list = if (known) pending(db, appId).filterNot { it.first == openId }
        else pending(db, appId).filterNot { it.first == openId } + (openId to now)
        val arr = JSONArray()
        list.takeLast(30).forEach { (id, ts) ->
            arr.put(JSONObject().put("openId", id).put("ts", ts))
        }
        db.put("qq_pending_$appId", arr.toString())
    }

    fun pendingRemove(ctx: Context, appId: String, openId: String) {
        val db = Db.get(ctx)
        val arr = JSONArray()
        pending(db, appId).filterNot { it.first == openId }.forEach { (id, ts) ->
            arr.put(JSONObject().put("openId", id).put("ts", ts))
        }
        db.put("qq_pending_$appId", arr.toString())
    }

    /** 这个 openid 是不是一个群（老数据用 g: 前缀） */
    fun isGroupKey(key: String): Boolean = key.startsWith("g:")

    // ---------------------------------------------------------------- 迁移

    private fun migrateLegacy(db: Db) {
        if (db.get("qq_migrated", "0") == "1") return
        val appId = db.get("qq_app_id", "")
        if (appId.isNotBlank()) {
            val a = QqAccount(
                appId = appId,
                secret = db.get("qq_secret", ""),
                personaId = db.personas().firstOrNull()?.id ?: "",
                enabled = db.get("qq_enabled", "0") == "1",
            )
            save(db, listOf(a))
        }
        db.put("qq_migrated", "1")
    }
}
