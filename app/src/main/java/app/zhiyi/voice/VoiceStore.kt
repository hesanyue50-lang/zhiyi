package app.zhiyi.voice

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File

/**
 * 人格的声音样本。
 *
 * MiMo 的 voiceclone 没有「上传一次换个 voice_id」那套——**每次请求都要内联带样本**
 * （`audio.voice` 里塞 `data:audio/mpeg;base64,...`）。所以样本必须常驻本地、能随时读成 data URL。
 *
 * 官方限制：base64 编码后不超过 10MB。base64 会把体积撑到 4/3，所以原始文件卡在 9MB。
 *
 * 三种来源，优先级从高到低：
 * 1. 用户自己传的文件（filesDir/voice/<pid>.mp3）
 * 2. 内置预设（assets/voice 下的 mp3，随包带的几秒干净人声）
 * 3. 没有
 */
object VoiceStore {

    // 官方限制是「base64 编码后 ≤ 10MB」。base64 会撑到 4/3，所以原始文件必须 ≤ 7.5MB，
    // 卡在 9MB 的话 7.5~9MB 的文件本地能过、服务端必拒，错误信息还看不懂。
    private const val MAX_RAW_BYTES = 7L * 1024 * 1024
    private val EXTS = listOf("mp3", "wav", "m4a")

    /**
     * 内置预设。都是从公开语音合集里截的 7~8 秒干声、做过响度归一（-20 LUFS / TP -2dB）。
     *
     * 素材权属：角色声音归米哈游，配音演员有声音权。只做本机音色参考，不要随包分发、
     * 不要商用——真要用在要发布的东西里，换成自己的录音或 voicedesign 文字设计。
     */
    val PRESETS: List<Pair<String, String>> = listOf(
        "elysia" to "爱莉希雅",
        "furina" to "芙宁娜",
    )

    fun presetName(key: String): String =
        PRESETS.firstOrNull { it.first == key }?.second ?: key

    private fun sp(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun dir(ctx: Context): File = File(ctx.filesDir, "voice").apply { runCatching { mkdirs() } }

    /** 用户自己传的样本文件（不含内置预设） */
    fun userFile(ctx: Context, pid: String): File? {
        if (pid.isBlank()) return null
        return EXTS.mapNotNull { ext ->
            File(dir(ctx), "$pid.$ext").takeIf { it.isFile && it.length() > 0 }
        }.firstOrNull()
    }

    fun currentPreset(ctx: Context, pid: String): String =
        if (pid.isBlank()) "" else sp(ctx).getString("voice_preset_$pid", "") ?: ""

    fun choosePreset(ctx: Context, pid: String, key: String) {
        if (pid.isBlank()) return
        sp(ctx).edit().putString("voice_preset_$pid", key).apply()
    }

    fun has(ctx: Context, pid: String): Boolean =
        userFile(ctx, pid) != null || currentPreset(ctx, pid).isNotBlank()

    /** 设置页那一行要显示的东西：「自定义 · MP3 · 128 KB」/「内置 · 爱莉希雅」/ 空 */
    fun describe(ctx: Context, pid: String): String {
        userFile(ctx, pid)?.let { f ->
            val kb = f.length() / 1024
            val size = if (kb >= 1024) "${kb / 1024} MB" else "$kb KB"
            return "自定义 · ${f.extension.uppercase()} · $size"
        }
        val p = currentPreset(ctx, pid)
        if (p.isNotBlank()) return "内置 · ${presetName(p)}"
        return ""
    }

    /**
     * 从系统选择器拿到的 uri 存成样本。同名不同格式的先清掉，避免 mp3/wav 并存时读到旧的。
     * 超过 9MB 直接失败（返回 false），不静默截断——截断的样本克隆出来会串音。
     */
    fun save(ctx: Context, pid: String, uri: Uri): Boolean {
        if (pid.isBlank()) return false
        val cr = ctx.contentResolver
        val mime = runCatching { cr.getType(uri) }.getOrNull() ?: ""
        val ext = when {
            mime.contains("wav") || mime.contains("x-wave") -> "wav"
            mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "m4a"
            else -> "mp3"
        }
        val tmp = File(dir(ctx), "$pid.$ext.tmp")
        val ins = runCatching { cr.openInputStream(uri) }.getOrNull() ?: return false
        return try {
            var total = 0L
            var tooBig = false
            ins.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_RAW_BYTES) {
                            tooBig = true
                            break
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
            if (tooBig || total <= 0L) {
                runCatching { tmp.delete() }
                false
            } else {
                removeUser(ctx, pid)
                val ok = tmp.renameTo(File(dir(ctx), "$pid.$ext"))
                if (!ok) runCatching { tmp.delete() }
                // 传了自己的就不再走内置预设
                if (ok) choosePreset(ctx, pid, "")
                ok
            }
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            false
        } finally {
            runCatching { ins.close() }
        }
    }

    fun removeUser(ctx: Context, pid: String) {
        EXTS.forEach { runCatching { File(dir(ctx), "$pid.$it").delete() } }
    }

    /** 清空该人格的声音来源（文件 + 预设） */
    fun clear(ctx: Context, pid: String) {
        removeUser(ctx, pid)
        choosePreset(ctx, pid, "")
    }

    /** 拼成 MiMo 要的 data URL；没有可用样本返回 null */
    /**
     * 样本指纹，用来做 TTS 缓存 key。
     *
     * 换了样本但文本相同的话，缓存必须失效——否则用户改完音色点播放，
     * 听到的还是旧声音，会以为没生效。（这个坑我自己先踩了一次）
     */
    fun fingerprint(ctx: Context, pid: String): String {
        userFile(ctx, pid)?.let { f ->
            return "f${f.length()}_${f.lastModified()}"
        }
        val p = currentPreset(ctx, pid)
        return if (p.isBlank()) "none" else "p_$p"
    }

    fun dataUrl(ctx: Context, pid: String): String? {
        userFile(ctx, pid)?.let { f ->
            if (f.length() > MAX_RAW_BYTES) return null
            val mime = when (f.extension) {
                "wav" -> "audio/wav"
                "m4a" -> "audio/mp4"
                else -> "audio/mpeg"
            }
            return runCatching {
                "data:$mime;base64," + Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
            }.getOrNull()
        }
        val p = currentPreset(ctx, pid)
        if (p.isBlank()) return null
        return runCatching {
            val bytes = ctx.assets.open("voice/$p.mp3").use { it.readBytes() }
            "data:audio/mpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        }.getOrNull()
    }
}
