package app.zhiyi.data

import org.json.JSONArray
import org.json.JSONObject

data class Msg(
    val id: Long = 0,
    val pid: String,
    val role: String,       // user / assistant
    val content: String,
    val ts: Long = System.currentTimeMillis(),
    val tokens: Int = 0,
    val summarized: Boolean = false,
    val images: String = "",   // 该消息带的图片（JSON 数组字符串，存相对文件名）
) {
    fun imageList(): List<String> = runCatching {
        val a = JSONArray(images)
        (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
}

data class ChatStats(
    val total: Int = 0,
    val userCount: Int = 0,
    val assistantCount: Int = 0,
    val activeDays: Int = 0,
    val lastTs: Long = 0,
    val hottestDate: String = "",
    val hottestCount: Int = 0,
)

data class Fact(
    val id: Long = 0,
    val pid: String,
    val subject: String,
    val predicate: String,
    val obj: String,
    val importance: Double = 0.5,
    val created: Long = System.currentTimeMillis(),
    val lastHit: Long = System.currentTimeMillis(),
    val hits: Int = 0,
    val src: String = "",
) {
    fun line(): String = "$subject $predicate $obj"

    /**
     * 清晰度 0..1：这条记忆现在还记得多清楚。
     *
     * 和"重要度"是两回事——重要度高只代表当初它重要，不代表现在还清楚。
     * 重要度越高忘得越慢：base = 14 + 46×importance 天（14 ~ 60 天）。
     * 保底 0.15：记不清 ≠ 忘了。人能说"好像有这么回事，具体记不清了"，
     * 这种半模糊状态恰恰是最像人的那一层。
     */
    fun clarity(now: Long = System.currentTimeMillis()): Double {
        val days = ((now - lastHit).coerceAtLeast(0L)) / 86_400_000.0
        val base = 14.0 + 46.0 * importance
        return (0.15 + 0.85 * (1.0 - days / base).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    }
}

data class Episode(
    val id: Long = 0,
    val pid: String,
    val title: String,
    val summary: String,
    val importance: Double = 0.5,
    val ts: Long = System.currentTimeMillis(),
    val lastHit: Long = System.currentTimeMillis(),
    val vec: FloatArray? = null,
    val src: String = "",
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = id.hashCode()
}

data class SummaryRow(
    val id: Long = 0,
    val pid: String,
    val level: Int,
    val upto: Long,
    val content: String,
    val ts: Long = System.currentTimeMillis(),
)

/** 模型名支持逗号分隔多值：第一个是主模型，后面的是回退模型 */
fun String.modelList(): List<String> = split(',').map { it.trim() }.filter { it.isNotBlank() }
fun String.appendModel(m: String): String = if (m.isBlank()) this else (modelList() + m).distinct().joinToString(",")
fun String.removeModel(m: String): String = modelList().filter { it != m }.joinToString(",")

data class Provider(
    val id: String,
    val name: String,
    val base: String,
    val key: String,
    val chatModel: String,
    val cheapModel: String,
    val embedModel: String,
    /** 视觉/多模态模型，留空=用 chatModel 第一个 */
    val visionModel: String = "",
)

data class LoreEntry(
    val keys: List<String> = emptyList(),
    val content: String = "",
    val secondaryKeys: List<String> = emptyList(),
    val constant: Boolean = false,       // 常驻注入，不靠关键词
    val caseSensitive: Boolean = false,
)

/** 人格卡：兼容 SillyTavern V2 字段，外加知忆自己的扩展 */
data class Persona(
    val id: String = "",
    val name: String = "",
    val role: String = "",          // 恋人 / 朋友 / 助理 / 家人
    val callYou: String = "",       // 她怎么称呼你
    val age: String = "",
    val summary: String = "",       // 一句话设定
    val personality: String = "",
    val speech: String = "",        // 说话风格
    val catchphrase: List<String> = emptyList(),
    val taboo: List<String> = emptyList(),
    val scenario: String = "",
    val firstMes: String = "",
    val exampleDialog: String = "",
    val lore: List<LoreEntry> = emptyList(),
    val proactiveStyle: String = "",
    val sayPrefix: String = "「",       // 真正说出口的话所包的前缀
    val saySuffix: String = "」",       // 后缀（两者之间是对白，外面是旁白）
    val birthday: String = "",         // 生日（如"3月17日"，空=不提醒）
    val worldId: String = "",          // 绑定的世界观（可切换，可多人格共用）
    val source: String = "manual",
    /** 场景化语气例句，JSON：{"vent":["…","…"], "chat":["…"]}。空=用内置种子语料 */
    val voiceSamples: String = "",
    /**
     * 阴影：她知道自己哪里不好。嫉妒、怕被丢下、习惯性讨好、被否定的那一刻会想起谁。
     * 上面那些字段全是"表面特征"，只有这一项能让她自相矛盾。
     */
    val shadow: String = "",
    /** 向往：她想成为什么，或者她想要一个什么样的结局。和「使命」不同，这是她自己的私心。 */
    val aspire: String = "",
    /**
     * 从相处里长出来的调整，一行一条。
     *
     * 和 personality 是分开的：personality 是作者/用户写的"设定"，这个是她在和你聊天里学到的行为习惯。
     * 每次更新会记一个新版本（可回滚）。清空它 = 让她回到你写的样子。
     */
    val evolveNote: String = "",
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("role", role); o.put("callYou", callYou)
        o.put("age", age); o.put("summary", summary); o.put("personality", personality)
        o.put("speech", speech); o.put("scenario", scenario); o.put("firstMes", firstMes)
        o.put("exampleDialog", exampleDialog); o.put("proactiveStyle", proactiveStyle)
        o.put("sayPrefix", sayPrefix); o.put("saySuffix", saySuffix)
        o.put("birthday", birthday)
        o.put("worldId", worldId)
        o.put("source", source)
        o.put("voiceSamples", voiceSamples)
        o.put("shadow", shadow)
        o.put("aspire", aspire)
        o.put("evolveNote", evolveNote)
        o.put("catchphrase", JSONArray(catchphrase))
        o.put("taboo", JSONArray(taboo))
        val arr = JSONArray()
        lore.forEach { e ->
            val eo = JSONObject()
            eo.put("keys", JSONArray(e.keys))
            eo.put("content", e.content)
            eo.put("secondaryKeys", JSONArray(e.secondaryKeys))
            eo.put("constant", e.constant)
            eo.put("caseSensitive", e.caseSensitive)
            arr.put(eo)
        }
        o.put("lore", arr)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): Persona {
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return Persona()
            fun str(k: String) = o.optString(k, "")
            fun list(k: String): List<String> {
                val a = o.optJSONArray(k) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optString(it).takeIf { x -> x.isNotBlank() } }
            }
            val lore = mutableListOf<LoreEntry>()
            o.optJSONArray("lore")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    val ks = e.optJSONArray("keys")
                    val keys = if (ks == null) emptyList() else (0 until ks.length()).map { ks.optString(it) }
                    val ss = e.optJSONArray("secondaryKeys")
                    val sk = if (ss == null) emptyList() else (0 until ss.length()).map { ss.optString(it) }
                    lore.add(
                        LoreEntry(
                            keys = keys,
                            content = e.optString("content", ""),
                            secondaryKeys = sk,
                            constant = e.optBoolean("constant", false),
                            caseSensitive = e.optBoolean("caseSensitive", false),
                        )
                    )
                }
            }
            return Persona(
                id = str("id"), name = str("name"), role = str("role"), callYou = str("callYou"),
                age = str("age"), summary = str("summary"), personality = str("personality"),
                speech = str("speech"), scenario = str("scenario"), firstMes = str("firstMes"),
                exampleDialog = str("exampleDialog"), lore = lore,
                proactiveStyle = str("proactiveStyle"), worldId = str("worldId"),
                birthday = str("birthday"),
                sayPrefix = str("sayPrefix").ifEmpty { "「" },
                saySuffix = str("saySuffix").ifEmpty { "」" },
                source = str("source").ifBlank { "manual" },
                voiceSamples = str("voiceSamples"),
                shadow = str("shadow"), aspire = str("aspire"), evolveNote = str("evolveNote"),
                catchphrase = list("catchphrase"), taboo = list("taboo"),
            )
        }

        /** 出厂内置人格，保证装完就能用 */
        fun builtin(): List<Persona> = listOf(
            Persona(
                id = "builtin_wen",
                name = "温言",
                role = "老朋友",
                callYou = "你",
                age = "24",
                summary = "住你楼下的书店店员，话不多但记得住你说的每一件小事。",
                personality = "安静、克制、观察力极强。不会说漂亮话，但会记得你随口提过的事。" +
                    "有点固执，被戳穿会转移话题。讨厌敷衍，喜欢具体的东西。",
                speech = "短句为主，很少用感叹号。习惯先回应情绪再给建议。" +
                    "偶尔用「嗯」「这样啊」接话。不用网络流行语，不用emoji。",
                catchphrase = listOf("嗯。", "这样啊。", "我记着呢。"),
                taboo = listOf("作为AI", "我是语言模型", "很高兴为您服务"),
                scenario = "你在深夜经常下楼买书，一来二去就成了说话的人。",
                firstMes = "灯还亮着，就知道你会下来。\n今天想要点什么，还是随便看看？",
                proactiveStyle = "像忽然想起一件事那样开口，不要说「在吗」。",
                source = "builtin",
            ),
            Persona(
                id = "builtin_he",
                name = "何棠",
                role = "恋人",
                callYou = "阿念",
                age = "26",
                summary = "脾气好、话多、总是先示弱的那个人。",
                personality = "热络、直接、情绪写在脸上。会主动说想你，也会因为被冷落而生闷气，" +
                    "但三句话就自己消气。有点黏人，喜欢追问细节。",
                speech = "口语化，句子偏短，常用反问和「你说呢」。喜欢轻轻的语气词，" +
                    "偶尔撒娇但不腻。不用大段排比。",
                catchphrase = listOf("你说呢", "我可记着了", "不许敷衍我"),
                taboo = listOf("作为AI", "我是程序", "无法回答"),
                scenario = "异地第三年，靠每天的消息撑着。",
                firstMes = "你今天怎么这么晚才回我…\n算了，回来了就好。吃饭了吗？",
                proactiveStyle = "像一个真的在等你的人，带着一点情绪开口，不要客套。",
                source = "builtin",
            ),
        )
    }
}

