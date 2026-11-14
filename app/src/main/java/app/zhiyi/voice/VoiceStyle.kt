package app.zhiyi.voice

import android.content.Context
import app.zhiyi.core.ChatCore
import app.zhiyi.data.Db
import app.zhiyi.data.Prefs
import app.zhiyi.memory.Heart
import app.zhiyi.memory.VoiceScene
import app.zhiyi.memory.VoiceTone

/**
 * 语气。
 *
 * voiceclone 只学音色、不学语气。官方给了三条控制路径，这里都用上：
 *   1. `user` 里的自然语言指令（最灵活）
 *   2. 正文开头 `(风格)` 标签
 *   3. 句中 `[音频标签]`
 *
 * 上一版的问题是**指令太笼统**：「自然说话的样子，不要念稿的腔调」这种话对模型等于没说。
 * 官方文档里效果最好的是「导演模式」——角色 / 场景 / 指导三段，
 * 而且明确写了「场景越具体越好」。所以这一版的核心是把这两样喂进去：
 *
 *   - **场景**：她这句话是在回应什么（VoiceScene 八分类已经算好了）
 *   - **指导**：这个场景下具体该怎么演（语速、气息、停顿、尾音），再被九维修正
 */
object VoiceStyle {

    data class Cue(val instruct: String, val tag: String, val speech: String = "")

    fun cue(ctx: Context, pid: String, text: String, userText: String = ""): Cue {
        val db = Db.get(ctx)
        val h = runCatching { Heart.load(db, pid) }.getOrNull()
        val p = runCatching { db.persona(pid) }.getOrNull()
        val scene = if (userText.isNotBlank()) runCatching { VoiceTone.detect(userText) }.getOrNull() else null
        return Cue(instruct(db, h, p, text, userText, scene), tag(h, text, scene))
    }

    // ---------------- 自然语言指令（导演模式）----------------

    private fun instruct(
        db: Db,
        h: Heart.State?,
        p: app.zhiyi.data.Persona?,
        text: String,
        userText: String,
        scene: VoiceScene?,
    ): String {
        fun d(k: Heart.Dim) = (h?.get(k) ?: 50.0) / 100.0
        val sb = StringBuilder()

        // 【角色】—— 只放和"怎么说话"有关的部分，别把整份人设塞进去
        sb.append("【角色】")
        sb.append(if (p?.name.isNullOrBlank()) "一个真人" else p.name)
        if (!p?.role.isNullOrBlank()) sb.append("，对方的").append(p.role)
        if (!p?.speech.isNullOrBlank()) sb.append("。她平时").append(p.speech.trim().take(80))
        sb.append("。\n")

        // 【场景】—— 官方说越具体越好，这里把"对方刚说了什么"直接放进去
        sb.append("【场景】")
        if (userText.isNotBlank()) {
            sb.append("对方刚对她说：「").append(userText.take(60)).append("」。")
            sb.append("她现在在回这句话")
            if (scene != null) sb.append("，对方的状态是「").append(scene.label).append("」")
            sb.append("。\n")
        } else {
            sb.append("没有人对她说话，她自己忽然开口，对面是安静的。\n")
        }

        // 【指导】—— 场景先定表演方向，九维再修正
        sb.append("【指导】")
        sb.append(sceneCue(scene))
        sb.append("。")

        val mods = ArrayList<String>()
        val tired = d(Heart.Dim.FATIGUE)
        val stress = d(Heart.Dim.STRESS)
        val joy = d(Heart.Dim.JOY)
        val attach = d(Heart.Dim.ATTACH)
        val rumi = d(Heart.Dim.RUMINATION)

        when {
            tired >= 0.72 -> mods += "不过她累了：声音比平时低，气声多一点，语速慢半拍，长句会断在中间"
            stress >= 0.70 -> mods += "她心里绷着：句子收得急，不拖尾音，但别听起来像在生气"
            joy >= 0.68 -> mods += "她心情好，语气往上走一点，带一点没忍住的笑意"
            rumi >= 0.70 -> mods += "她有点走神，像刚从自己的念头里回过来，开头半句轻一点"
        }
        if (attach >= 0.7) mods += "对这个人不用字正腔圆，可以含糊、可以省略主语"
        if (mods.isNotEmpty()) sb.append("但").append(mods.joinToString("；")).append("。")

        // 文本自身的形状
        val short = text.length <= 10
        val ellipsis = text.contains("…") || text.contains("...")
        val ex = text.count { it == '！' || it == '!' }
        val tail = ArrayList<String>()
        if (short) tail += "这句话很短，轻着说，说完就停，不要拖"
        if (ellipsis) tail += "省略号的地方真的留一点空白，不要用语气填满"
        if (ex >= 2) tail += "多个感叹号的地方情绪顶上去，但别喊"
        if (tail.isNotEmpty()) sb.append(tail.joinToString("；")).append("。")

        sb.append("整体像真人在说话：有呼吸、有停顿、有不完美的尾音，不要播音腔。")
        return sb.toString()
    }

