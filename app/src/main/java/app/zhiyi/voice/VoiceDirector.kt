package app.zhiyi.voice

import android.content.Context
import app.zhiyi.core.ChatCore
import app.zhiyi.data.Db
import app.zhiyi.data.Prefs
import app.zhiyi.data.modelList
import app.zhiyi.llm.Llm
import app.zhiyi.memory.Heart
import app.zhiyi.memory.JsonUtil
import app.zhiyi.memory.VoiceScene
import app.zhiyi.memory.VoiceTone
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * 语气导演：单独叫一次模型，专门为"这一句话"定表演方式。
 *
 * 为什么不再用规则：规则只能覆盖我想到的情况。「疲惫 = 声音低」是人人成立的套话，
 * 而真正准的语气是**这三样东西一起**决定的——
 *
 *   1. 她此刻的心境（九维，量化成数值）
 *   2. 刚刚发生了什么（对方那句话 + 八类场景判定）
 *   3. 她是谁（性格 / 说话方式 / 阴影 / 向往）
 *
 * 规则算不出「一个怕被丢下的人，在对方说'算了不用你管'的时候会用什么样的声音回话」。
 * 模型可以。所以这里把三样都结构化喂进去，让它输出具体的表演要领。
 *
 * 成本与延迟：每次播放多一次 cheapModel 调用（~200 token）。
 * 靠两件事消化——**预取时就生成**、以及**按情绪档位缓存**。
 */
object VoiceDirector {

    data class Cue(val instruct: String, val tag: String, val speech: String = "")

    // 之前 5 秒太宽松：超时的代价直接叠在首包延迟上。2.5 秒拿不到就回落规则版
    private const val TIMEOUT_MS = 2_500L

    /** 情绪量化成三档再拼 key：直接拿浮点值当 key，两次调用差值 0.1 就永远命中不了 */
    private fun moodBucket(h: Heart.State): String {
        fun b(d: Heart.Dim) = when {
            (h[d] ?: 50.0) >= 66 -> 2
            (h[d] ?: 50.0) >= 33 -> 1
            else -> 0
        }
        return listOf(Heart.Dim.FATIGUE, Heart.Dim.STRESS, Heart.Dim.JOY,
            Heart.Dim.ATTACH, Heart.Dim.INTIMACY, Heart.Dim.RUMINATION)
            .joinToString("") { b(it).toString() }
    }

    private fun cacheKey(pid: String, text: String, bucket: String): String =
        "vd_" + pid + "_" + (pid + text + bucket).hashCode().toUInt().toString(16)

    fun cached(ctx: Context, pid: String, text: String, h: Heart.State?): Cue? {
        val db = Db.get(ctx)
        val st = h ?: return null
        val raw = db.get(cacheKey(pid, text, moodBucket(st)), "")
        if (raw.isBlank()) return null
        val o = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        val tag = o.optString("tag", "")
        val dir = o.optString("direct", "")
        if (dir.isBlank()) return null
        val rawSpeech = o.optString("speech", "").trim()
        val speech = if (normalize(rawSpeech) == normalize(text)) rawSpeech else ""
        return Cue(build(tag, dir), tag, speech)
    }