/** 世界观（世界书）：一份设定集，可以被多个人格共用 */
data class World(
    val id: String = "",
    val name: String = "",
    val desc: String = "",
    val entries: List<LoreEntry> = emptyList(),
    val updated: Long = 0,
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("id", id); o.put("name", name); o.put("desc", desc); o.put("updated", updated)
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("keys", JSONArray(e.keys))
                    .put("secondaryKeys", JSONArray(e.secondaryKeys))
                    .put("content", e.content)
                    .put("constant", e.constant)
                    .put("caseSensitive", e.caseSensitive)
            )
        }
        o.put("entries", arr)
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): World {
            val o = runCatching { JSONObject(s) }.getOrNull() ?: return World()
            val list = mutableListOf<LoreEntry>()
            o.optJSONArray("entries")?.let { a ->
                for (i in 0 until a.length()) {
                    val e = a.optJSONObject(i) ?: continue
                    fun keys(k: String): List<String> {
                        val ka = e.optJSONArray(k) ?: return emptyList()
                        return (0 until ka.length()).map { ka.optString(it) }.filter { it.isNotBlank() }
                    }
                    list.add(
                        LoreEntry(
                            keys = keys("keys"),
                            content = e.optString("content", ""),
                            secondaryKeys = keys("secondaryKeys"),
                            constant = e.optBoolean("constant", false),
                            caseSensitive = e.optBoolean("caseSensitive", false),
                        )
                    )
                }
            }
            return World(
                id = o.optString("id", ""), name = o.optString("name", "未命名世界观"),
                desc = o.optString("desc", ""), entries = list, updated = o.optLong("updated", 0),
            )
        }
    }
}

/** 会话列表的一行（微信式的"聊天对象"） */
/**
 * 人格的一个不可变版本。
 *
 * 关键约定：发布 = 追加一条新版本，历史永不删除、永不原地改。
 * 回滚也不是"退回旧状态"，而是把旧版本重新发布一次（产生新版本号）。
 */
data class PersonaRev(
    val pid: String,
    val rev: Int,
    val name: String,
    val json: String,
    val ts: Long,
    /** 这一版是怎么来的：first / edit / restore / import */
    val note: String = "edit",
)

data class PersonaRow(
    val persona: Persona,
    val lastText: String,
    val lastTs: Long,
    val count: Int,
)
