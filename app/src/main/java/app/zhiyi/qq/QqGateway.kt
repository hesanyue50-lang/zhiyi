package app.zhiyi.qq

import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicInteger

/**
 * QQ 机器人网关（WebSocket 长连接）。
 *
 * 协议要点：
 * - 连上先收 op=10 Hello，里面给 heartbeat_interval（实测 45s）
 * - 发 op=2 Identify：token 形如 "QQBot {AccessToken}"、intents 位图、shard [0,1]
 * - 收 op=0 Ready，里面给 session_id
 * - 按 interval 发 op=1 心跳，d 填**收到的最新 s**（首次为 null），收 op=11 ACK
 * - 断线用 op=6 Resume（带 session_id + seq），网关补发期间事件；4009 才能 resume，
 *   其他错误码必须重新 Identify
 * - op=7 Reconnect 主动重连；op=9 Invalid Session 会话失效
 *
 * 官方提醒：每机器人 1000 次/天 session 启动限制，重连别太猛。
 */
class QqGateway(
    private val ctx: android.content.Context,
    private val acc: QqAccount,
    private val onEvent: (QqAccount, type: String, data: JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    companion object {
        /** C2C 私聊 + 群 @ 都属于这一位 */
        const val INTENT_GROUP_AND_C2C = 1 shl 25
    }

    @Volatile private var ws: WsClient? = null
    @Volatile private var sessionId = ""
    @Volatile private var lastSeq: Int = 0
    @Volatile private var hb: Timer? = null
    @Volatile private var running = false
    @Volatile private var canResume = false
    private val attempt = AtomicInteger(0)

    /** 启动连接（幂等：已在跑就忽略） */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        attempt.set(0)
        connect(resume = false)
    }

    @Synchronized
    fun stop() {
        running = false
        hb?.cancel(); hb = null
        ws?.close(); ws = null
        canResume = false
        onState("已断开")
    }

    fun isRunning(): Boolean = running

    // ---------------------------------------------------------------- 连接
    private fun connect(resume: Boolean) {
        if (!running) return
        Thread({
            try {
                onState(if (resume) "重连中…" else "连接中…")
                val url = QqApi.gateway(ctx, acc)
                val token = QqApi.token(ctx, acc, force = attempt.get() > 0)
                lastSeq = 0

                val client = WsClient(
                    url = url,
                    onText = { handle(it) },
                    onClose = { code, reason ->
                        hb?.cancel(); hb = null
                        if (running) {
                            onState("连接关闭（$code ${reason}）")
                            scheduleReconnect()
                        }
                    },
                    onError = { e ->
                        hb?.cancel(); hb = null
                        if (running) {
                            onState("连接出错：${e.message}")
                            scheduleReconnect()
                        }
                    },
                )
                ws = client
                client.connect(15000)
                sendIdentify(token)          // Hello 里带了 interval，也在 identify 前先发
            } catch (e: Throwable) {
                onState("连不上：${e.message}")
                scheduleReconnect()
            }
        }, "qq-gw-connect").apply { isDaemon = true }.start()
    }

    private fun sendIdentify(token: String) {
        val d = JSONObject()
            .put("token", "QQBot $token")
            .put("intents", INTENT_GROUP_AND_C2C)
            .put("shard", org.json.JSONArray().put(0).put(1))
            .put("properties", JSONObject())
        ws?.send(JSONObject().put("op", 2).put("d", d).toString())
        onState("鉴权中…")
    }

    private fun scheduleReconnect() {
        if (!running) return
        val n = attempt.incrementAndGet()
        val wait = (2000L * n).coerceAtMost(30000L)     // 2s,4s,6s…最多 30s
        onState("${wait / 1000} 秒后重连（第 $n 次）")
        Thread {
            Thread.sleep(wait)
            if (running) connect(resume = canResume)
        }.apply { isDaemon = true }.start()
    }

    // ---------------------------------------------------------------- 收消息
    private fun handle(text: String) {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        val op = obj.optInt("op", -1)
        // 心跳/事件都可能带 s，记最新的
        if (obj.has("s") && !obj.isNull("s")) lastSeq = obj.optInt("s", lastSeq)

        when (op) {
            10 -> {
                val interval = obj.optJSONObject("d")?.optLong("heartbeat_interval", 45000) ?: 45000
                startHeartbeat(interval)
                onState("已连接，等待鉴权")
            }
            11 -> { /* ACK，忽略 */ }
            0 -> {
                val t = obj.optString("t", "")
                val d = obj.optJSONObject("d") ?: JSONObject()
                when (t) {
                    "READY" -> {
                        sessionId = d.optString("session_id", "")
                        canResume = true
                        attempt.set(0)
                        onState("已上线")
                    }
                    "RESUMED" -> {
                        canResume = true
                        attempt.set(0)
                        onState("已恢复")
                    }
                    else -> runCatching { onEvent(acc, t, d) }
                }
            }
            7 -> { onState("服务端要求重连"); ws?.close(); scheduleReconnect() }
            9 -> {
                // 会话失效：必须重新 Identify，不能 resume
                canResume = false
                sessionId = ""
                onState("会话失效，重新鉴权")
                runCatching {
                    val token = QqApi.token(ctx, acc, force = true)
                    sendIdentify(token)
                }
            }
        }
    }

    private fun startHeartbeat(interval: Long) {
        hb?.cancel()
        hb = Timer("qq-hb", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    runCatching {
                        val d = if (lastSeq > 0) lastSeq else JSONObject.NULL
                        ws?.send(JSONObject().put("op", 1).put("d", d).toString())
                    }
                }
            }, interval, interval)
        }
    }
}
