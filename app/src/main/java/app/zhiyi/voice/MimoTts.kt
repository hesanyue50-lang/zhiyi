package app.zhiyi.voice

import android.content.Context
import app.zhiyi.core.ChatCore
import app.zhiyi.data.Db
import app.zhiyi.data.Prefs
import app.zhiyi.memory.Heart
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 用 MiMo 的声音克隆把台词念出来。
 *
 * ## 为什么它慢（三件事叠在一起）
 *
 * 1. **voiceclone 的流式是「兼容模式」**——官方写明「仅在所有推理完成后以流式格式返回一次结果」。
 *    也就是说不管怎么调，都必须等**整段**合成完才能拿到第一个字节。真正的流式收益这里为零。
 * 2. **样本每次都要内联上传**——`audio.voice` 里塞 data URL，样本越大上行越久。
 * 3. **一句话越长越慢**，而且中间没有任何可播的东西。
 *
 * ## 所以优化只能从「别等整段」「别重复等」「别在用户点之后才开始」三处下手
 *
 * - **分句流水**：按标点切句，第一句合成完就开始播，剩下的在播第一句的同时并行取。
 * - **结果缓存**：同一句话（同一人格、同一样本）第二次播放直接读本地。
 * - **预取**：消息刚出现就在后台悄悄合成好，用户点喇叭时缓存已经在了。
 *
 * ## 并发模型（这块是踩过坑重写的）
 *
 * `track / worker / playing` 是全局的，而一次播放可能被下一次播放叫停。如果让每个 worker
 * 无条件在 finally 里 `releaseTrack() + playing = false`，旧线程就会掐掉新线程正在播的音频，
 * 还把按钮状态踩成「未播放」。
 *
 * 所以用一个**代次号** `gen`：每次 speak/stop 自增，worker 记住自己是第几代，
 * 只有当 `gen` 仍然等于自己那一代时才允许动共享字段。
 *
 * 另外 `Thread.interrupt()` **打不断阻塞的 socket read**，所以在途连接单独存一份，
 * stop 时直接 `disconnect()` 把读打断——否则按了停止还要等最多两分钟才真的停。
 */
object MimoTts {

    private const val TAG = "MimoTts"
    private const val BASE = "https://api.xiaomimimo.com/v1"
    private const val MODEL = "mimo-v2.5-tts-voiceclone"
    private const val RATE = 24000
    /**
     * 播放语速。
     *
     * 模型的默认语速明显偏慢（像念稿），这是"不像正常人说话"最主要的一条。
     * 用 AudioTrack 的 time-stretch 直接加速，同时把 pitch 钉在 1.0 上——
     * setSpeed 会连音高一起变，必须显式 setPitch 才是"说得快但声音不变"。
     */
    private const val SPEED = 1.15f

    /**
     * 这一次播放的语速与音高，按九维算。
     *
     * 为什么要在播放器这里再动一次手：模型对"她很累"的理解是概率性的，
     * 而语速和音高是**物理量**，改了就一定听得出来。
     * 累了就慢下来压低，急了就赶，高兴了往上飘——这三件事不该等模型去体会。
     *
     * 幅度控制在 0.92~1.38 / 0.96~1.03：再大就不是"情绪"，是变声。
     */
    @Volatile private var curSpeed = SPEED
    @Volatile private var curPitch = 1.0f

    private fun tuneToMood(h: Heart.State?) {
        if (h == null) { curSpeed = SPEED; curPitch = 1.0f; return }
        fun d(k: Heart.Dim) = ((h[k] ?: 50.0) / 100.0).toFloat()
        val tired = d(Heart.Dim.FATIGUE)
        val stress = d(Heart.Dim.STRESS)
        val joy = d(Heart.Dim.JOY)
        val rumi = d(Heart.Dim.RUMINATION)
        curSpeed = (SPEED
            - 0.16f * tired          // 累了：慢
            + 0.12f * stress         // 绷着：赶
            + 0.10f * joy            // 高兴：快
            - 0.07f * rumi           // 走神：拖
            ).coerceIn(0.92f, 1.38f)
        curPitch = (1.0f
            - 0.030f * tired         // 累了下沉
            + 0.022f * joy           // 高兴往上飘
            ).coerceIn(0.96f, 1.03f)
    }

