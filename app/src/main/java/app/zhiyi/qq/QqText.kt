package app.zhiyi.qq

/**
 * QQ 端的文本处理。
 *
 * 和 App 里相反：App 要把旁白渲染成灰色斜体，QQ 里**旁白一个都不该出现**——
 * 隔着屏幕看不见对方的表情，"她抬眼看你"发过去只会像一段奇怪的括号。
 * 所以这里做的是**只取台词**，剩下的全丢。
 */
object QqText {

    /** 表情包标记：模型在最后单独一行写这个 */
    private val STICKER_TAG = Regex("\\[(表情包?|贴纸|sticker)]", RegexOption.IGNORE_CASE)

    /**
     * 只保留说出口的话。
     *
     * @param pre/suf 台词标记（人格自定义，默认「」）
     * 一个标记都没有时，整条按台词处理——那种情况说明模型没守格式，
     * 与其丢掉整条回复，不如把包裹符号剥掉发出去。
     */
    fun onlySpeech(s: String, pre: String, suf: String): String {
        if (s.isBlank()) return ""
        val p = pre.ifBlank { "「" }
        val q = suf.ifBlank { "」" }
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val a = s.indexOf(p, i)
            if (a < 0) break
            val b = s.indexOf(q, a + p.length)
            if (b < 0) {
                // 半截标记：后面全算台词，别把话吃了
                val tail = s.substring(a + p.length).trim()
                if (tail.isNotEmpty()) out.append(tail).append('\n')
                i = s.length
                break
            }
            val seg = s.substring(a + p.length, b).trim('\n', ' ')
            if (seg.isNotEmpty()) out.append(seg).append('\n')
            i = b + q.length
        }
        val text = if (out.isBlank()) stripMarks(s) else out.toString()
        return tidy(text)
    }

    /** 要不要发表情包 */
    fun wantsSticker(s: String): Boolean = STICKER_TAG.containsMatchIn(s)

    /** 把表情标记从正文里摘掉（它不该被当成字发出去） */
    fun removeStickerTag(s: String): String = STICKER_TAG.replace(s, "")

    /**
     * 兜底清理：标记、括号旁白、多余空行。
     * 只在模型没守格式时用得上，但一旦用上就得清干净，否则会漏出「」和（）。
     */
    fun stripMarks(s: String): String {
        var t = s
        // 台词标记
        t = t.replace("「", "").replace("」", "")
        t = t.replace("『", "").replace("』", "")
        t = t.replace("“", "").replace("”", "")
        // 星号旁白
        t = t.replace("**", "")
        t = t.replace(Regex("\\*([^*\\n]+)\\*"), "$1")
        // 整行都是括号旁白的，直接删整行（这些在 QQ 里完全不该出现）
        t = t.replace(Regex("^[ \\t]*[（(【\\[][^）)】\\]]*[）)】\\]][ \\t]*$", RegexOption.MULTILINE), "")
        // 行内的括号内容去掉
        t = t.replace(Regex("[（(【\\[][^）)】\\]]*[）)】\\]]"), "")
        return tidy(t)
    }

    private fun tidy(s: String): String {
        var t = s
        t = t.replace(Regex("[ \\t]+"), " ")
        t = t.replace(Regex(" *\\n *"), "\n")
        t = t.replace(Regex("\n{2,}"), "\n")
        return t.trim()
    }

    /**
     * 按**模型自己的换行**分句——这才像真人发消息。
     *
     * 真人在手机上聊天是一句一条，不会把三句话塞进一个气泡。
     * 所以模型输出里的每个换行就是一条消息；单行太长再按句号切。
     *
     * @param maxLines 最多发几条（QQ 被动回复每条最多 4 次，留给表情包 1 次）
     */
    fun lines(s: String, maxLines: Int = 3, maxLen: Int = 300): List<String> {
        if (s.isBlank()) return emptyList()
        val raw = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (raw.isEmpty()) return emptyList()
        // 单行超长的先按句子拆开
        val expanded = ArrayList<String>()
        for (line in raw) {
            if (line.length <= maxLen) expanded.add(line)
            else expanded.addAll(chunk(line, maxLen))
        }
        if (expanded.size <= maxLines) return expanded
        // 超出的合并到最后一行的前缀里，而不是丢内容
        val keep = expanded.take(maxLines - 1).toMutableList()
        val rest = expanded.drop(maxLines - 1).joinToString(" ").trim()
        if (rest.isNotEmpty()) keep.add(if (rest.length > maxLen) rest.take(maxLen - 1) + "…" else rest)
        return keep
    }

    /**
     * QQ 单条消息有长度限制，太长的分段发。
     * 按句号/问号/换行切，尽量别在句子中间断。
     */
    fun chunk(s: String, max: Int = 400): List<String> {
        if (s.length <= max) return listOf(s)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(ch)
            val atBreak = ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?'
            if (sb.length >= max || (atBreak && sb.length >= max * 2 / 3)) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotBlank() }
    }
}
