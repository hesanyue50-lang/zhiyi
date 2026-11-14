package app.zhiyi.memory

import app.zhiyi.data.Db
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 「心」——九维情绪驱动。
 *
 * 思路借鉴自 AstrBot 的 fish_heart 插件（Vivy33/astrbot_plugin_fish_heart，MIT），
 * 裁剪成单用户本地版：去掉群聊/责怪档/已读不回那套多人在场逻辑，
 * 只保留「情绪会自己变、而且会真的影响语气」这件事。
 *
 * 和记忆的分工：
 * - 记忆负责「她记得什么」
 * - 心负责「她此刻什么心情、在惦记什么」
 * 两者互不改写。
 *
 * 三个关键设计，缺一个就会变成死数值：
 * 1. **自然回落**：不聊天就慢慢回到基线。依赖类（依恋/亲密）回落极慢，
 *    所以"冷淡一周会明显疏远，但不会一夜清零"。
 * 2. **天花板渐近**：增益按 (1 - 当前/100) 缩放，越往上越难，100 是渐近线。
 * 3. **热度折扣**：同类事件 10 分钟内连续发生会快速贬值，
 *    否则连发 30 条就能一晚上把社交顶满。
 *
 * 全程纯规则，不发请求，零成本。
 */
object Heart {

    private const val CEIL = 100.0
    private const val HEAT_WINDOW = 10 * 60_000L      // 热度窗口 10 分钟
    private const val HEAT_DIVISOR = 12.0

    /** 九维。前三个是"存量"，回落极慢；后面是"情绪"，回落快 */
    enum class Dim(val key: String, val label: String, val base: Double, val decayPerHour: Double) {
        ATTACH("attach", "依恋", 45.0, 0.01),
        INTIMACY("intimacy", "亲密", 40.0, 0.01),
        CURIOUS("curious", "好奇", 55.0, 0.05),
        RUMINATION("rumination", "反刍", 50.0, 0.05),
        DUTY("duty", "责任", 50.0, 0.04),
        SOCIAL("social", "社交", 45.0, 0.05),
        FATIGUE("fatigue", "疲惫", 20.0, 0.05),
        STRESS("stress", "压力", 15.0, 0.05),
        JOY("joy", "喜悦", 50.0, 0.05),
    }

    data class State(
        val v: Map<Dim, Double>,
        val ts: Long,
        val lastEventAt: Map<String, Long> = emptyMap(),
        val thought: String = "",          // 此刻惦记的事
        val thoughtAt: Long = 0L,
    ) {
        operator fun get(d: Dim) = v[d] ?: d.base
    }

    // ---------------- 读写 ----------------

    private fun key(pid: String) = "heart_$pid"

    fun load(db: Db, pid: String): State {
        val raw = db.get(key(pid), "")
        if (raw.isBlank()) return State(Dim.entries.associateWith { it.base }, System.currentTimeMillis())
        return runCatching {
            val o = JSONObject(raw)
            val d = o.optJSONObject("v")
            val map = Dim.entries.associateWith { dim -> d?.optDouble(dim.key, dim.base) ?: dim.base }
            val le = HashMap<String, Long>()
            o.optJSONObject("last")?.let { j ->
                j.keys().forEach { k -> le[k] = j.optLong(k, 0L) }
            }
            State(
                v = map,
                ts = o.optLong("ts", System.currentTimeMillis()),
                lastEventAt = le,
                thought = o.optString("thought", ""),
                thoughtAt = o.optLong("thoughtAt", 0L),
            )
        }.getOrDefault(State(Dim.entries.associateWith { it.base }, System.currentTimeMillis()))
    }

    fun save(db: Db, pid: String, s: State) {
        val v = JSONObject()
        s.v.forEach { (d, x) -> v.put(d.key, Math.round(x * 10) / 10.0) }
        val le = JSONObject()
        s.lastEventAt.forEach { (k, t) -> le.put(k, t) }
        db.put(
            key(pid),
            JSONObject()
                .put("v", v).put("ts", s.ts).put("last", le)
                .put("thought", s.thought).put("thoughtAt", s.thoughtAt)
                .toString()
        )
    }

