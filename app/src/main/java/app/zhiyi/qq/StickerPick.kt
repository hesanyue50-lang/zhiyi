package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db
import app.zhiyi.data.Prefs
import app.zhiyi.data.StickerStore
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 表情挑选。
 *
 * 顺序：**本地按关键词 → 本地随机 → 联网搜一张 → 放弃**。
 *
 * 为什么要关键词匹配：模型现在可以写 `[表情 开心]`。
 * 本地有对应标签的直接用，比随手抽一张贴题得多；
 * 本地没有就上网按这个词抓一张回来，抓到之后**用关键词命名**，下次就能直接命中。
 */
object StickerPick {

    private const val MAX_ONLINE = 3          // 一次最多联网抓几张
    private val onlineTried = HashMap<String, Long>()

    /** 挑一张。keyword 为空就随机 */
    fun pick(ctx: Context, keyword: String = ""): File? {
        val kw = keyword.trim()
        if (kw.isNotBlank()) {
            localByKeyword(ctx, kw)?.let { return it }
            searchOnline(ctx, kw)?.let { return it }
        }
        return randomLocal(ctx)
    }

    /** 记一下，避免连续两次发同一张 */
    fun remember(ctx: Context, name: String) {
        Db.get(ctx).put("qq_last_sticker", name)
    }

    // ---------------------------------------------------------------- 本地

    private fun randomLocal(ctx: Context): File? {
        val names = runCatching { StickerStore.list(ctx) }.getOrDefault(emptyList())
        if (names.isEmpty()) return null
        val db = Db.get(ctx)
        val last = db.get("qq_last_sticker", "")
        val pool = if (names.size > 1) names.filter { it != last } else names
        val pick = pool.random()
        val f = File(StickerStore.dir(ctx), pick)
        return if (f.exists() && f.length() > 0 && f.length() < 2 * 1024 * 1024) f else null
    }

    /** 文件名里带关键词的优先（联网抓回来的按关键词命名） */
    private fun localByKeyword(ctx: Context, kw: String): File? {
        val names = runCatching { StickerStore.list(ctx) }.getOrDefault(emptyList())
        val hit = names.filter { it.contains(kw, ignoreCase = true) }
        if (hit.isEmpty()) return null
        val f = File(StickerStore.dir(ctx), hit.random())
        return if (f.exists() && f.length() > 0) f else null
    }

    // ---------------------------------------------------------------- 联网

    /**
     * 按关键词联网抓一张。
     * 用用户自己配的表情接口（`cfg.stickerSource`）——
     * 拼法：带 `{kw}` 占位符的替换它；否则当查询参数追加。
     * 接口不支持关键词时会抓到不相关的东西，所以下一个关键词最多试一次、抓 1 张。
     */
    private fun searchOnline(ctx: Context, kw: String): File? {
        val src = Prefs.get(ctx).data.stickerSource.trim()
        if (src.isBlank()) return null
        // 同一个词 10 分钟内只试一次，免得每句话都去请求
        val now = System.currentTimeMillis()
        onlineTried[kw]?.let { if (now - it < 10 * 60_000) return null }

        val url = when {
            src.contains("{kw}") -> src.replace("{kw}", enc(kw))
            src.contains("?") -> "$src&keyword=${enc(kw)}"
            else -> "$src?keyword=${enc(kw)}"
        }
        val body = runCatching { httpGet(url) }.getOrNull() ?: return null
        val urls = parseImageUrls(body).take(MAX_ONLINE)
        if (urls.isEmpty()) return null

        val d = StickerStore.dir(ctx)
        for ((i, u) in urls.withIndex()) {
            // 名字里带上关键词，下次本地就能直接命中
            val name = "st_${kw.take(8)}_${now}_$i"
            val f = File(d, name)
            if (runCatching { download(u, f) }.getOrDefault(false) && f.length() > 0 && f.length() < 2 * 1024 * 1024) {
                runCatching { StickerStore.adopt(ctx, name) }
                onlineTried[kw] = now
                return f
            }
            f.delete()
        }
        onlineTried[kw] = now
        return null
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    private fun httpGet(u: String): String? {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
        return runCatching {
            c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
        }.getOrNull()
    }

    private fun download(u: String, out: File): Boolean {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        return runCatching {
            c.inputStream.use { inp ->
                out.outputStream().use { o -> inp.copyTo(o) }
            }
            c.disconnect()
            out.length() > 0
        }.getOrDefault(false)
    }

    /** 从 HTML / JSON 里扒图片链接 */
    private fun parseImageUrls(body: String): List<String> {
        val out = ArrayList<String>()
        val re = Regex("https?://[^\"'\\s\\\\<>()]+")
        for (m in re.findAll(body)) {
            val s = m.value
            val path = s.substringBefore('?').lowercase()
            val ok = path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp")
            if (ok && !out.contains(s)) out.add(s)
            if (out.size >= 12) break
        }
        return out
    }
}
