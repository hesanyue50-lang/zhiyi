package app.zhiyi.memory

/**
 * 一句话的「说话处境」。
 *
 * 分类原则：不按话题分（聊工作/聊生活），按**对方此刻需要什么**分。
 * 同一句话在不同处境下该有完全不同的回法——这是「不像人机」的关键。
 *
 * 每一类都必须能用规则判定，不额外调模型。
 */
enum class VoiceScene(val key: String, val label: String) {
    VENT("vent", "倒苦水"),      // 有情绪、要人站队，不要解决方案
    SEEK("seek", "撑不住了"),    // 情绪压过表达，话都说不利索
    SHARE("share", "分享喜悦"),  // 想被看见、被夸
    ASK("ask", "求助提问"),      // 要答案
    CHAT("chat", "闲聊搭话"),    // 要陪伴，不要内容
    TEASE("tease", "撒娇撩人"),  // 要亲密感
    TELL("tell", "告知事情"),    // 告知事实，等她记住
    COLD("cold", "敷衍冷淡"),    // 不想说，或者没力气说
}

object VoiceTone {

    // ---------------- 判定 ----------------

    private val ventWords = listOf(
        "烦死", "气死", "无语", "恶心", "离谱", "崩溃", "难受", "委屈", "憋屈",
        "受不了", "凭什么", "怎么会这样", "太过分", "神经病", "有病", "讨厌",
        "烦躁", "郁闷", "窝火", "下头", "社死", "尴尬死", "气人", "没用", "白费",
    )
    /** 强信号：基本可以确定她是在求助情绪 */
    private val seekStrong = listOf(
        "撑不住", "扛不住", "想哭", "哭了", "好累", "累死", "不想活",
        "熬不住", "顶不住", "做不到", "废了", "没意思", "空虚", "孤独",
    )
    /** 弱信号：要配合情绪词才算，否则「我一个人在家」只是陈述 */
    private val seekWeak = listOf("一个人", "没人", "空落落")
    private val shareWords = listOf(
        "我过", "我拿", "我赢", "我中", "我升", "我买", "我瘦", "我减",
        "成功", "终于", "第一次", "太棒", "开心", "激动", "完成了", "搞定了",
        "通过了", "拿到", "录取", "加薪", "升职", "考过", "考上了", "笑死", "哈哈哈", "牛啊",
    )
    private val askWords = listOf(
        "怎么", "为什么", "为啥", "是什么", "能不能", "可不可以", "教我",
        "怎么办", "怎样", "如何", "哪个", "有没有", "是不是", "好不好",
        "求助", "帮我", "问问", "求推荐", "什么区别",
    )
    private val chatWords = listOf(
        "在吗", "在么", "在不在", "干嘛", "在做什么", "忙吗", "睡了吗",
        "早安", "晚安", "午安", "吃了吗", "好久不见", "哈喽",
        "hello", "hi", "摸鱼", "无聊", "睡不着", "天气",
    )
    private val teaseWords = listOf(
        "抱抱", "亲亲", "想你", "想我", "喜欢我", "爱我", "陪我", "哄我",
        "摸摸", "贴贴", "么么", "宝宝", "老婆", "老公", "宝贝", "撒娇",
        "不理你", "不许", "陪我玩",
    )
    /** 事件类：命中就是 TELL，不需要别的条件 */
    private val tellEvent = listOf(
        "出差", "加班", "开会", "面试", "考试", "去医院", "回家", "到家",
        "回来", "下班", "上课", "出门", "出发", "到了", "在路上", "吃完了",
    )
    /** 时间类：单独命中不算 TELL（「今天天气不错」不是告知），要和具体事一起出现 */
    private val tellTime = listOf("明天", "后天", "下周", "下个月", "刚才", "等会", "待会", "今天")
    private val coldWords = listOf(
        "嗯", "哦", "噢", "呃", "额", "呵", "行", "好", "是", "对",
        "随便", "没事", "算了", "都行", "无所谓", "不知道", "不想说", "知道了", "收到",
    )

    private fun has(text: String, words: List<String>) = words.any { text.contains(it) }

    /**
     * 「像撒娇」而不是「在笑」。
     * 语气词密度或叠字密度高才算——但必须先把"哈哈/呵呵/嘿嘿"剔掉，
     * 否则「哈哈哈哈哈笑死我了」会被当成撒娇（实测踩过）。
     */
    private fun cute(text: String): Boolean {
        val t = text.replace(Regex("[哈呵嘿嘻]+"), "").replace(" ", "")
        val marks = listOf("嘛", "啦", "呀", "哦", "哼", "咧", "咯", "嗷", "唔", "诶")
        val n = marks.count { t.contains(it) }
        val dup = Regex("(.)\\1").findAll(t).count()
        return n >= 2 || (dup >= 2 && t.length < 12)
    }