    /**
     * 真正去问模型。失败/超时返回 null，由调用方回落到规则版 VoiceStyle。
     * 在 worker 线程里调，别放主线程。
     */
    fun direct(ctx: Context, pid: String, text: String, userText: String): Cue? {
        val db = Db.get(ctx)
        val cfg = Prefs.get(ctx).data
        val h = runCatching { Heart.load(db, pid) }.getOrNull() ?: return null
        val p = runCatching { db.persona(pid) }.getOrNull() ?: return null
        val prov = ChatCore.providerFor(ctx, pid) ?: ChatCore.activeProvider(ctx) ?: return null
        val model = prov.cheapModel.modelList().firstOrNull()
            ?: prov.chatModel.modelList().firstOrNull() ?: return null
        if (cfg.ttsKey.isBlank()) return null

        val scene = if (userText.isNotBlank()) runCatching { VoiceTone.detect(userText) }.getOrNull() else null
        val ctxLines = runCatching {
            db.recentMsgs(ChatCore.threadKey(pid, cfg.chatMode), 8)
                .filter { it.content.isNotBlank() }
                .takeLast(6)
                .joinToString("\n") {
                    (if (it.role == "user") "对方" else p.name) + "：" + it.content.take(80).replace("\n", " ")
                }
        }.getOrDefault("")

        val ask = buildString {
            append("【她是谁】\n")
            append("名字：").append(p.name).append("\n")
            if (p.personality.isNotBlank()) append("性格：").append(p.personality.take(200)).append("\n")
            if (p.speech.isNotBlank()) append("说话方式：").append(p.speech.take(150)).append("\n")
            if (p.shadow.isNotBlank()) append("她自己不愿承认的部分：").append(p.shadow.take(120)).append("\n")
            if (p.aspire.isNotBlank()) append("她心里想要的：").append(p.aspire.take(120)).append("\n")

            append("\n【她此刻的状态】0-100，越高越强\n")
            append(Heart.Dim.entries.joinToString("、") { d ->
                val v = (h[d] ?: 50.0).toInt()
                val mark = when {
                    v >= 72 -> "（明显偏高）"
                    v <= 28 -> "（明显偏低）"
                    else -> ""
                }
                d.name.lowercase().replaceFirstChar { it.uppercase() } + " " + v + mark
            })
            append("\n")
            append("标了「明显偏高／偏低」的维度必须在表演要领里留下痕迹，而且要能指出来是哪一条影响的：\n")
            append("疲惫高 → 语速慢、气声多、长句断在中间；压力高 → 句子收得急、尾音短、不拖；\n")
            append("喜悦高 → 尾音往上飘、句子之间几乎不留空；反刍高 → 开头半句轻，像刚从念头里回神。\n")
            append("不许写「她心情很复杂」这种什么都没说的话。\n")

            if (ctxLines.isNotBlank()) {
                append("\n【最近说的话】\n").append(ctxLines).append("\n")
            }

            append("\n【刚刚发生的】\n")
            if (userText.isNotBlank()) {
                append("对方对她说：「").append(userText.take(80)).append("」\n")
                if (scene != null) append("（这属于「").append(scene.label).append("」）\n")
            } else {
                append("没有人对她说话，她自己忽然开口。\n")
            }

            append("\n【她要念的这一句】\n「").append(text.take(160)).append("」\n")

            append("\n【你的任务】\n")
            append("写出她念这句话时**具体**的表演要领：语速、气息、停顿位置、重音落在哪个字、尾音往哪走。\n")
            append("必须从上面三样东西推出来。不能是任何角色在任何情境下都成立的套话。\n")
            append("反例（不要写）：温柔地说、自然地说话、要有感情、带着感情读完。\n")
            append("正例：开头半句压低给气声，「那种人」三个字咬重；逗号处停一拍；尾句放软，不要用劝人的语气。\n")
            if (p.shadow.isNotBlank()) {
                append("还要注意她「").append(p.shadow.take(40))
                append("」这类内在张力会在语气上留痕迹——明明在意，却把尾音收得很快。\n")
            }

            append("\n【最后一件，也是最重要的】\n")
            append("真人说话不是匀速的：该赶的地方赶，该停的地方停，同一句话里节奏会变。\n")
            append("请把上面那句原文**原样**返回，只在需要的地方插入括号标记来定节奏，\n")
            append("可用：（语速加快）（放慢）（压低）（拔高）（停顿）（小声）（提高音量）\n")
            append("除了这些括号标记，一个字都不许改、不许增删。至少用两个标记。\n")

            append("\n只输出 JSON，不要任何解释：\n")
            append("{\"tag\":\"两个风格标签，只能从这些里挑：温柔 平静 疲惫 无奈 怅然 欣慰 俏皮 慵懒 深沉 冷漠 忐忑 开心 活泼 兴奋 严肃 干练\",")
            append("\"direct\":\"表演要领，一到三句中文\",")
            append("\"speech\":\"加了节奏括号的原文\"}")
        }

        val task = FutureTask {
            runCatching {
                Llm(prov).chat(
                    model,
                    listOf("system" to "你是一个配音导演，只输出 JSON，不要解释。", "user" to ask),
                    0.4,
                    140,
                )
            }.getOrNull()
        }
        Thread(task, "voice-direct").apply { isDaemon = true; start() }
        val raw = runCatching { task.get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }.getOrNull() ?: return null

        val obj = runCatching { JsonUtil.objectOf(raw) }.getOrNull() ?: return null
        val tag = obj.optString("tag", "").trim()
        val dir = obj.optString("direct", "").trim()
        if (dir.length < 4) return null

        // 模型给的念法：只有括号标记算数，正文必须和原文一致。
        // 不一致就丢掉——宁可匀速念，也不能让它偷偷改词或漏字。
        val rawSpeech = obj.optString("speech", "").trim()
        val speech = if (normalize(rawSpeech) == normalize(text)) rawSpeech else ""

        db.put(cacheKey(pid, text, moodBucket(h)), obj.toString())
        return Cue(build(tag, dir), tag, speech)
    }

    /** 去掉括号标记、空白和标点后比较，判断模型有没有偷改正文 */
    private fun normalize(x: String): String =
        x.replace(Regex("[（(][^）)]*[）)]"), "")
            .replace(Regex("[\\s，。！？、；：…,.!?;:]"), "")

    private fun build(tag: String, dir: String): String = buildString {
        append("【指导】").append(dir)
        if (tag.isNotBlank()) append("（整体基调：").append(tag).append("）")
        append("。像真人在说话：有呼吸、有停顿、有不完美的尾音，不要播音腔。")
    }
}
