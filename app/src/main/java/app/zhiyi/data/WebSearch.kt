package app.zhiyi.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 轻量联网搜索（免 key）。
 * 用 DuckDuckGo Lite 的 HTML 页，解析标题 + 摘要。
 * 设计约束：一次搜索必须在 4 秒内结束（单条回答总预算 20 秒）。
 */
object WebSearch {

    private const val CONNECT_MS = 1800
    private const val READ_MS = 2200

    /** 判断这句话是否值得联网查一下（宁可少搜，不要每条都搜） */
    fun shouldSearch(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        // 明确要求
        if (Regex("查一下|查查|搜一下|搜搜|搜搜看|帮我查|帮我搜|上网查|联网查").containsMatchIn(t)) return true
        // 时效性 / 事实性提问
        val timey = Regex("最新|今天|昨天|今晚|刚才|现在.*(几点|多少|怎么样)|新闻|热搜|天气|股价|汇率|价格|多少钱")
        val factual = Regex("是谁|是什么|在哪里|哪里人|什么时候|哪一年|多少岁|几年|有什么新|发布了|上映|比赛|比分")
        return (timey.containsMatchIn(t) || factual.containsMatchIn(t)) && t.length >= 6
    }

    /** 返回若干条「标题：摘要」，失败返回 null（不阻塞主流程） */
    fun search(query: String, max: Int = 3): String? {
        val q = query.trim().take(60)
        if (q.isBlank()) return null
        return try {
            val body = fetch("https://lite.duckduckgo.com/lite/", "q=" + URLEncoder.encode(q, "UTF-8")) ?: return null
            parse(body, max)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("ZhiYi", "search failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun fetch(url: String, postBody: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = CONNECT_MS
        c.readTimeout = READ_MS
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        return try {
            c.outputStream.use { it.write(postBody.toByteArray()) }
            if (c.responseCode !in 200..299) return null
            c.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun parse(html: String, max: Int): String? {
        // 按 <tr> 结果块切分，块内再取标题+摘要，避免两个自由集合按下标错位
        val blocks = html.split(Regex("<tr[^>]*>", RegexOption.IGNORE_CASE))
        val out = StringBuilder()
        var used = 0
        for (b in blocks) {
            if (used >= max) break
            val title = Regex("""class=['"]result-link['"][^>]*>(.*?)</a>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(b)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            val snip = Regex("""class=['"]result-snippet['"][^>]*>(.*?)</td>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .find(b)?.groupValues?.get(1)?.let { clean(it) }.orEmpty()
            if (title.isBlank() && snip.isBlank()) continue
            out.append("· ").append(title.take(60))
            if (snip.isNotBlank()) out.append("：").append(snip.take(120))
            out.append("\n")
            used++
        }
        return out.toString().trim().ifBlank { null }
    }

    private fun clean(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