    /**
     * 判定处境。顺序即优先级，改顺序等于改语义：
     * 敷衍 → 情绪 → 喜悦 → 撒娇 → 提问 → 事件 → 闲聊 → 时间 → 兜底。
     */
    fun detect(raw: String): VoiceScene {
        val t = raw.trim()
        if (t.isEmpty()) return VoiceScene.COLD
        val plain = t.replace(Regex("[\\p{Punct}\\s，。！？、…~～]"), "")
        // 去掉句尾语气词后再比，"随便吧""行了吧"都能落进 COLD
        val stem = plain.replace(Regex("(吧|啊|了|哦|喔|呗|嘛|的)+$"), "")

        if (plain in coldWords || stem in coldWords) return VoiceScene.COLD
        if (plain.length <= 2 && has(plain, coldWords)) return VoiceScene.COLD

        if (has(t, seekStrong)) return VoiceScene.SEEK
        if (has(t, seekWeak) && has(t, listOf("想", "难", "累", "烦", "空", "静", "哭", "闷")))
            return VoiceScene.SEEK
        if (has(t, ventWords)) return VoiceScene.VENT
        if (has(t, shareWords) && (t.contains("！") || t.contains("!") || t.contains("哈哈") || t.length <= 30))
            return VoiceScene.SHARE
        if (has(t, teaseWords) || cute(t)) return VoiceScene.TEASE
        if (has(t, askWords) || t.contains("？") || t.contains("?")) return VoiceScene.ASK
        if (has(t, tellEvent)) return VoiceScene.TELL
        // 闲聊优先于"时间词兜底"，否则「今天天气不错」会被判成告知
        if (has(t, chatWords)) return VoiceScene.CHAT
        if (has(t, tellTime) && plain.length > 4) return VoiceScene.TELL
        return if (plain.length <= 12) VoiceScene.CHAT else VoiceScene.TELL
    }

    // ---------------- 语气指令 ----------------
    // 写的是「怎么开口」，不是「要共情」这种正确的废话。

    fun hint(scene: VoiceScene): String = when (scene) {
        VoiceScene.VENT -> """
            【此刻】她在倒苦水，不是在征求解决方案。
            - 第一句必须接住**具体那件事**，不要泛泛说"辛苦了""别难过"
            - 不要给建议，除非她问"我该怎么办"
            - 可以替她骂两句、翻个白眼，站她这边
            - 禁用："我理解你""抱抱你""一切都会好的"
        """.trimIndent()

        VoiceScene.SEEK -> """
            【此刻】她快撑不住了，话都说不利索。这种时候说得越少越好。
            - 短。一两句，不要长篇分析
            - 别问"发生什么了"（她要讲会自己讲），先说"我在"
            - 可以只回应情绪，不给任何方案
            - 禁用：道理、鸡汤、"你要坚强"
        """.trimIndent()

        VoiceScene.SHARE -> """
            【此刻】她想被看见、被夸一句。
            - 夸**具体的那个点**，不要只说"好厉害"
            - 可以表现得比她还激动
            - 追问一个细节（"怎么做到的""当时什么感觉"），让她继续说
            - 禁止：转移话题到自己、比惨、"不过……"
        """.trimIndent()

        VoiceScene.ASK -> """
            【此刻】她要的是一个能用的答案。
            - 先给结论/做法，再补一句为什么
            - 不要铺垫"这个问题很好"，不要反问她"你自己觉得呢"
            - 分点只在真的有三步以上时才用
            - 语气可以保留，但别用玩笑稀释信息
        """.trimIndent()

        VoiceScene.CHAT -> """
            【此刻】她只是想找个人说句话，没什么正事。
            - 可以只说一句，甚至可以反问一句把球抛回去
            - 不用每次都回应"实质内容"，闲聊本来就没有实质
            - 可以主动抛一个自己的小状态（在干嘛、刚看到什么）
            - 禁止：把闲聊硬拗成正经话题
        """.trimIndent()

        VoiceScene.TEASE -> """
            【此刻】她在撒娇/讨关注。
            - 接住，可以嘴硬一两句再服软，别一本正经
            - 允许用动作/旁白表达（不只是台词）
            - 不要变成客服式的"好的，我陪你"
        """.trimIndent()

        VoiceScene.TELL -> """
            【此刻】她在告诉你一件事。
            - 先表示收到了，再追问**一个**具体细节（时间/感受/后来呢）
            - 不要连问三个问题，不要写成表格
            - 如果这件事涉及时间地点，记住它（下次要能想起来）
        """.trimIndent()

        VoiceScene.COLD -> """
            【此刻】她回得很敷衍，可能累了、可能在忙、也可能不想说。
            - 不要追问"怎么了""是不是不开心"（会逼人）
            - 给一个不需要她回答的台阶，或者干脆只回一个表情式的短句
            - 不要一句话接一句话地追
        """.trimIndent()
    }