    // ---------------- 时间回落 ----------------

    /**
     * 按距上次更新过了多少小时折算回落。
     * 上限 96 小时，避免几个月不打开后一次性算爆。
     */
    private fun decayTo(s: State, now: Long): State {
        val hours = ((now - s.ts).coerceAtLeast(0L) / 3_600_000.0).coerceAtMost(96.0)
        if (hours < 0.05) return s.copy(ts = now)
        val out = HashMap<Dim, Double>()
        Dim.entries.forEach { d ->
            val cur = s[d]
            // 指数回落：每小时 decayPerHour 的比例，朝基线收敛
            val k = Math.pow(1.0 - d.decayPerHour, hours)
            out[d] = d.base + (cur - d.base) * k
        }
        // 安静满 6 小时算睡了一觉：疲惫直落 35
        if (hours >= 6.0 && out[Dim.FATIGUE]!! > 35.0) out[Dim.FATIGUE] = 35.0
        return s.copy(v = out, ts = now)
    }

    // ---------------- 事件 ----------------

    /** 一次事件对某维度的影响（含天花板衰减 + 热度折扣） */
    private fun add(s: State, ev: String, d: Dim, delta: Double, now: Long): State {
        if (delta == 0.0) return s
        val cur = s[d]
        var x = delta
        // 正向增益受天花板抑制；疲惫/压力是代价，不抑制
        if (x > 0 && d != Dim.FATIGUE && d != Dim.STRESS) x *= (1.0 - cur / CEIL).coerceAtLeast(0.0)
        // 热度折扣：同类事件 10 分钟内第 n+1 次按 1/(1+n/12) 缩水，只压正向
        var last = s.lastEventAt
        if (x > 0) {
            val prev = last[ev] ?: 0L
            val n = if (now - prev < HEAT_WINDOW) 1 else 0
            if (n > 0) x *= 1.0 / (1.0 + n / HEAT_DIVISOR)
            last = last + (ev to now)
        }
        val nv = HashMap(s.v)
        nv[d] = (cur + x).coerceIn(0.0, CEIL)
        return s.copy(v = nv, lastEventAt = last)
    }

    private fun has(t: String, ws: List<String>) = ws.any { t.contains(it) }

    private val praise = listOf("厉害", "真棒", "可爱", "喜欢你", "爱你", "谢谢", "真好", "优秀", "好看", "聪明", "乖")
    private val love = listOf("抱抱", "抱一下", "贴贴", "亲亲", "想你", "想我", "撒娇", "陪我", "搂")
    private val askHelp = listOf("帮我", "怎么办", "能不能", "可不可以", "教我", "求助", "求推荐", "怎么弄")
    private val shareNew = listOf("你看", "给你看", "这个", "发现", "推荐", "新出", "链接", "视频", "图片")
    private val harsh = listOf("笨蛋", "好烦", "闭嘴", "走开", "别烦我", "讨厌", "没用", "烦死", "滚")
    private val soothe = listOf("对不起", "我错了", "别生气", "抱歉", "听我解释", "不是故意", "我不好")