    /** 缓存上限。一句 8 秒的 PCM 约 384KB，40MB 能存一百来句 */
    private const val CACHE_LIMIT = 40L * 1024 * 1024

    /** 短于一整句的就不拆了，拆了反而多一次请求 */
    private const val SPLIT_MIN_CHARS = 34
    /**
     * 首句最多这么长。
     *
     * 这个数不能太小：首句太短，播放时间盖不住"剩余那段"的上传 + 推理，中间就会空一大截——
     * 听感上就是"念一小句，然后顿好久"。16 字只够播三四秒，30 字约十秒，才盖得住。
     */
    private const val SPLIT_FIRST_MAX = 22

    /** 每次 speak / stop 自增，worker 只在自己这代仍有效时才碰共享状态 */
    private val gen = AtomicInteger(0)

    @Volatile private var track: AudioTrack? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var prefetcher: Thread? = null
    /** 正在预取的目标缓存文件；speak 遇到同一条就等它，而不是再跑一遍 */
    @Volatile private var prefetchingFor: String? = null
    @Volatile private var conn: HttpURLConnection? = null

    var playing by mutableStateOf(false); private set

    /** 正在念的是哪一条（pid|文本）。全局 playing 会让所有喇叭一起亮，所以要多记一个身份 */
    var playingKey by mutableStateOf<String?>(null); private set

    private fun keyOf(pid: String, text: String) = pid + "|" + text.hashCode()

    fun isPlaying(pid: String, raw: String): Boolean =
        playingKey == keyOf(pid, speechText(raw))

    // ---------------- 缓存 ----------------

    private fun cacheDir(ctx: Context): File =
        File(ctx.filesDir, "tts2").apply { runCatching { mkdirs() } }