    /** 八类场景各自的表演方向。这是"准不准"的关键——泛泛说"温柔"没有用，要说清怎么个温柔法 */
    private fun sceneCue(scene: VoiceScene?): String {
        return when (scene) {
            VoiceScene.VENT -> "对方在倒苦水，先站到她那边：语速跟着她的情绪稍快一点，语气软但不敷衍，" +
                "不要冷静、不要讲道理、不要用「其实」「话说回来」这种转折腔"
            VoiceScene.SEEK -> "对方已经撑不住了：声音放低放慢，句与句之间留空，" +
                "宁可少说，也不要急着把沉默填满，尾音往下沉"
            VoiceScene.SHARE -> "对方是来分享好消息的：语气轻快上扬，带着真的替她高兴的劲儿，" +
                "可以抢半句话，别用平铺直叙的转述腔"
            VoiceScene.ASK -> "对方在求助：先给一句明确的回应，语速正常偏快，重音落在答案上，" +
                "别像客服，别在开头加「这个问题」之类的缓冲"
            VoiceScene.CHAT -> "随便聊两句：语气松，允许话说半句就停，不要每一句都收得很完整"
            VoiceScene.TEASE -> "在撩人：语速慢半拍，尾音微微上扬带一点故意的味道，" +
                "中途可以轻轻笑一下，但别笑满"
            VoiceScene.TELL -> "在说一件正事：语速平常，咬字清楚，把关键信息说重，" +
                "不需要多余的情绪起伏"
            VoiceScene.COLD -> "她没什么力气说话：短、平、不解释，音量和起伏都压下去，" +
                "但别演出「生气」的感觉，是「没劲」"
            null -> "正常说话，语气跟着这句话本身的内容走，别端着"
        }
    }

    // ---------------- 风格标签 ----------------

    private fun tag(h: Heart.State?, text: String, scene: VoiceScene?): String {
        fun d(k: Heart.Dim) = (h?.get(k) ?: 50.0) / 100.0
        val out = ArrayList<String>()

        // 场景决定主基调
        when (scene) {
            VoiceScene.VENT -> out += listOf("温柔", "无奈")
            VoiceScene.SEEK -> out += listOf("温柔", "深沉")
            VoiceScene.SHARE -> out += listOf("开心", "活泼")
            VoiceScene.ASK -> out += listOf("平静", "干练")
            VoiceScene.CHAT -> out += listOf("慵懒")
            VoiceScene.TEASE -> out += listOf("俏皮", "温柔")
            VoiceScene.TELL -> out += listOf("平静")
            VoiceScene.COLD -> out += listOf("平静", "冷漠")
            null -> out += listOf("平静")
        }

        // 九维覆盖：疲惫/压力比"平静"更该说话
        when {
            d(Heart.Dim.FATIGUE) >= 0.72 -> out.add(0, "疲惫")
            d(Heart.Dim.STRESS) >= 0.70 -> out.add(0, "忐忑")
            d(Heart.Dim.JOY) >= 0.68 -> out.add(0, "开心")
        }
        if (d(Heart.Dim.ATTACH) >= 0.7) out += "温柔"
        if (text.count { it == '！' || it == '!' } >= 2) out += "兴奋"
        if (text.length <= 10 && text.contains("？")) out += "俏皮"
        return out.distinct().take(3).joinToString(" ")
    }

    /** 正文包装：开头 (风格) + 句中 [标签] */
    fun decorate(text: String, tag: String): String {
        if (text.isBlank()) return text
        if (text.startsWith("(") || text.startsWith("（") || text.startsWith("[")) return text
        val body = insertAudioTags(text)
        return if (tag.isBlank()) body else "($tag)$body"
    }

    /** 只在明确的信号处插标签；不认识的句式一律不动，加多了会把句子切碎 */
    private fun insertAudioTags(text: String): String {
        var s = text
        s = s.replace(Regex("(?<=^|[。！？…，\\s])(唉|哎)(?=[…，、]|$)"), "[叹气]$1")
        s = s.replace(Regex("哈哈+"), "[轻笑]$0")
        s = s.replace(Regex("嘿嘿+"), "[轻笑]$0")
        return s
    }
}