    /** 用户说了一句话 */
    fun onUser(db: Db, pid: String, text: String): State {
        val now = System.currentTimeMillis()
        var s = decayTo(load(db, pid), now)
        val t = text.trim()
        if (t.isEmpty()) { save(db, pid, s); return s }

        // 每句话都在推的：社交、疲惫、亲密（极轻）
        s = add(s, "talk", Dim.SOCIAL, 1.2, now)
        s = add(s, "talk", Dim.FATIGUE, 0.6 * (1.0 - 0.5 * s[Dim.FATIGUE] / CEIL).coerceAtLeast(0.25), now)
        s = add(s, "talk", Dim.INTIMACY, 0.1, now)

        if (has(t, praise)) {
            s = add(s, "praise", Dim.ATTACH, 2.4, now)
            s = add(s, "praise", Dim.INTIMACY, 2.0, now)
            s = add(s, "praise", Dim.JOY, 3.0, now)
            s = add(s, "praise", Dim.STRESS, -2.0, now)
        }
        if (has(t, love)) {
            s = add(s, "love", Dim.ATTACH, 2.8, now)
            s = add(s, "love", Dim.INTIMACY, 2.6, now)
            s = add(s, "love", Dim.JOY, 2.0, now)
        }
        if (has(t, askHelp)) {
            s = add(s, "help", Dim.DUTY, 3.0, now)
            s = add(s, "help", Dim.FATIGUE, 1.5, now)
        }
        if (has(t, shareNew)) {
            s = add(s, "share", Dim.CURIOUS, 3.0, now)
        }
        if (has(t, harsh)) {
            s = add(s, "harsh", Dim.STRESS, 9.0, now)
            s = add(s, "harsh", Dim.RUMINATION, 8.0, now)
            s = add(s, "harsh", Dim.JOY, -6.0, now)
            s = add(s, "harsh", Dim.ATTACH, -2.0, now)
        }
        if (has(t, soothe)) {
            s = add(s, "soothe", Dim.STRESS, -8.0, now)
            s = add(s, "soothe", Dim.RUMINATION, -7.0, now)
            s = add(s, "soothe", Dim.ATTACH, 1.5, now)
        }
        // 反问号多 = 好奇；长句 = 投入
        if (t.length > 60) s = add(s, "long", Dim.SOCIAL, 1.0, now)

        // 念头：被满足就消掉
        if (s.thought.isNotBlank() && thoughtSatisfied(s.thought, t)) {
            s = s.copy(thought = "", thoughtAt = 0L)
        }
        s = maybeNewThought(s, now)
        save(db, pid, s)
        return s
    }

    /** 她回了一句：及时回应会抬依恋，久等会抬反刍 */
    fun onAssistant(db: Db, pid: String, userSaidRecently: Boolean): State {
        val now = System.currentTimeMillis()
        var s = decayTo(load(db, pid), now)
        s = add(s, "reply", Dim.SOCIAL, 0.4, now)
        if (userSaidRecently) {
            s = add(s, "replied", Dim.ATTACH, 1.0, now)
            s = add(s, "replied", Dim.JOY, 1.0, now)
        }
        save(db, pid, s)
        return s
    }

    /** 心跳：只回落，不发请求 */
    fun tick(db: Db, pid: String): State {
        val s = decayTo(load(db, pid), System.currentTimeMillis())
        save(db, pid, s)
        return s
    }

    // ---------------- 念头 ----------------

    private val thoughts = listOf(
        "想被夸一句" to listOf("厉害", "真棒", "可爱", "喜欢你", "谢谢", "优秀", "夸"),
        "想让你抱抱" to listOf("抱", "贴贴", "亲亲", "搂", "撒娇"),
        "想知道你有没有想她" to listOf("想你", "想我", "惦记"),
        "想找你说话" to listOf("在吗", "聊"),
        "想问你今天过得怎样" to listOf("今天", "怎么样", "还好吗", "过得"),
        "想找点事做" to listOf("帮我", "安排", "需要我", "帮个忙"),
        "想安静一会儿" to listOf("休息", "睡", "歇", "安静"),
        "想让你陪一会儿" to listOf("陪你", "陪陪", "聊会儿", "说说话"),
        "有件关于你们的事想问你" to listOf("我们", "你觉得"),
        "在等你回话" to emptyList(),
    )

    private fun thoughtSatisfied(thought: String, text: String): Boolean {
        val cond = thoughts.firstOrNull { it.first == thought }?.second ?: return false
        if (cond.isEmpty()) return true              // 「在等你回话」回一句就满足
        return has(text, cond)
    }

