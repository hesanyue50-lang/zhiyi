package app.zhiyi.data

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 表情包存储。
 * - 图片放 filesDir/stickers，索引存 kv
 * - 支持从网络接口批量抓取（接口返回 JSON 数组：字符串或 {"url": "..."}）
 * - 单条可删、可整体清空
 */
object StickerStore {

    private const val KEY = "stickers_index"
    private const val MAX_COUNT = 120

    fun dir(ctx: Context): File = File(ctx.filesDir, "stickers").apply { mkdirs() }

    fun list(ctx: Context): List<String> {
        val raw = Db.get(ctx).get(KEY, "")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList()).filter { File(dir(ctx), it).exists() }
    }

    private fun saveIndex(ctx: Context, names: List<String>) {
        Db.get(ctx).put(KEY, JSONArray(names).toString())
    }

    fun delete(ctx: Context, name: String) {
        File(dir(ctx), name).delete()
        saveIndex(ctx, list(ctx).filter { it != name })
    }

    fun clearAll(ctx: Context) {
        runCatching { dir(ctx).deleteRecursively(); dir(ctx).mkdirs() }
        saveIndex(ctx, emptyList())
    }

    fun sizeMb(ctx: Context): Long {
        var sum = 0L
        dir(ctx).walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum / 1024
    }

    /**
     * 从接口抓表情。
     * 接口约定：返回 JSON 数组，元素可以是图片 URL 字符串，或 {"url": "..."}
     * 也兼容 {"data": [...]} 包一层。
     */
    fun crawl(
        ctx: Context,
        source: String,
        want: Int = 40,
        onProgress: (String) -> Unit,
    ): Int {
        if (source.isBlank()) return -1
        onProgress("拉取列表…")
        val body = runCatching { httpGet(source) }.getOrNull() ?: return -2
        val urls = parseUrls(body).take(want)
        if (urls.isEmpty()) return -3

        var ok = 0
        val d = dir(ctx)
        val index = list(ctx).toMutableList()
        urls.forEachIndexed { i, u ->
            onProgress("下载 ${i + 1}/${urls.size}…")
            val name = "st_${System.currentTimeMillis()}_$i"
            val f = File(d, name)
            if (runCatching { download(u, f) }.getOrDefault(false) && f.length() > 0) {
                index.add(name)
                ok++
            } else {
                f.delete()
            }
        }
        // 只保留最近 MAX_COUNT 张，超出的删掉
        while (index.size > MAX_COUNT) {
            val drop = index.removeAt(0)
            File(d, drop).delete()
        }
        saveIndex(ctx, index)
        return ok
    }

    private fun parseUrls(body: String): List<String> {
        val out = ArrayList<String>()
        fun push(s: String) {
            if (!s.startsWith("http")) return
            val path = s.substringBefore('?').lowercase()      // 去掉 ?v8 这类查询串再判断后缀
            val ok = path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".gif") || path.endsWith(".webp") || path.endsWith(".bmp") ||
                path.contains("/emoji/") || path.contains("/icons/") || path.contains("image")
            if (ok) out.add(s)
        }
        runCatching {
            val trimmed = body.trim()
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> org.json.JSONObject(trimmed).let { o ->
                    o.optJSONArray("data") ?: o.optJSONArray("items")
                        ?: o.optJSONArray("urls") ?: o.optJSONArray("results")
                }
            }
            if (arr == null) {
                // 有些接口只返回单个对象 {"url": "..."}
                val one = org.json.JSONObject(trimmed)
                val u = one.optString("url").ifBlank { one.optString("image").ifBlank { one.optString("src") } }
                if (u.isNotBlank()) push(u)
            }
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.opt(i)
                    when (v) {
                        is String -> push(v)
                        is org.json.JSONObject ->
                            push(v.optString("url").ifBlank { v.optString("image").ifBlank { v.optString("src") } })
                    }
                }
            }
        }
        // 兜底：正则捞图片链接（接口格式五花八门）
        if (out.isEmpty()) {
            Regex("https?://[^\\s\"'<>]+\\.(?:png|jpe?g|gif|webp)", RegexOption.IGNORE_CASE)
                .findAll(body).forEach { push(it.value) }
        }
        return out.distinct()
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "ZhiYi/5.3")
        return try {
            if (c.responseCode !in 200..299) "" else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, dest: File): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.setRequestProperty("User-Agent", "ZhiYi/5.3")
        return try {
            if (c.responseCode !in 200..299) return false
            c.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            dest.length() > 512     // 太小多半是错误页
        } catch (e: Exception) {
            false
        } finally {
            c.disconnect()
        }
    }

    /** 清缓存：临时目录 + 表情 */
    fun clearCaches(ctx: Context, keepStickers: Boolean): Long {
        var freed = 0L
        runCatching {
            ctx.cacheDir.listFiles()?.forEach { f ->
                freed += f.length()
                f.deleteRecursively()
            }
        }
        if (!keepStickers) {
            freed += dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            clearAll(ctx)
        }
        return freed / 1024
    }

    fun cacheSizeKb(ctx: Context): Long {
        var sum = 0L
        ctx.cacheDir.walkTopDown().forEach { if (it.isFile) sum += it.length() }
        return sum / 1024
    }
}

/** 消息里表示"图片表情"的前缀 */
const val IMG_PREFIX = "img:"

fun isStickerMessage(content: String) = content.startsWith(IMG_PREFIX)
