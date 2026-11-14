package app.zhiyi.llm

/**
 * 输出守卫。
 *
 * 模型偶尔会犯两种毛病，光靠 prompt 约束挡不住：
 * 1. 突然整段说英文（尤其推理型小模型，或者上下文里混了英文素材）
 * 2. 把 system prompt 里的「规则/格式要求」当成需要回应的内容，输出
 *    "Understood. I will respond in English, follow your instructions..." 这种确认句
 *
 * 这类内容一旦落库会污染上下文，之后每一轮都带着，所以在入库前拦掉。
 */
object OutputGuard {

    private val echoPhrases = listOf(
        "i will respond in english",
        "respond in english",
        "follow your instructions",
        "as instructed",
        "understood.",
        "understood,",
        "only respond in",
        "i'm ready to help",
        "i am ready to help",
        "how can i assist",
        "as an ai language model",
        "as a language model",
    )

    /** 中文汉字占比 */
    private fun cjkRatio(t: String): Double {
        val letters = t.count { it.isLetter() }
        if (letters == 0) return 1.0
        val cjk = t.count { it.code in 0x4E00..0x9FFF }
        return cjk.toDouble() / letters
    }

    /**
     * @return true 表示这段输出不该用：要么是复述指令，要么整段跑英文
     */
    fun reject(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        if (echoPhrases.any { lower.contains(it) }) return true
        // 去掉「」和标点后判断：字数够多、却几乎没有汉字 -> 判定为跑语言了
        val plain = t.replace(Regex("[\\p{Punct}\\s]"), "")
        if (plain.length >= 12 && cjkRatio(plain) < 0.15) return true
        return false
    }

    /** 兜底用：给用户看的说明（放气泡/占位，不落库） */
    const val FALLBACK_ZH = "……"
}
