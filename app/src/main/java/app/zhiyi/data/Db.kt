package app.zhiyi.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer

/** 全部记忆落在一个 SQLite 文件里，可导出、可备份 */
class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "zhiyi.db", null, 7) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE msg(
            id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, role TEXT, content TEXT,
            ts INTEGER, tokens INTEGER, summarized INTEGER DEFAULT 0, images TEXT DEFAULT '')""")
        db.execSQL("CREATE INDEX idx_msg_pid ON msg(pid, id)")
        db.execSQL("""CREATE TABLE fact(
            id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, subject TEXT, predicate TEXT, obj TEXT,
            importance REAL, created INTEGER, last_hit INTEGER, hits INTEGER DEFAULT 0,
            active INTEGER DEFAULT 1, src TEXT DEFAULT '')""")
        db.execSQL("CREATE INDEX idx_fact_pid ON fact(pid, active)")
        db.execSQL("""CREATE TABLE episode(
            id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, title TEXT, summary TEXT,
            importance REAL, ts INTEGER, last_hit INTEGER, vec BLOB, src TEXT DEFAULT '')""")
        db.execSQL("CREATE INDEX idx_ep_pid ON episode(pid)")
        db.execSQL("""CREATE TABLE summary(
            id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, level INTEGER, upto INTEGER,
            content TEXT, ts INTEGER)""")
        db.execSQL("""CREATE TABLE persona(
            id TEXT PRIMARY KEY, name TEXT, json TEXT, updated INTEGER)""")
        // 人格的不可变版本历史。发布 = 追加一条；回滚也是追加（把旧版重发一次）
        db.execSQL("""CREATE TABLE IF NOT EXISTS persona_rev(
            pid TEXT, rev INTEGER, name TEXT, json TEXT, ts INTEGER, note TEXT DEFAULT 'edit',
            PRIMARY KEY(pid, rev))""")
        db.execSQL("""CREATE TABLE provider(
            id TEXT PRIMARY KEY, name TEXT, base TEXT, key TEXT, chat_model TEXT,
            cheap_model TEXT, embed_model TEXT, vision_model TEXT DEFAULT '')""")
        db.execSQL("CREATE TABLE kv(k TEXT PRIMARY KEY, v TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS diary(id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, date TEXT, content TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS life(id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, content TEXT, ts INTEGER)")
        db.execSQL("CREATE TABLE world(id TEXT PRIMARY KEY, name TEXT, desc TEXT, updated INTEGER, json TEXT)")
        db.execSQL("""CREATE TABLE proactive(
            ts INTEGER, score REAL, sent INTEGER, trigger TEXT, replied INTEGER)""")
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            // world 表（世界观功能，幂等）
            db.execSQL("CREATE TABLE IF NOT EXISTS world(id TEXT PRIMARY KEY, name TEXT, desc TEXT, updated INTEGER, json TEXT)")
        }
        if (old < 3) {
            // 记忆回溯：fact/episode 记录来源消息 id（列不存在才加，避免重复升级报错）
            runCatching { db.execSQL("ALTER TABLE fact ADD COLUMN src TEXT DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE episode ADD COLUMN src TEXT DEFAULT ''") }
        }
        if (old < 4) {
            // 日记（她写的）、生活流（她的一天）、情绪状态
            db.execSQL("CREATE TABLE IF NOT EXISTS diary(id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, date TEXT, content TEXT, ts INTEGER)")
            db.execSQL("CREATE TABLE IF NOT EXISTS life(id INTEGER PRIMARY KEY AUTOINCREMENT, pid TEXT, content TEXT, ts INTEGER)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_diary_pid ON diary(pid)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_life_pid ON life(pid)")
        }
        if (old < 5) {
            // 多模态：消息可带图片
            runCatching { db.execSQL("ALTER TABLE msg ADD COLUMN images TEXT DEFAULT ''") }
        }
        if (old < 6) {
            // 识屏专用视觉模型（留空=跟随 chatModel）
            runCatching { db.execSQL("ALTER TABLE provider ADD COLUMN vision_model TEXT DEFAULT ''") }
        }
        if (old < 7) {
            // 人格版本历史
            db.execSQL("""CREATE TABLE IF NOT EXISTS persona_rev(
                pid TEXT, rev INTEGER, name TEXT, json TEXT, ts INTEGER, note TEXT DEFAULT 'edit',
                PRIMARY KEY(pid, rev))""")
        }
    }

    // ---------------- world ----------------
    fun saveWorld(w: World) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO world(id,name,desc,updated,json) VALUES(?,?,?,?,?)",
            arrayOf(w.id, w.name, w.desc, System.currentTimeMillis(), w.toJson()),
        )
    }

    fun worlds(): List<World> {
        val out = ArrayList<World>()
        readableDatabase.rawQuery("SELECT id,json FROM world ORDER BY updated DESC", null).use { c ->
            while (c.moveToNext()) {
                val w = World.fromJson(c.getString(1))
                out.add(if (w.id.isBlank()) w.copy(id = c.getString(0)) else w)
            }
        }
        return out
    }

    fun world(id: String): World? {
        if (id.isBlank()) return null
        readableDatabase.rawQuery("SELECT id,json FROM world WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                val w = World.fromJson(c.getString(1))
                return if (w.id.isBlank()) w.copy(id = c.getString(0)) else w
            }
        }
        return null
    }

    fun deleteWorld(id: String) {
        if (id.isBlank()) return
        val rows = ArrayList<Pair<String, String>>()
        readableDatabase.rawQuery("SELECT id,json FROM persona", null).use { c ->
            while (c.moveToNext()) rows.add(c.getString(0) to c.getString(1))
        }
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM world WHERE id=?", arrayOf(id))
            rows.forEach { (pid, json) ->
                val p = Persona.fromJson(json)
                if (p.worldId == id) {
                    writableDatabase.execSQL(
                        "UPDATE persona SET json=? WHERE id=?", arrayOf(p.copy(worldId = "").toJson(), pid),
                    )
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // WAL：UI、通知回复、主动发话三个入口可能同时写，不开 WAL 会 SQLITE_BUSY
        db.enableWriteAheadLogging()
    }

    // ---------------- kv ----------------
    fun put(k: String, v: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", arrayOf(k, v))
    }

    fun putNum(k: String, v: Long) = put(k, v.toString())

    fun get(k: String, def: String = ""): String {
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k=?", arrayOf(k)).use {
            return if (it.moveToFirst()) it.getString(0) else def
        }
    }

    fun getNum(k: String, def: Long = 0): Long = get(k, def.toString()).toLongOrNull() ?: def

    // ---------------- messages ----------------
    fun addMsg(m: Msg): Long {
        val cv = ContentValues().apply {
            put("pid", m.pid); put("role", m.role); put("content", m.content)
            put("ts", m.ts); put("tokens", m.tokens); put("summarized", if (m.summarized) 1 else 0)
            put("images", m.images)
        }
        return writableDatabase.insert("msg", null, cv)
    }

    fun recentMsgs(pid: String, limit: Int): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery(
            "SELECT id,pid,role,content,ts,tokens,summarized,images FROM msg WHERE pid=? ORDER BY id DESC LIMIT ?",
            arrayOf(pid, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Msg(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5), c.getInt(6) == 1, c.getString(7))
            )
        }
        out.reverse()
        return out
    }

    fun msgCount(pid: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM msg WHERE pid=?", arrayOf(pid)).use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun msgsBefore(pid: String, uptoId: Long, limit: Int): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery(
            "SELECT id,pid,role,content,ts,tokens,summarized,images FROM msg WHERE pid=? AND id<=? ORDER BY id DESC LIMIT ?",
            arrayOf(pid, uptoId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Msg(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5), c.getInt(6) == 1, c.getString(7))
            )
        }
        out.reverse()
        return out
    }

    /** 按游标向后取：不会因为"最后 N 条"窗口跳过中间的积压消息 */
    fun msgsAfter(pid: String, afterId: Long, limit: Int): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery(
            "SELECT id,pid,role,content,ts,tokens,summarized,images FROM msg WHERE pid=? AND id>? ORDER BY id ASC LIMIT ?",
            arrayOf(pid, afterId.toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Msg(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5), c.getInt(6) == 1, c.getString(7))
            )
        }
        return out
    }

    fun countAfter(pid: String, afterId: Long): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM msg WHERE pid=? AND id>?", arrayOf(pid, afterId.toString()),
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    fun markSummarized(pid: String, uptoId: Long) {
        writableDatabase.execSQL("UPDATE msg SET summarized=1 WHERE pid=? AND id<=?", arrayOf(pid, uptoId))
    }

    /** 只标这一批，绝不"从头到 upto"一把标掉 */
    fun markSummarizedRange(pid: String, fromExclusive: Long, toInclusive: Long) {
        writableDatabase.execSQL(
            "UPDATE msg SET summarized=1 WHERE pid=? AND id>? AND id<=?",
            arrayOf(pid, fromExclusive.toString(), toInclusive.toString()),
        )
    }

    /** 这条消息之后的下一条"用户"消息（撤回时用来确定删到哪） */
    fun firstUserIdAfter(pid: String, afterId: Long): Long? {
        readableDatabase.rawQuery(
            "SELECT MIN(id) FROM msg WHERE pid=? AND id>? AND role='user'",
            arrayOf(pid, afterId.toString()),
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return null
    }

    fun deleteMsgsRange(pid: String, fromId: Long, toId: Long) {
        writableDatabase.execSQL(
            "DELETE FROM msg WHERE pid=? AND id>=? AND id<=?",
            arrayOf(pid, fromId.toString(), toId.toString()),
        )
    }

    /** 清会话：只删聊天记录，不动记忆库（fact/episode/summary 保留） */
    fun clearMsgs(pid: String) {
        writableDatabase.execSQL("DELETE FROM msg WHERE pid=?", arrayOf(pid))
        // 游标是"消息总数"，删空后不清会让 total-lastfact 永远为负 -> 抽取再也不跑
        writableDatabase.execSQL("DELETE FROM kv WHERE k LIKE 'lastfact_%'")
    }

    /** 清记忆库：删事实/回忆/摘要，聊天记录保留 */
    fun clearMemory(pid: String) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.execSQL("DELETE FROM fact WHERE pid=?", arrayOf(pid))
            writableDatabase.execSQL("DELETE FROM episode WHERE pid=?", arrayOf(pid))
            writableDatabase.execSQL("DELETE FROM summary WHERE pid=?", arrayOf(pid))
            writableDatabase.execSQL("DELETE FROM kv WHERE k GLOB 'lastfact_'||?||'*'", arrayOf(pid))
            writableDatabase.execSQL("DELETE FROM kv WHERE k GLOB 'sumto_'||?||'*'", arrayOf(pid))
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // ---------------- facts ----------------
    /**
     * 冲突消解：同一 (subject, predicate) 出现新值时，旧值不删除而是 active=0，
     * 保证时间线还在，但不再干扰当下的判断。
     */
    private fun editDistance(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0) return n; if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[n]
    }

    /** 一次性迁移：把历史事实的 predicate 归一化到固定分类 */
    fun migratePredicates(): Int {
        val rows = ArrayList<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT id,predicate FROM fact", null).use { c ->
            while (c.moveToNext()) rows.add(c.getLong(0) to c.getString(1))
        }
        var n = 0
        writableDatabase.beginTransaction()
        try {
            for ((id, p) in rows) {
                val fixed = app.zhiyi.memory.FactCategories.normalize(p)
                if (fixed != p) {
                    writableDatabase.execSQL("UPDATE fact SET predicate=? WHERE id=?", arrayOf(fixed, id))
                    n++
                }
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return n
    }

    fun upsertFact(f: Fact): Long {
        writableDatabase.beginTransaction()
        try {
        // 顺带把 src 查出来：用户手填/手改过的条目归用户所有，AI 不能把它关掉
        val old = ArrayList<Triple<Long, String, String>>()
        readableDatabase.rawQuery(
            "SELECT id,obj,COALESCE(src,'') FROM fact WHERE pid=? AND subject=? AND predicate=? AND active=1",
            arrayOf(f.pid, f.subject, f.predicate),
        ).use { c -> while (c.moveToNext()) old.add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
        // 命中判定放宽：完全相等 / 互相包含 / 编辑距离 <= max(2, 长度/8)
        val same = old.firstOrNull { pair ->
            val a = pair.second.trim(); val b = f.obj.trim()
            if (a.isEmpty() || b.isEmpty()) return@firstOrNull false
            a == b || a.contains(b) || b.contains(a) || editDistance(a, b) <= maxOf(2, minOf(a.length, b.length) / 8)
        }
        if (same != null) {
            writableDatabase.execSQL(
                "UPDATE fact SET hits=hits+1, last_hit=?, importance=MIN(1.0, importance+0.05) WHERE id=?",
                arrayOf(System.currentTimeMillis(), same.first),
            )
            writableDatabase.setTransactionSuccessful()   // 不补这行，return 会触发回滚，hits 永远涨不上去
            return same.first
        }
        // 计划/承诺允许多条并存（周三面试 + 周五交报告），不把旧计划置 inactive
        // 用户手填/手改过的（src=user）也不由 AI 关掉——AI 只能追加，不能撤销用户的记忆
        if (f.predicate != "计划") {
            old.forEach {
                if (it.third == "user" && f.src != "user") return@forEach
                writableDatabase.execSQL("UPDATE fact SET active=0 WHERE id=?", arrayOf(it.first))
            }
        }
        writableDatabase.setTransactionSuccessful()
        val cv = ContentValues().apply {
            put("pid", f.pid); put("subject", f.subject); put("predicate", f.predicate)
            put("obj", f.obj); put("importance", f.importance); put("created", f.created)
            put("last_hit", f.lastHit); put("hits", 0); put("active", 1); put("src", f.src)
        }
        return writableDatabase.insert("fact", null, cv)
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun topFacts(pid: String, limit: Int): List<Fact> {
        val out = ArrayList<Fact>()
        // 重要度 + 清晰度 + 命中次数 综合排序。
        // 原来只有"重要度 + 是否 30 天内提过"这种硬阈值，等于要么新鲜要么过气；
        // 换成连续的清晰度衰减，排在前面的是"既重要、又还说得清"的那些。
        readableDatabase.rawQuery(
            """SELECT id,pid,subject,predicate,obj,importance,created,last_hit,hits,src FROM fact
               WHERE pid=? AND active=1
               ORDER BY (importance * 0.55
                         + MAX(0.15, 1.0 - (? - last_hit) / 86400000.0 / (14.0 + 46.0 * importance)) * 0.45
                         + MIN(hits, 20) * 0.005) DESC,
                        last_hit DESC LIMIT ?""",
            arrayOf(pid, System.currentTimeMillis().toString(), limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getDouble(5), c.getLong(6), c.getLong(7), c.getInt(8), c.getString(9))
            )
        }
        return out
    }

    fun allFacts(pid: String): List<Fact> {
        val out = ArrayList<Fact>()
        readableDatabase.rawQuery(
            "SELECT id,pid,subject,predicate,obj,importance,created,last_hit,hits,src FROM fact WHERE pid=? AND active=1 ORDER BY importance DESC, last_hit DESC",
            arrayOf(pid),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getDouble(5), c.getLong(6), c.getLong(7), c.getInt(8), c.getString(9))
            )
        }
        return out
    }

    /** 分页读事实：记忆页滚动加载用，避免一次读全量 */
    /** 一个会话的概览（QQ 那侧的记忆按 "pid#openid" 存，这里把它们列出来） */
    data class ThreadStat(val key: String, val msgs: Int, val lastTs: Long)

    /**
     * 列出所有"带作用域的"会话（pid 里含 #），也就是 QQ 那边每个人/每个群各一条的记忆线。
     * App 主界面用的是裸 pid，所以这些会话默认看不到——需要单独的入口去翻。
     */
    fun scopedThreads(): List<ThreadStat> {
        val out = ArrayList<ThreadStat>()
        readableDatabase.rawQuery(
            "SELECT pid, COUNT(*), MAX(ts) FROM msg WHERE pid LIKE '%#%' GROUP BY pid ORDER BY MAX(ts) DESC LIMIT 100",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ThreadStat(c.getString(0), c.getInt(1), c.getLong(2)))
            }
        }
        return out
    }

    fun factsPage(pid: String, offset: Int, limit: Int): List<Fact> {
        val out = ArrayList<Fact>()
        readableDatabase.rawQuery(
            "SELECT id,pid,subject,predicate,obj,importance,created,last_hit,hits,src FROM fact WHERE pid=? AND active=1 ORDER BY importance DESC, last_hit DESC LIMIT ? OFFSET ?",
            arrayOf(pid, limit.toString(), offset.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Fact(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4),
                    c.getDouble(5), c.getLong(6), c.getLong(7), c.getInt(8), c.getString(9))
            )
        }
        return out
    }

    fun deleteFact(id: Long) = writableDatabase.execSQL("DELETE FROM fact WHERE id=?", arrayOf(id))

    /** 合并双消息流：老版本聊天界面的 pid#chat 消息并回 pid（一次性，按 id 排序即时间序） */
    fun mergeChatThreads() {
        writableDatabase.execSQL(
            "UPDATE msg SET pid = substr(pid, 1, length(pid)-5) WHERE pid LIKE '%#chat'"
        )
    }

    fun updateFact(id: Long, subject: String, predicate: String, obj: String, importance: Double) {
        // src 打上 user：这条从此归用户所有，AI 只能追加命中、不能再把它置 inactive
        writableDatabase.execSQL(
            "UPDATE fact SET subject=?, predicate=?, obj=?, importance=?, src='user' WHERE id=?",
            arrayOf(subject, predicate, obj, importance, id),
        )
    }

    fun addDiary(pid: String, date: String, content: String) {
        val cv = ContentValues().apply {
            put("pid", pid); put("date", date); put("content", content); put("ts", System.currentTimeMillis())
        }
        writableDatabase.insert("diary", null, cv)
    }

    fun diaries(pid: String): List<Triple<Long, String, String>> {
        val out = ArrayList<Triple<Long, String, String>>()
        readableDatabase.rawQuery("SELECT id,date,content FROM diary WHERE pid=? ORDER BY id DESC", arrayOf(pid))
            .use { c -> while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getString(1), c.getString(2))) }
        return out
    }

    fun addLife(pid: String, content: String) {
        val cv = ContentValues().apply { put("pid", pid); put("content", content); put("ts", System.currentTimeMillis()) }
        writableDatabase.insert("life", null, cv)
    }

    fun recentLife(pid: String, limit: Int): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT content FROM life WHERE pid=? ORDER BY id DESC LIMIT ?",
            arrayOf(pid, limit.toString())).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** 记忆回溯：按一组消息 id 取原文 */
    fun msgsByIds(ids: List<Long>): List<Msg> {
        if (ids.isEmpty()) return emptyList()
        val ph = ids.joinToString(",") { "?" }
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery(
            "SELECT id,pid,role,content,ts FROM msg WHERE id IN ($ph) ORDER BY id ASC",
            ids.map { it.toString() }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) out.add(Msg(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4)))
        }
        return out
    }

    // ---------------- episodes ----------------
    fun addEpisode(e: Episode): Long {
        val cv = ContentValues().apply {
            put("pid", e.pid); put("title", e.title); put("summary", e.summary)
            put("importance", e.importance); put("ts", e.ts); put("last_hit", e.lastHit)
            put("vec", e.vec?.let { toBlob(it) }); put("src", e.src)
        }
        return writableDatabase.insert("episode", null, cv)
    }

    fun allEpisodes(pid: String): List<Episode> {
        val out = ArrayList<Episode>()
        readableDatabase.rawQuery(
            "SELECT id,pid,title,summary,importance,ts,last_hit,vec,src FROM episode WHERE pid=? ORDER BY ts DESC",
            arrayOf(pid),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Episode(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getDouble(4), c.getLong(5), c.getLong(6), c.getBlob(7)?.let { fromBlob(it) }, c.getString(8))
            )
        }
        return out
    }

    /** 只读元数据，不带向量 —— 召回粗排阶段用，避免每轮把几千条 BLOB 全拉进内存 */
    fun episodesLite(pid: String, limit: Int): List<Episode> {
        val out = ArrayList<Episode>()
        readableDatabase.rawQuery(
            "SELECT id,pid,title,summary,importance,ts,last_hit FROM episode WHERE pid=? ORDER BY ts DESC LIMIT ?",
            arrayOf(pid, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) out.add(
                Episode(c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getDouble(4), c.getLong(5), c.getLong(6), null)
            )
        }
        return out
    }

    /** 只给精排的这十几条取向量 */
    fun episodeVecs(pid: String, ids: List<Long>): Map<Long, FloatArray> {
        val out = HashMap<Long, FloatArray>()
        if (ids.isEmpty()) return out
        val ph = ids.joinToString(",") { "?" }
        val args = (listOf(pid) + ids.map { it.toString() }).toTypedArray()
        readableDatabase.rawQuery("SELECT id,vec FROM episode WHERE pid=? AND id IN ($ph)", args).use { c ->
            while (c.moveToNext()) {
                val b = c.getBlob(1) ?: continue
                out[c.getLong(0)] = fromBlob(b)
            }
        }
        return out
    }

    fun hitEpisode(id: Long) = writableDatabase.execSQL(
        "UPDATE episode SET last_hit=?, importance=MIN(1.0, importance+0.04) WHERE id=?",
        arrayOf(System.currentTimeMillis(), id),
    )

    fun deleteEpisode(id: Long) = writableDatabase.execSQL("DELETE FROM episode WHERE id=?", arrayOf(id))

    // ---------------- summaries ----------------
    fun addSummary(s: SummaryRow): Long {
        val cv = ContentValues().apply {
            put("pid", s.pid); put("level", s.level); put("upto", s.upto)
            put("content", s.content); put("ts", s.ts)
        }
        return writableDatabase.insert("summary", null, cv)
    }

    fun latestSummary(pid: String, level: Int): SummaryRow? {
        readableDatabase.rawQuery(
            "SELECT id,pid,level,upto,content,ts FROM summary WHERE pid=? AND level=? ORDER BY id DESC LIMIT 1",
            arrayOf(pid, level.toString()),
        ).use { c ->
            if (c.moveToFirst()) return SummaryRow(
                c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getString(4), c.getLong(5)
            )
        }
        return null
    }

    fun allSummaries(pid: String): List<SummaryRow> {
        val out = ArrayList<SummaryRow>()
        readableDatabase.rawQuery(
            "SELECT id,pid,level,upto,content,ts FROM summary WHERE pid=? ORDER BY level ASC, id ASC",
            arrayOf(pid),
        ).use { c ->
            while (c.moveToNext()) out.add(
                SummaryRow(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3), c.getString(4), c.getLong(5))
            )
        }
        return out
    }

    // ---------------- persona ----------------
    fun savePersona(p: Persona) {
        if (p.id.isBlank()) return
        val json = p.toJson()
        val old = persona(p.id)
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO persona(id,name,json,updated) VALUES(?,?,?,?)",
            arrayOf(p.id, p.name, json, System.currentTimeMillis()),
        )
        // 只有"人格内容"真的变了才追加版本。
        // 绑世界观、换声音、改 source 这些属于配置，不该刷成一排历史版本。
        if (old == null) {
            appendRev(p, "first")
        } else if (contentKey(old) != contentKey(p)) {
            appendRev(p, "edit")
        }
    }

    /**
     * 人格内容指纹：参与比较的只有"她是谁"这一层。
     * worldId / source / voiceSamples 不算——那些是外挂配置。
     */
    private fun contentKey(p: Persona): String = listOf(
        p.name, p.role, p.callYou, p.age, p.summary, p.personality, p.speech,
        p.catchphrase.joinToString("\u0001"), p.taboo.joinToString("\u0001"),
        p.scenario, p.firstMes, p.exampleDialog, p.proactiveStyle,
        p.sayPrefix, p.saySuffix, p.birthday, p.shadow, p.aspire, p.evolveNote,
        p.lore.joinToString("\u0002") { it.keys.joinToString(",") + "=" + it.content },
    ).joinToString("\u0003")

    private fun appendRev(p: Persona, note: String) {
        val next = (personaRevs(p.id).maxOfOrNull { it.rev } ?: 0) + 1
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO persona_rev(pid,rev,name,json,ts,note) VALUES(?,?,?,?,?,?)",
            arrayOf(p.id, next.toString(), p.name, p.toJson(), System.currentTimeMillis().toString(), note),
        )
    }

    /** 版本列表，新的在前 */
    fun personaRevs(pid: String): List<PersonaRev> {
        val out = ArrayList<PersonaRev>()
        readableDatabase.rawQuery(
            "SELECT pid,rev,name,json,ts,note FROM persona_rev WHERE pid=? ORDER BY rev DESC",
            arrayOf(pid),
        ).use { c ->
            while (c.moveToNext()) out.add(
                PersonaRev(c.getString(0), c.getInt(1), c.getString(2), c.getString(3),
                    c.getLong(4), c.getString(5) ?: "edit")
            )
        }
        return out
    }

    /** 回滚：把某个历史版本重新发布一次（产生新版本号，历史不动） */
    fun restorePersona(pid: String, rev: Int): Persona? {
        val json = readableDatabase.rawQuery(
            "SELECT json FROM persona_rev WHERE pid=? AND rev=?", arrayOf(pid, rev.toString()),
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        val p = Persona.fromJson(json).copy(id = pid)
        if (p.name.isBlank()) return null
        savePersona(p)
        appendRev(p, "restore r$rev")
        return p
    }

    /** 一条 SQL 读所有人格 + 最后一条消息 + 消息数，避免 reload 里逐个人格查询 */
    fun personaRows(): List<PersonaRow> {
        val out = ArrayList<PersonaRow>()
        readableDatabase.rawQuery(
            """SELECT p.id, p.json,
               (SELECT COALESCE(NULLIF(TRIM(content),''), '[图片]') FROM msg WHERE pid=p.id ORDER BY id DESC LIMIT 1) as lt,
               (SELECT ts FROM msg WHERE pid=p.id ORDER BY id DESC LIMIT 1) as lts,
               (SELECT COUNT(*) FROM msg WHERE pid=p.id) as cnt
               FROM persona p""",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val parsed = Persona.fromJson(c.getString(1))
                val pp = if (parsed.id.isBlank()) parsed.copy(id = c.getString(0)) else parsed
                val lt = c.getString(2)?.replace("\n", " ") ?: pp.firstMes.replace("\n", " ")
                out.add(PersonaRow(pp, lt, c.getLong(3), c.getInt(4)))
            }
        }
        return out.sortedWith(compareByDescending<PersonaRow> { it.lastTs }.thenByDescending { it.persona.name })
    }

    fun personas(): List<Persona> {
        val out = ArrayList<Persona>()
        readableDatabase.rawQuery("SELECT id,json FROM persona ORDER BY updated DESC", null).use { c ->
            while (c.moveToNext()) {
                val rowId = c.getString(0)
                val parsed = Persona.fromJson(c.getString(1))
                // JSON 坏了也要保留行 id，否则这个人格会以空 id 混进列表 → 删它会误清 kv
                out.add(if (parsed.id.isBlank()) parsed.copy(id = rowId) else parsed)
            }
        }
        return out
    }

    fun persona(id: String): Persona? {
        readableDatabase.rawQuery("SELECT id,json FROM persona WHERE id=?", arrayOf(id)).use { c ->
            if (c.moveToFirst()) {
                val parsed = Persona.fromJson(c.getString(1))
                return if (parsed.id.isBlank()) parsed.copy(id = c.getString(0)) else parsed
            }
        }
        return null
    }

    fun deletePersona(id: String) = writableDatabase.execSQL("DELETE FROM persona WHERE id=?", arrayOf(id))

    /** 删人格 = 连她的对话、事实、回忆、摘要、声音档案一起删，不留幽灵数据 */
    /**
     * 删人格 = 连她的对话、事实、回忆、摘要、声音档案、状态键一起删。
     * ⚠️ 之前用 `k LIKE '%_$id'`：`_` 是单字符通配符，id 为空时会命中所有 key（把整张 kv 清空），
     * 而 `pro_count_<pid>_<日期>` 又永远匹配不到。改成显式前缀 + GLOB（GLOB 里 `_` 是字面量）。
     */
    fun deletePersonaAll(id: String) {
        if (id.isBlank()) return
        val w = writableDatabase
        w.beginTransaction()
        try {
            w.execSQL("DELETE FROM persona WHERE id=?", arrayOf(id))
            w.execSQL("DELETE FROM msg WHERE pid=? OR pid=?||'#chat'", arrayOf(id, id))
            w.execSQL("DELETE FROM fact WHERE pid=?", arrayOf(id))
            w.execSQL("DELETE FROM episode WHERE pid=?", arrayOf(id))
            w.execSQL("DELETE FROM summary WHERE pid=?", arrayOf(id))
            w.execSQL("DELETE FROM diary WHERE pid=?", arrayOf(id))
            w.execSQL("DELETE FROM life WHERE pid=?", arrayOf(id))
            val prefixes = listOf(
                "last_user_ts_", "last_assistant_ts_", "lastfact_", "pro_streak_",
                "pro_silence_until_", "last_proactive_ts_", "voice_", "ticking_", "sumto_",
                "first_chat_ts_", "mood_", "moodnote_", "mood_hist_", "conflict_", "diary_", "life_",
            )
            prefixes.forEach { w.execSQL("DELETE FROM kv WHERE k = ? || ?", arrayOf(it, id)) }
            w.execSQL("DELETE FROM kv WHERE k GLOB ?", arrayOf("pro_count_${id}_*"))
            w.execSQL("DELETE FROM kv WHERE k = ?", arrayOf("provider_$id"))
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // ---------------- provider ----------------
    fun saveProvider(p: Provider) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO provider(id,name,base,key,chat_model,cheap_model,embed_model,vision_model) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(p.id, p.name, p.base, p.key, p.chatModel, p.cheapModel, p.embedModel, p.visionModel),
        )
    }

    fun providers(): List<Provider> {
        val out = ArrayList<Provider>()
        readableDatabase.rawQuery("SELECT id,name,base,key,chat_model,cheap_model,embed_model,vision_model FROM provider", null).use { c ->
            while (c.moveToNext()) out.add(
                Provider(c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7) ?: "")
            )
        }
        return out
    }

    fun provider(id: String): Provider? = providers().firstOrNull { it.id == id }

    fun deleteProvider(id: String) = writableDatabase.execSQL("DELETE FROM provider WHERE id=?", arrayOf(id))

    fun logProactive(score: Double, sent: Boolean, trigger: String) {
        writableDatabase.execSQL(
            "INSERT INTO proactive(ts,score,sent,trigger,replied) VALUES(?,?,?,?,0)",
            arrayOf(System.currentTimeMillis(), score, if (sent) 1 else 0, trigger),
        )
    }

    private fun toBlob(f: FloatArray): ByteArray {
        val b = ByteBuffer.allocate(f.size * 4)
        f.forEach { b.putFloat(it) }
        return b.array()
    }

    private fun fromBlob(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b)
        val n = b.size / 4
        return FloatArray(n) { bb.getFloat() }
    }

    fun chatStats(pid: String): ChatStats {
        var total = 0; var u = 0; var a = 0; var days = 0; var lastTs = 0L
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN role='user' THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN role='assistant' THEN 1 ELSE 0 END), " +
                "COUNT(DISTINCT strftime('%Y-%m-%d', ts/1000,'unixepoch','localtime')), MAX(ts) " +
                "FROM msg WHERE pid=?",
            arrayOf(pid),
        ).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0); u = c.getInt(1); a = c.getInt(2); days = c.getInt(3); lastTs = c.getLong(4)
            }
        }
        var hotDate = ""; var hotCount = 0
        readableDatabase.rawQuery(
            "SELECT strftime('%Y-%m-%d', ts/1000,'unixepoch','localtime') d, COUNT(*) c FROM msg WHERE pid=? GROUP BY d ORDER BY c DESC LIMIT 1",
            arrayOf(pid),
        ).use { c ->
            if (c.moveToFirst()) { hotDate = c.getString(0); hotCount = c.getInt(1) }
        }
        return ChatStats(total, u, a, days, lastTs, hotDate, hotCount)
    }

    /** 全量备份：所有表序列化成一个 JSON 大文件 */
    fun exportAll(): String {
        val o = JSONObject()
        o.put("app", "zhiyi"); o.put("version", 1); o.put("exportedAt", System.currentTimeMillis())

        fun table(sql: String, mapper: (android.database.Cursor) -> JSONObject): JSONArray {
            val arr = JSONArray()
            readableDatabase.rawQuery(sql, null).use { c -> while (c.moveToNext()) arr.put(mapper(c)) }
            return arr
        }
        o.put("personas", table("SELECT id,name,json,updated FROM persona") { c ->
            JSONObject().put("id", c.getString(0)).put("name", c.getString(1)).put("json", c.getString(2)).put("updated", c.getLong(3))
        })
        o.put("worlds", table("SELECT id,name,desc,updated,json FROM world") { c ->
            JSONObject().put("id", c.getString(0)).put("name", c.getString(1)).put("desc", c.getString(2)).put("updated", c.getLong(3)).put("json", c.getString(4))
        })
        o.put("providers", table("SELECT id,name,base,key,chat_model,cheap_model,embed_model,vision_model FROM provider") { c ->
            JSONObject().put("id", c.getString(0)).put("name", c.getString(1)).put("base", c.getString(2)).put("key", c.getString(3)).put("chat", c.getString(4)).put("cheap", c.getString(5)).put("embed", c.getString(6))
        })
        o.put("messages", table("SELECT id,pid,role,content,ts,tokens,summarized,images FROM msg") { c ->
            JSONObject().put("id", c.getLong(0)).put("pid", c.getString(1)).put("role", c.getString(2)).put("content", c.getString(3)).put("ts", c.getLong(4)).put("tokens", c.getInt(5)).put("sum", c.getInt(6)).put("images", c.getString(7))
        })
        o.put("facts", table("SELECT id,pid,subject,predicate,obj,importance,created,last_hit,hits,active,src FROM fact") { c ->
            JSONObject().put("id", c.getLong(0)).put("pid", c.getString(1)).put("subject", c.getString(2)).put("predicate", c.getString(3)).put("obj", c.getString(4)).put("importance", c.getDouble(5)).put("created", c.getLong(6)).put("last_hit", c.getLong(7)).put("hits", c.getInt(8)).put("active", c.getInt(9)).put("src", c.getString(10))
        })
        o.put("episodes", table("SELECT id,pid,title,summary,importance,ts,last_hit,vec,src FROM episode") { c ->
            val vec = c.getBlob(7)?.let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) } ?: ""
            JSONObject().put("id", c.getLong(0)).put("pid", c.getString(1)).put("title", c.getString(2)).put("summary", c.getString(3)).put("importance", c.getDouble(4)).put("ts", c.getLong(5)).put("last_hit", c.getLong(6)).put("vec", vec).put("src", c.getString(8))
        })
        o.put("summaries", table("SELECT id,pid,level,upto,content,ts FROM summary") { c ->
            JSONObject().put("id", c.getLong(0)).put("pid", c.getString(1)).put("level", c.getInt(2)).put("upto", c.getLong(3)).put("content", c.getString(4)).put("ts", c.getLong(5))
        })
        o.put("diaries", table("SELECT id,pid,date,content,ts FROM diary") { c ->
            JSONObject().put("id", c.getLong(0)).put("pid", c.getString(1)).put("date", c.getString(2)).put("content", c.getString(3)).put("ts", c.getLong(4))
        })
        o.put("life", table("SELECT id,pid,content,ts FROM life") { c ->
            JSONObject().put("id", c.getLong(0)).put("pid", c.getString(1)).put("content", c.getString(2)).put("ts", c.getLong(3))
        })
        val kv = JSONObject()
        readableDatabase.rawQuery("SELECT k,v FROM kv", null).use { c -> while (c.moveToNext()) kv.put(c.getString(0), c.getString(1)) }
        o.put("kv", kv)
        return o.toString()
    }

    /** 全量恢复：清空所有表，从备份 JSON 恢复。返回恢复的消息条数 */
    fun importAll(json: String): Int {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return -1
        // 结构校验：不是知忆备份就绝不碰现有数据（否则随便一个 JSON 都会清库，含 API Key）
        if (o.optString("app") != "zhiyi") return -1
        if (o.optJSONArray("personas") == null && o.optJSONArray("messages") == null) return -1
        val w = writableDatabase
        w.beginTransaction()
        try {
            listOf("msg", "fact", "episode", "summary", "diary", "life", "persona", "world", "provider", "kv")
                .forEach { w.execSQL("DELETE FROM $it") }

            o.optJSONArray("personas")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO persona(id,name,json,updated) VALUES(?,?,?,?)",
                        arrayOf(x.getString("id"), x.getString("name"), x.getString("json"), x.getLong("updated")))
                }
            }
            o.optJSONArray("worlds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO world(id,name,desc,updated,json) VALUES(?,?,?,?,?)",
                        arrayOf(x.getString("id"), x.getString("name"), x.getString("desc"), x.getLong("updated"), x.getString("json")))
                }
            }
            o.optJSONArray("providers")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO provider(id,name,base,key,chat_model,cheap_model,embed_model,vision_model) VALUES(?,?,?,?,?,?,?,?)",
                        arrayOf(x.getString("id"), x.getString("name"), x.getString("base"), x.getString("key"), x.getString("chat"), x.getString("cheap"), x.getString("embed"), x.optString("vision", "")))
                }
            }
            o.optJSONArray("messages")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO msg(id,pid,role,content,ts,tokens,summarized,images) VALUES(?,?,?,?,?,?,?,?)",
                        arrayOf(x.getLong("id"), x.getString("pid"), x.getString("role"), x.getString("content"), x.getLong("ts"), x.getInt("tokens"), x.getInt("sum"), x.getString("images")))
                }
            }
            o.optJSONArray("facts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO fact(id,pid,subject,predicate,obj,importance,created,last_hit,hits,active,src) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf(x.getLong("id"), x.getString("pid"), x.getString("subject"), x.getString("predicate"), x.getString("obj"), x.getDouble("importance"), x.getLong("created"), x.getLong("last_hit"), x.getInt("hits"), x.getInt("active"), x.getString("src")))
                }
            }
            o.optJSONArray("episodes")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    val vecB = x.getString("vec").takeIf { it.isNotBlank() }?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
                    w.execSQL("INSERT INTO episode(id,pid,title,summary,importance,ts,last_hit,vec,src) VALUES(?,?,?,?,?,?,?,?,?)",
                        arrayOf(x.getLong("id"), x.getString("pid"), x.getString("title"), x.getString("summary"), x.getDouble("importance"), x.getLong("ts"), x.getLong("last_hit"), vecB, x.getString("src")))
                }
            }
            o.optJSONArray("summaries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO summary(id,pid,level,upto,content,ts) VALUES(?,?,?,?,?,?)",
                        arrayOf(x.getLong("id"), x.getString("pid"), x.getInt("level"), x.getLong("upto"), x.getString("content"), x.getLong("ts")))
                }
            }
            o.optJSONArray("diaries")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO diary(id,pid,date,content,ts) VALUES(?,?,?,?,?)",
                        arrayOf(x.getLong("id"), x.getString("pid"), x.getString("date"), x.getString("content"), x.getLong("ts")))
                }
            }
            o.optJSONArray("life")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val x = arr.getJSONObject(i)
                    w.execSQL("INSERT INTO life(id,pid,content,ts) VALUES(?,?,?,?)",
                        arrayOf(x.getLong("id"), x.getString("pid"), x.getString("content"), x.getLong("ts")))
                }
            }
            o.optJSONObject("kv")?.let { kv ->
                val keys = kv.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    w.execSQL("INSERT OR REPLACE INTO kv(k,v) VALUES(?,?)", arrayOf(k, kv.getString(k)))
                }
            }
            w.setTransactionSuccessful()
            return o.optJSONArray("messages")?.length() ?: 0
        } finally {
            w.endTransaction()
        }
    }
}