    // style 必须进 key：同一句话"疲惫地说"和"开心地说"是两段音频，
    // 不带 style 就会读到上一次情绪的缓存，听起来像没换过语气。
    private fun key(pid: String, text: String, voiceTag: String, style: String): String {
        val md = MessageDigest.getInstance("MD5")
        val h = md.digest("$pid|$voiceTag|$style|$text".toByteArray(Charsets.UTF_8))
        return pid.take(24) + "_" + h.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(ctx: Context, pid: String, text: String, style: String): File =
        File(cacheDir(ctx), key(pid, text, VoiceStore.fingerprint(ctx, pid), style) + ".pcm")

    fun clearCache(ctx: Context) {
        runCatching { cacheDir(ctx).listFiles()?.forEach { it.delete() } }
    }

    fun cacheSize(ctx: Context): Long =
        runCatching { cacheDir(ctx).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    /**
     * 先写 .tmp 再 rename —— rename 在同一文件系统里是原子的。
     * 直接往目标文件写的话，进程被杀 / 被 interrupt 时会留下半截 PCM，
     * 而命中判断只看「文件存在且非空」，那份残句就会被**永久**当成有效缓存播放。
     */
    private fun writeAtomic(target: File, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            runCatching { target.writeBytes(bytes) }
            runCatching { tmp.delete() }
        }
    }

    /** 超上限时按最后修改时间从旧到新删（忽略半成品 .tmp） */
    private fun trimCache(ctx: Context) {
        runCatching {
            val files = cacheDir(ctx).listFiles()
                ?.filter { !it.name.endsWith(".tmp") }
                ?.sortedBy { it.lastModified() } ?: return
            var total = files.sumOf { it.length() }
            for (f in files) {
                if (total <= CACHE_LIMIT) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }

    // ---------------- 播放控制 ----------------

    fun stop() {
        gen.incrementAndGet()
        val w = worker
        worker = null
        val c = conn
        conn = null
        // interrupt 打不断阻塞的 socket read，必须直接断开连接
        runCatching { c?.disconnect() }
        runCatching { w?.interrupt() }
        releaseTrack()
        playing = false
        playingKey = null
    }

    private fun releaseTrack() {
        val t = track
        track = null
        runCatching { t?.pause(); t?.flush(); t?.release() }
    }

    /**
     * 念一段。会先停掉正在念的。
     * onEnd 带回错误文案，成功时为 null。
     */
    fun speak(ctx: Context, pid: String, raw: String, onEnd: (String?) -> Unit = {}) {
        val text = speechText(raw)
        if (text.isBlank()) return
        val app = ctx.applicationContext
        stop()

        val cfg = Prefs.get(app).data
        if (cfg.ttsKey.isBlank()) {
            onEnd("还没填 MiMo API Key")
            return
        }
        // 这里只做廉价判断：dataUrl 要读盘 + base64（最大 7MB → 9MB 字符串），
        // 放在主线程每次点喇叭都卡一下，挪进 worker。
        if (!VoiceStore.has(app, pid)) {
            onEnd("这个人格还没有声音样本")
            return
        }

        val myGen = gen.incrementAndGet()
        val key = cfg.ttsKey.trim()
        playing = true
        playingKey = keyOf(pid, text)

        worker = Thread {
            var err: String? = null
            fun alive() = gen.get() == myGen
            try {
                if (!alive()) return@Thread
                val voice = VoiceStore.dataUrl(app, pid)
                    ?: throw RuntimeException("这个人格还没有声音样本")

                // 语气两条路都用上：user 里的自然语言指令 + 正文开头的 (风格) 标签
                val cue = cueOf(app, pid, text)
                runCatching {
                    tuneToMood(Heart.load(Db.get(app), pid))
                }
                val instruct = cue.instruct
                // 有导演给的带节奏念法就照它念（风格标签放最前面，因为它才是整体基调）；
                // 没有就用规则版包装原文
                val spoken = if (cue.speech.isNotBlank()) {
                    if (cue.tag.isBlank()) cue.speech else "(" + cue.tag + ")" + cue.speech
                } else {
                    VoiceStyle.decorate(text, cue.tag)
                }

                // 缓存命中也得先算——playPcm 会新建 track，用的就是这两个值
                val hit = cacheFile(app, pid, text, cue.tag)
                // 同一条正在后台预取 → 等它把缓存写完，别开第二份任务抢带宽。
                // 这是"点了没反应、过一会儿突然两个声音"的来源之一。
                if (prefetchingFor == hit.path && !hit.isFile) {
                    runCatching { prefetcher?.join(12_000) }
                }
                if (hit.isFile && hit.length() > 0) {
                    Log.d(TAG, "缓存命中 ${hit.length()}B")
                    playPcm(hit.readBytes(), myGen)
                    return@Thread
                }

                // 整段一次请求。
                //
                // 分句的代价被低估了：voiceclone 每次调用都要内联整份样本（~240KB base64），
                // 分两次就是上传两次，前一段还在播、后一段还在传，中间必然空一截；
                // 而且每段各自收尾，(风格) 标签和节奏标记也只能盖住第一段。
                // 换成一次发完整段：首包慢一点，但播放是连续的一条，语气也才是同一个人一口气说的。
                val t0 = System.currentTimeMillis()
                val pcm = fetchPcm(key, voice, spoken, myGen, instruct)
                if (pcm.isEmpty()) throw RuntimeException("没有返回音频")
                if (!alive()) return@Thread
                Log.d(TAG, "音频就绪 ${System.currentTimeMillis() - t0}ms / ${pcm.size}B")

                playPcm(pcm, myGen)
                Log.d(TAG, "播放完成 ${System.currentTimeMillis() - t0}ms")

                runCatching {
                    writeAtomic(cacheFile(app, pid, text, cue.tag), pcm)
                    trimCache(app)
                }
            } catch (e: Throwable) {
                err = if (!alive() || e is InterruptedException) null else (e.message ?: "播放失败")
            } finally {
                // 只有自己这一代还活着才收尾，否则会释放新一轮的 track、把 playing 清零
                if (alive()) {
                    releaseTrack()
                    playing = false
                    playingKey = null
                }
                // 回调放在 alive 检查外面：哪怕是"被新一轮顶掉"的旧 worker，
                // 也要让调用方拿到结束信号（它只会清自己的 error 状态，不会影响新一轮）
                runCatching { onEnd(err) }
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    /**
     * 后台预合成，只写缓存不播。
     *
     * 消息刚冒出来就偷偷做好，用户真点喇叭时就是读本地——把「合成」和「点击」从串行变并行。
     * 失败静默，反正用户点的时候还会再试一次。
     */
    fun prefetch(ctx: Context, pid: String, raw: String) {
        val text = speechText(raw)
        if (text.isBlank()) return
        val app = ctx.applicationContext
        val cfg = Prefs.get(app).data
        if (cfg.ttsKey.isBlank()) return
        if (!VoiceStore.has(app, pid)) return
        val cue = cueOf(app, pid, text)
        val f = cacheFile(app, pid, text, cue.tag)
        if (f.isFile && f.length() > 0) return
        if (playing) return

        val key = cfg.ttsKey.trim()
        val myGen = gen.get()
        runCatching { prefetcher?.interrupt() }
        prefetchingFor = f.path
        prefetcher = Thread {
            runCatching {
                val voice = VoiceStore.dataUrl(app, pid) ?: return@runCatching
                if (Thread.currentThread().isInterrupted) return@runCatching
                if (gen.get() != myGen) return@runCatching
                // 预取一次取整段：既然样本每次都要重传，拆开没有任何好处
                val pcm = fetchPcm(key, voice, VoiceStyle.decorate(text, cue.tag), myGen, cue.instruct)
                writeAtomic(f, pcm)
                trimCache(app)
                Log.d(TAG, "预取完成 ${f.length()}B")
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun playPcm(bytes: ByteArray, myGen: Int) {
        if (bytes.isEmpty()) return
        val tr = newTrack()
        if (gen.get() != myGen) {
            runCatching { tr.release() }
            return
        }
        track = tr
        tr.play()
        tr.write(bytes, 0, bytes.size)
        runCatching { tr.stop() }
    }

    /**
     * 算出这一次的语气。
     *
     * 顺带解决一个隐蔽的问题：缓存 key 只能吃 `tag`，不能吃整段 instruct。
     * instruct 里有九维情绪的连续值，两次调用（预取 vs 真正播放）之间哪怕情绪动了 0.1，
     * 算出来的字符串就不同 → 同一个文件被写成两个 key，缓存永远不命中。
     * tag 是离散化后的（疲惫/忐忑/开心/温柔），短期稳定，用它做 key 才有意义。
     */
    private fun cueOf(ctx: android.content.Context, pid: String, text: String): VoiceStyle.Cue {
        // 参数名不能叫 app —— 会遮蔽包名 app，Db 就解析不出来了
        val db = Db.get(ctx)
        val h = runCatching { Heart.load(db, pid) }.getOrNull()

        // 1) 先看缓存：预取时已经算过就直接用，不花钱也不占时间
        runCatching { VoiceDirector.cached(ctx, pid, text, h) }.getOrNull()?.let {
            return VoiceStyle.Cue(it.instruct, it.tag)
        }

        val lastUser = runCatching {
            val tk = ChatCore.threadKey(pid, Prefs.get(ctx).data.chatMode)
            db.recentMsgs(tk, 10).lastOrNull { it.role == "user" }?.content ?: ""
        }.getOrDefault("")

        // 2) 叫导演模型现算（最多等 5 秒，超时/null 自动往下走）
        runCatching { VoiceDirector.direct(ctx, pid, text, lastUser) }.getOrNull()?.let {
            return VoiceStyle.Cue(it.instruct, it.tag)
        }

        // 3) 回落：规则版。没网、没 key、模型抽风时至少不会哑掉
        return VoiceStyle.cue(ctx, pid, text, lastUser)
    }

    // ---------------- 网络 ----------------

    private fun fetchPcm(key: String, voiceUrl: String, text: String, myGen: Int, instruct: String = ""): ByteArray {
        val body = JSONObject().apply {
            put("model", MODEL)
            put("stream", true)
            put("messages", JSONArray().apply {
                // 官方示例里 user 是空串，但文档明确写了「user 消息可以传入指令来调整语气与风格」，
                // voiceclone 同样吃这条（只有 voicedesign 是必填）。留空 = 让她干念。
                put(JSONObject().put("role", "user").put("content", instruct))
                put(JSONObject().put("role", "assistant").put("content", text))
            })
            put(
                "audio", JSONObject().apply {
                    put("format", "pcm16")
                    put("voice", voiceUrl)
                },
            )
        }

        val c = (URL("$BASE/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "text/event-stream")
        }
        // 登记在途连接，stop() 才能 disconnect 打断阻塞读
        conn = c
        try {
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: ""
                throw RuntimeException("MiMo $code ${msg.take(160)}")
            }

            val out = java.io.ByteArrayOutputStream()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("tts cancelled")
                    val line = r.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.substring(5).trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    val delta = obj.optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                    val audio = delta.optJSONObject("audio") ?: continue
                    val b64 = audio.optString("data", "")
                    if (b64.isBlank()) continue
                    val pcm = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull() ?: continue
                    out.write(pcm)
                }
            }
            // 被打断时不能把半截 PCM 当正常结果交出去——那会被当成完整音频播出去 / 缓存下来
            if (Thread.currentThread().isInterrupted || gen.get() != myGen) {
                throw InterruptedException("tts cancelled")
            }
            return out.toByteArray()
        } finally {
            if (conn === c) conn = null
            runCatching { c.disconnect() }
        }
    }

    /** 给 track 挂上语速。设备不支持时静默跳过（宁可慢一点也别崩） */
    private fun applySpeed(tr: AudioTrack) {
        runCatching {
            tr.playbackParams = android.media.PlaybackParams()
                .setSpeed(curSpeed)
                .setPitch(curPitch)
        }
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(if (min > 0) min * 2 else 64 * 1024)
            .build()
            .also { applySpeed(it) }
    }

    // ---------------- 文本处理 ----------------

    /**
     * 把一条回复变成「能念的字」。
     *
     * 台词在「」内、动作在括号里；旁白念出来会很怪（「（歪头看你）」），
     * 所以有台词就只念台词。全是旁白时才退回念正文。
     */
    fun speechText(raw: String): String {
        var s = raw
        val quoted = Regex("[「『]([^」』]*)[」』]")
            .findAll(s).map { it.groupValues[1] }.joinToString(" ")
        if (quoted.isNotBlank()) s = quoted
        s = s.replace(Regex("[（(][^）)]*[）)]"), " ")
        s = s.replace(Regex("[「」『』（）()\\[\\]【】*#`]"), "")
        return s.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * 按标点切句给流水线用。
     *
     * 太短的不切——一句话拆成两次请求，多出来的一次往返比省下的合成时间还贵。
     */
    /**
     * 分句。`firstMax > 0` 时把首句单独压短。
     *
     * 因为 voiceclone 的"流式"是降级的——要等整句推理完才返回，首句多长就要等多久。
     * 首句压到十几个字，开口时间能砍一半；后面几句再并发取。
     */
    fun splitSentences(text: String, firstMax: Int = 0): List<String> {
        val raw = cutSentences(text)
        if (firstMax <= 0 || raw.isEmpty()) return raw
        val first = raw[0]
        if (first.length <= firstMax) return raw
        // 在首句里找一个靠后的逗号断开，找不到就硬切
        var cut = -1
        for (i in first.length - 1 downTo 1) {
            if (first[i] in "，,、：" && i <= firstMax + 6 && i >= 5) { cut = i + 1; break }
        }
        if (cut < 0) cut = firstMax.coerceAtMost(first.length)
        val head = first.substring(0, cut).trim()
        val tail = first.substring(cut).trim()
        if (head.isEmpty() || tail.isEmpty()) return raw
        return listOf(head, tail) + raw.drop(1)
    }

    private fun cutSentences(text: String): List<String> {
        if (text.length <= SPLIT_MIN_CHARS) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch in "。！？!?；;…\n") {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) out.add(s)
                sb.setLength(0)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) {
            // 尾巴太短就并到上一句，避免「好啊。」单独发一次请求
            if (out.isNotEmpty() && tail.length < 8) {
                out[out.size - 1] = out.last() + tail
            } else {
                out.add(tail)
            }
        }
        return if (out.isEmpty()) listOf(text) else out
    }
}
