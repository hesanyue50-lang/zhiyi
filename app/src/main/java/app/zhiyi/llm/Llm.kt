package app.zhiyi.llm

import app.zhiyi.data.Provider
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LlmException(msg: String) : Exception(msg)

/**
 * 零依赖的 OpenAI 兼容客户端（HttpURLConnection + org.json）。
 * 支持流式 SSE、非流式、embeddings。DeepSeek / 硅基流动 / Kimi / OpenRouter 全通。
 */
class Llm(private val p: Provider) {

    companion object {
        /**
         * 思考强度：low / medium / high，空串=不传这个字段。
         * OpenAI 兼容网关（new-api / oneapi / 官方）大多认 `reasoning_effort`；
         * 不认的网关会忽略这个字段，无副作用。
         */
        @Volatile
        var effort: String = "low"

        /** 哪些 base 已证实不接受 reasoning_effort（智谱 11133 / 部分 oneapi 直接 503 no_healthy_account） */
        private val effortBlocked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /**
         * 只有「参数本身被拒」才算：错误串里一定带 "HTTP 400"，所以不能靠状态码判断，
         * 否则模型名不存在 / 上下文超长 / 把图给纯文本模型 这些 400 都会白发一次请求。
         */
        fun looksParamReject(msg: String): Boolean {
            val m = msg.lowercase()
            return m.contains("parameter") || m.contains("invalid argument") ||
                m.contains("unsupported") || m.contains("rejected") ||
                m.contains("11133") || m.contains("1214")
        }

        fun isEffortBlocked(base: String) = effortBlocked.containsKey(base)
        fun markEffortBlocked(base: String) { effortBlocked[base] = true }
    }

    private fun url(path: String): String {
        val b = p.base.trim().trimEnd('/')
        return if (b.endsWith("/v1") || b.contains("/v1/")) "$b$path" else "$b/v1$path"
    }

    private fun checkKey() {
        val k = p.key.trim()
        if (!k.matches(Regex("^[\\x21-\\x7E]+$")))
            throw LlmException("Key 里有非法字符（全角符号或换行）")
    }