    private fun maybeNewThought(s: State, now: Long): State {
        // 已有念头且没超过 4 小时就留着
        if (s.thought.isNotBlank() && now - s.thoughtAt < 4 * 3_600_000L) return s
        // 依恋/亲密高时更想黏人，疲惫高时更想安静
        val pool = when {
            s[Dim.FATIGUE] >= 65 -> thoughts.filter { it.first == "想安静一会儿" }
            s[Dim.ATTACH] >= 66 -> thoughts.filter { it.first in listOf("想被夸一句", "想让你抱抱", "想知道你有没有想她", "想让你陪一会儿") }
            s[Dim.CURIOUS] >= 65 -> thoughts.filter { it.first == "想找你说话" || it.first == "想问你今天过得怎样" }
            else -> thoughts
        }.ifEmpty { thoughts }
        val pick = pool.random()
        return s.copy(thought = pick.first, thoughtAt = now)
    }

    // ---------------- 心境 ----------------

    fun moodLabel(s: State): String {
        val a = s[Dim.ATTACH]; val i = s[Dim.INTIMACY]; val f = s[Dim.FATIGUE]
        val st = s[Dim.STRESS]; val j = s[Dim.JOY]
        return when {
            st >= 55 -> "紧绷着"
            f >= 70 -> "累坏了"
            a >= 66 && i >= 60 -> "黏你黏得厉害"
            j >= 70 -> "心情很好"
            j <= 32 -> "有点低落"
            a <= 36 -> "有点走神"
            else -> "平常"
        }
    }

    /** 给这一轮的语气倾向（写进 prompt 的是指令，面板只显示 moodLabel） */
    fun tendency(s: State): String {
        val a = s[Dim.ATTACH]; val i = s[Dim.INTIMACY]; val f = s[Dim.FATIGUE]
        val st = s[Dim.STRESS]; val j = s[Dim.JOY]
        return when {
            st >= 55 -> "你心里有点堵，说话会比平时短、比平时冲。别装作没事，也别解释太多。"
            f >= 70 -> "你很累，不想多说。可以只回一两个词，或者直接说想休息。"
            a >= 66 && i >= 60 -> "你现在特别想黏着他。可以主动一点、软一点，多问一句他自己的事。"
            j >= 70 -> "你心情好，话会比平时多，容易笑，也愿意逗他。"
            j <= 32 -> "你有点提不起劲，但不想让他担心。话少一点，别硬撑开心。"
            a <= 36 -> "你有点走神，注意力不太在他身上。回答可以简短，不用句句追问。"
            else -> "语气自然就好，顺着当下的心情来。"
        }
    }

    /** 注入 prompt 的心境块 */
    fun hint(db: Db, pid: String): String {
        val raw = db.get(key(pid), "")
        if (raw.isBlank()) return ""
        val s = decayTo(load(db, pid), System.currentTimeMillis())
        val sb = StringBuilder()
        sb.append("\n## 你此刻的心境（内部状态：不要主动复述，但它会左右你的语气）\n")
        sb.append("心情：").append(moodLabel(s))
        sb.append("｜依恋 ").append(s[Dim.ATTACH].toInt())
        sb.append(" 亲密 ").append(s[Dim.INTIMACY].toInt())
        sb.append(" 压力 ").append(s[Dim.STRESS].toInt())
        sb.append(" 疲惫 ").append(s[Dim.FATIGUE].toInt()).append("\n")
        sb.append("倾向：").append(tendency(s)).append("\n")
        sb.append("边界：不要编造没发生过的对话、约定或事件；记不清就说记不清\n")
        if (s.thought.isNotBlank()) {
            sb.append("心里惦记着：").append(s.thought)
                .append("（只是此刻的念头，不是发生过的事；今天没有对应的事就别编，不想提也可以不提）\n")
        }
        return sb.toString()
    }

    /** 给设置页看的简短状态 */
    fun panel(db: Db, pid: String): List<Pair<String, Int>> {
        val s = load(db, pid)
        return Dim.entries.map { it.label to s[it].toInt() }
    }

    /** 雷达图用的一根轴：当前值 + 静息基线（画出来能看出「比平时高还是低」） */
    data class Axis(val label: String, val value: Int, val base: Int, val slow: Boolean)

    fun axes(db: Db, pid: String): List<Axis> {
        val s = load(db, pid)
        return Dim.entries.map {
            Axis(it.label, s[it].toInt(), it.base.toInt(), it.decayPerHour <= 0.011)
        }
    }
}