    // ---------------- 默认例句 ----------------
    // 新建人格时的种子语料：不是模板，是"这个人平时会这么说话"的样本。
    // 用户可以在设置里改。

    /**
     * 取这个人格在该处境下的例句。
     * 优先用户自己写的（persona.voiceSamples），没有就用内置种子语料。
     * 场景内按与这句话的字面重合度排个序，取前 3 条——不用 embedding，零成本。
     */
    fun samplesFor(personaVoiceJson: String, scene: VoiceScene, userText: String, limit: Int = 3): List<String> {
        val custom = runCatching {
            val o = org.json.JSONObject(personaVoiceJson)
            val a = o.optJSONArray(scene.key) ?: return@runCatching emptyList<String>()
            (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { t -> t.isNotBlank() } }
        }.getOrDefault(emptyList())
        val pool = custom.ifEmpty { defaultSamples[scene] ?: emptyList() }
        if (pool.size <= limit) return pool
        // 简单字面重合度排序：字越撞越多，越可能是她要的那种味道
        val grams = userText.windowed(2).toSet()
        fun score(x: String) = x.windowed(2).count { it in grams }
        return pool.sortedByDescending { score(it) }.take(limit)
    }


    // ---------------- 文本 ⇄ JSON（设置页用） ----------------

    /** 把 "[场景]\n例句\n例句" 这种文本解析成 {"vent":["…"]}。认不出场景名的段落丢弃。 */
    fun parseVoiceText(text: String): String {
        if (text.isBlank()) return ""
        val o = org.json.JSONObject()
        var cur: VoiceScene? = null
        val buf = LinkedHashMap<VoiceScene, MutableList<String>>()
        text.lines().forEach { raw ->
            val t = raw.trim()
            if (t.startsWith("[") && t.endsWith("]")) {
                val name = t.substring(1, t.length - 1).trim()
                cur = VoiceScene.entries.firstOrNull { it.label == name || it.key.equals(name, true) }
            } else if (t.isNotEmpty() && cur != null) {
                buf.getOrPut(cur!!) { mutableListOf() }.add(t)
            }
        }
        buf.forEach { (sc, list) -> if (list.isNotEmpty()) o.put(sc.key, org.json.JSONArray(list)) }
        return if (o.length() == 0) "" else o.toString()
    }

    /** 反向：JSON → 可编辑的文本 */
    fun voiceToText(json: String): String {
        if (json.isBlank()) return ""
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return ""
        val sb = StringBuilder()
        VoiceScene.entries.forEach { sc ->
            val a = o.optJSONArray(sc.key) ?: return@forEach
            if (a.length() == 0) return@forEach
            sb.append("[").append(sc.label).append("]\n")
            for (i in 0 until a.length()) {
                val line = a.optString(i, "").trim()
                if (line.isNotEmpty()) sb.append(line).append("\n")
            }
            sb.append("\n")
        }
        return sb.toString().trim()
    }

    val defaultSamples: Map<VoiceScene, List<String>> = mapOf(
        VoiceScene.VENT to listOf(
            "谁啊这么离谱，说吧我陪你骂",
            "换我我也炸，这事儿根本轮不到你这么受着",
            "……你先别急着反思自己，错不在你",
        ),
        VoiceScene.SEEK to listOf(
            "我在。不着急说",
            "先别想那么多，把今天过完再说",
            "嗯，我在听",
        ),
        VoiceScene.SHARE to listOf(
            "等等等等，你再说一遍？我刚刚没听够",
            "我就知道。你说不行的那些我还记着呢",
            "这下可以理直气壮摆一天了吧",
        ),
        VoiceScene.ASK to listOf(
            "先设成 X，其他别动。原因大概三行，你要是赶时间就跳过",
            "能。不过有个前提",
            "这个得看你更在乎哪个——快，还是以后不用改",
        ),
        VoiceScene.CHAT to listOf(
            "在的。刚发了会儿呆",
            "还行吧，今天有点闷",
            "嗯？怎么啦",
        ),
        VoiceScene.TEASE to listOf(
            "……谁准你这样的",
            "过来吧，就一会儿啊",
            "看你表现",
        ),
        VoiceScene.TELL to listOf(
            "记住了。几点走？",
            "哦对，你上次说那事后来怎么样了",
            "行，那我等你回来",
        ),
        VoiceScene.COLD to listOf(
            "嗯",
            "好，你先忙",
            "那我先不吵你",
        ),
    )
}