    private fun req(path: String): HttpURLConnection {
        val c = URL(url(path)).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 45000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "text/event-stream")
        checkKey()
        // 异常原文里会带整串 Bearer Key，所以这里不放 key 到异常里
        c.setRequestProperty("Authorization", "Bearer " + p.key.trim())
        return c
    }

    private fun bodyObj(
        model: String, messages: List<Pair<String, String>>, images: List<String>,
        temp: Double, maxTok: Int, stream: Boolean, effortValue: String,
    ): JSONObject {
        val arr = JSONArray()
        messages.forEachIndexed { i, (r, c) ->
            val isLastUserImg = i == messages.size - 1 && r == "user" && images.isNotEmpty()
            if (isLastUserImg) {
                val ca = JSONArray()
                ca.put(JSONObject().put("type", "text").put("text", c))
                images.forEach { uri ->
                    ca.put(JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", uri)))
                }
                arr.put(JSONObject().put("role", r).put("content", ca))
            } else {
                arr.put(JSONObject().put("role", r).put("content", c))
            }
        }
        val o = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", temp)
            .put("max_tokens", maxTok)
            .put("stream", stream)
        // 思考强度：本次请求自己的值，不读全局静态，避免并发串台
        if (effortValue.isNotBlank()) o.put("reasoning_effort", effortValue)
        return o
    }

    /**
     * 有些网关会把推理过程用 <think:hash>...</think> 或未闭合的 <think:hash> 塞进 content。
     * 这里统一剥掉，保证只留台词/正文。
     */
    private fun stripThink(raw: String): String {
        var t = raw
        // 1) 闭合形式整段删（<think:abc>...</think> / <think>...</think>）
        t = Regex("""<think(?::[\w-]+)?>[\s\S]*?</think(?::[\w-]+)?>""", RegexOption.IGNORE_CASE).replace(t, "")
        // 2) 未闭合：找到最后一个 <think...> 标签，丢掉它之前的内容（含标签本身）
        val m = Regex("""<think(?::[\w-]+)?>""", RegexOption.IGNORE_CASE).findAll(t).lastOrNull()
        // 未闭合时：标签之后是「思考」，必须丢弃；标签之前才是正文。
        // （原来保留标签之后 = 把内心推理当台词返回，还会落库污染记忆）
        if (m != null) t = t.substring(0, m.range.first)
        // 3) 残留标签
        t = Regex("""</?think(?::[\w-]+)?>""", RegexOption.IGNORE_CASE).replace(t, "")
        return t.trim()
    }

    fun chat(
        model: String,
        messages: List<Pair<String, String>>,
        temp: Double = 0.85,
        maxTok: Int = 500,
        stream: Boolean = false,
        onDelta: ((String) -> Unit)? = null,
        cancel: (() -> Boolean)? = null,
        images: List<String> = emptyList(),   // data URI 列表，只挂到最后一条 user 消息上
    ): String {
        // 本次请求用哪个思考强度，全程只用局部变量：effort 是 companion 的静态值，
        // 多个请求并发时会互相覆盖（A 的 finally 会把 B 的设置冲掉）。
        val want = if (isEffortBlocked(p.base)) "" else effort
        // 已经往外吐过字就不能重试：调用方会把两遍内容拼起来（前半句 + 完整一遍），还双倍计费
        val emitted = java.util.concurrent.atomic.AtomicBoolean(false)
        val guardedDelta: ((String) -> Unit)? = onDelta?.let { cb ->
            { d: String -> emitted.set(true); cb(d) }
        }
        return try {
            chatOnce(model, messages, temp, maxTok, stream, guardedDelta, cancel, images, want)
        } catch (e: LlmException) {
            val msg = e.message ?: ""
            // 智谱 11133 / oneapi no_healthy_account 都是「参数被拒」，去掉 reasoning_effort 再试一次
            if (want.isNotBlank() && !emitted.get() && looksParamReject(msg)) {
                android.util.Log.w("ZhiYi", "reasoning_effort 被拒(降级重试) ${p.base}: ${msg.take(120)}")
                markEffortBlocked(p.base)
                chatOnce(model, messages, temp, maxTok, stream, onDelta, cancel, images, "")
            } else {
                throw e
            }
        }
    }

    private fun chatOnce(
        model: String,
        messages: List<Pair<String, String>>,
        temp: Double,
        maxTok: Int,
        stream: Boolean,
        onDelta: ((String) -> Unit)?,
        cancel: (() -> Boolean)?,
        images: List<String>,
        effortValue: String = "",
    ): String {
        if (p.key.isBlank()) throw LlmException("API Key 还没填")
        // req() 里的 URL() 会抛 MalformedURLException（base 少写 http:// 时），
        // 那不是 LlmException，包在 try 外会直接冒到 UI、让整条 provider 回退链失效
        var conn: java.net.HttpURLConnection? = null
        try {
            conn = req("/chat/completions")
            val c = conn!!
            c.outputStream.use { it.write(bodyObj(model, messages, images, temp, maxTok, stream, effortValue).toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching {
                    c.errorStream?.use { ins ->
                        val b = ByteArray(800); val n = ins.read(b)
                        if (n > 0) String(b, 0, n) else ""
                    } ?: ""
                }.getOrDefault("")
                // 带上诊断：上游 11133「参数有误」光看 body 猜不出来，
                // 直接标出这次请求的形状（有没有 user / 带没带 effort / 几张图）
                val diag = "｜诊断 model=$model msgs=${messages.size}" +
                    " user=${messages.count { it.first == "user" }}" +
                    " effort=${effortValue.ifBlank { "无" }} img=${images.size}"
                throw LlmException("HTTP $code ${humanize(code)} ${err}$diag")
            }
            if (!stream) {
                val txt = c.inputStream.bufferedReader().readText()
                val o = JSONObject(txt)
                val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                val content = msg?.optString("content", "") ?: ""
                val cleaned = stripThink(content)
                if (cleaned.isBlank()) {
                    val rc = msg?.optString("reasoning_content", "") ?: ""
                    android.util.Log.w("ZhiYi", "llm content blank after strip (raw ${content.length} chars, reasoning ${rc.length}), model=$model")
                } else if (cleaned.length != content.length) {
                    android.util.Log.i("ZhiYi", "stripped think: ${content.length} -> ${cleaned.length}")
                }
                return cleaned
            }
            val sb = StringBuilder()
            var inThink = false          // 状态机：处于 <think> 内就丢弃
            var pending = StringBuilder() // 暂存可能是标签前缀的内容
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    if (cancel?.invoke() == true) break
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val obj = runCatching { JSONObject(payload) }.getOrNull()
                    if (obj == null) continue
                    // 流中错误帧要暴露，不能静默成空
                    obj.optJSONObject("error")?.let { err ->
                        throw LlmException("网关错误: " + err.optString("message", err.toString()))
                    }
                    val delta = runCatching {
                        obj.optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("delta")?.optString("content", "") ?: ""
                    }.getOrDefault("")
                    if (delta.isEmpty()) continue

                    // 网关按 token 流式输出时，标签会被切开：["你好<thi", "nk:ab>推理</think>我在"]。
                    // 单 chunk 内 indexOf("<think") 找不到 -> 标签和整段推理原样漏进正文。
                    // 处理前先拼上上一块留下的尾巴；处理后再把「可能是标签前缀的尾部」存回去。
                    var text = pending.toString() + delta
                    pending.setLength(0)
                    run {
                        val lt = text.lastIndexOf('<')
                        if (lt >= 0 && text.indexOf('>', lt) < 0 && text.length - lt <= 9) {
                            pending.append(text.substring(lt))
                            text = text.substring(0, lt)
                        }
                    }

                    // 状态机过滤 <think ...> ... </think ...>
                    var i = 0
                    val out = StringBuilder()
                    while (i < text.length) {
                        if (!inThink) {
                            val idx = text.indexOf("<think", i, ignoreCase = true)
                            if (idx < 0) { out.append(text, i, text.length); i = text.length }
                            else {
                                out.append(text, i, idx)
                                val gt = text.indexOf('>', idx)
                                if (gt < 0) { inThink = true; i = text.length }   // 标签跨块，先进入静默
                                else { inThink = true; i = gt + 1 }
                            }
                        } else {
                            val close = text.indexOf("</think", i, ignoreCase = true)
                            if (close < 0) { i = text.length }                 // 整块都在思考里，丢掉
                            else {
                                val gt = text.indexOf('>', close)
                                if (gt < 0) { i = text.length } else { inThink = false; i = gt + 1 }
                            }
                        }
                    }
                    val piece = out.toString()
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        onDelta?.invoke(piece)
                    }
                }
            }
            if (pending.isNotEmpty() && !inThink) {
                sb.append(pending)
                onDelta?.invoke(pending.toString())
            }
            return sb.toString()
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException("${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** GET /models —— 用真实 Key 拉这个接口支持的模型名 */
    fun models(): List<String> {
        if (p.key.isBlank()) throw LlmException("先填 API Key")
        val c = URL(url("/models")).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 8000
        c.readTimeout = 45000
        c.setRequestProperty("Authorization", "Bearer ${p.key.trim()}")
        return try {
            val code = c.responseCode
            if (code !in 200..299) throw LlmException("HTTP $code ${humanize(code)}")
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val arr = o.optJSONArray("data") ?: o.optJSONArray("models")
            val out = ArrayList<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val id = arr.optJSONObject(i)?.optString("id", "")
                        ?: arr.optJSONObject(i)?.optString("name", "") ?: ""
                    if (id.isNotBlank()) out.add(id)
                }
            }
            out
        } catch (e: LlmException) {
            throw e
        } catch (e: Exception) {
            throw LlmException(e.message ?: "拉取模型失败")
        } finally {
            c.disconnect()
        }
    }

    fun embed(text: String, model: String): FloatArray? {
        if (model.isBlank() || p.key.isBlank()) return null
        val conn = req("/embeddings")
        return try {
            val body = JSONObject().put("model", model).put("input", text)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val a = o.optJSONArray("data")?.optJSONObject(0)?.optJSONArray("embedding") ?: return null
            FloatArray(a.length()) { a.optDouble(it).toFloat() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun humanize(code: Int): String = when (code) {
        401 -> "Key 无效或未授权（检查 Key 是否填对）"
        402 -> "余额不足"
        404 -> "地址或模型名不对"
        429 -> "请求太频繁"
        in 500..599 -> "服务端错误"
        else -> "请求失败"
    }
}
