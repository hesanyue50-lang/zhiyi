package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * QQ 机器人开放平台 OpenAPI（零依赖：HttpURLConnection + org.json）。
 *
 * 所有方法都按**账号**走：token 是每个 AppID 一份，缓存键带 appId，
 * 否则两个机器人会互相顶掉对方的令牌。
 *
 * 三个坑（官方文档明说的）：
 * 1. getAppAccessToken 失败时 HTTP 也是 200，必须看 body 里的 code；
 * 2. token 有效期 7200 秒，期内重复获取返回同一个值——别每次都去换；
 * 3. 被动回复（带 msg_id）60 分钟内最多 4 次，同一个 msg_id + msg_seq 重复发会失败。
 */
object QqApi {

    private const val BASE = "https://api.bot.qq.com"

    private fun tokenKey(appId: String) = "qq_token_$appId"
    private fun expKey(appId: String) = "qq_token_exp_$appId"

    /** 缓存里还有效的 token（不发起请求） */
    fun cachedToken(db: Db, acc: QqAccount): String {
        val t = db.get(tokenKey(acc.appId), "")
        val exp = db.getNum(expKey(acc.appId), 0)
        return if (t.isNotBlank() && System.currentTimeMillis() < exp) t else ""
    }

    /**
     * 取 access_token。有效期内复用缓存；过期前 60 秒就换新的。
     * 官方说明：过期前 60 秒内再取会拿到新值，旧值在这 60 秒内仍有效。
     */
    fun token(ctx: Context, acc: QqAccount, force: Boolean = false): String {
        val db = Db.get(ctx)
        if (!force) cachedToken(db, acc).let { if (it.isNotBlank()) return it }
        if (acc.appId.isBlank() || acc.secret.isBlank()) throw QqException("这个机器人还没填 AppSecret")

        val body = JSONObject().put("appId", acc.appId).put("clientSecret", acc.secret).toString()
        val res = request("POST", "$BASE/app/getAppAccessToken", null, body)
        val code = res.optInt("code", 0)
        if (code != 0) {
            val msg = res.optString("message", res.optString("msg", "未知错误"))
            throw QqException("取 token 失败（code=$code）：$msg", code)
        }
        val tk = res.optString("access_token", "")
        if (tk.isBlank()) throw QqException("响应里没有 access_token：$res")
        val ttl = res.optString("expires_in", "7200").toLongOrNull() ?: 7200L

        db.put(tokenKey(acc.appId), tk)
        db.put(expKey(acc.appId), (System.currentTimeMillis() + (ttl - 60).coerceAtLeast(60) * 1000).toString())
        return tk
    }

    /** 发一条私聊消息。msgId 非空 = 被动回复；为空 = 主动推送 */
    fun sendC2C(
        ctx: Context,
        acc: QqAccount,
        openId: String,
        content: String,
        msgId: String? = null,
        msgSeq: Int = 1,
    ) {
        val tk = token(ctx, acc)
        val body = JSONObject().put("content", content).put("msg_type", 0)
        if (msgId != null) body.put("msg_id", msgId).put("msg_seq", msgSeq)
        val res = request("POST", "$BASE/v2/users/${enc(openId)}/messages", tk, body.toString())
        val code = res.optInt("code", 0)
        if (code != 0) throw QqException("发送失败（code=$code）：${res.optString("message", "")}", code)
    }

    /** 机器人自身信息，用来验证凭据 */
    fun me(ctx: Context, acc: QqAccount): JSONObject {
        val tk = token(ctx, acc)
        return request("GET", "$BASE/users/@me", tk, null)
    }

    /** WebSocket 网关地址。频控 2 QPM（burst 10），别频繁调用 */
    fun gateway(ctx: Context, acc: QqAccount): String {
        val tk = token(ctx, acc)
        val res = request("GET", "$BASE/gateway", tk, null)
        val u = res.optString("url", "")
        if (u.isBlank()) throw QqException("拿不到网关地址：$res")
        return u
    }

    // ---- 底层 ----
    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    internal fun request(method: String, url: String, token: String?, body: String?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        c.setRequestProperty("Accept", "application/json")
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "QQBot $token")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        c.disconnect()
        return runCatching { JSONObject(text) }.getOrElse {
            throw QqException("HTTP $code：${text.take(200)}")
        }
    }
}

class QqException(message: String, val code: Int = 0) : Exception(message)
