package app.zhiyi.qq

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 WebSocket 客户端（RFC 6455 客户端一侧）。
 *
 * 为什么不用 OkHttp：整个工程是零第三方依赖的（网络层只有 HttpURLConnection + org.json）。
 * 为什么不用 java.net.http：Android 上没有这个包。所以自己写。
 *
 * 只实现 QQ 机器人网关要用的部分：文本帧、ping/pong、close、分片接收、客户端掩码。
 * 不做扩展协商（permessage-deflate），不发送二进制帧。
 */
class WsClient(
    private val url: String,                       // wss://api.bot.qq.com/websocket/
    private val onText: (String) -> Unit,
    private val onClose: (code: Int, reason: String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    @Volatile private var sock: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var running = false
    private var reader: Thread? = null
    private val rnd = Random()

    /** 连接 + 握手。失败抛异常，调用方决定重试。 */
    fun connect(timeoutMs: Int = 15000) {
        val u = java.net.URI(url)
        val host = u.host
        val secure = u.scheme != "ws"
        val port = if (u.port > 0) u.port else if (secure) 443 else 80
        val path = if (u.path.isNullOrBlank()) "/" else u.path + (u.query?.let { "?$it" } ?: "")

        val raw = Socket()
        raw.connect(java.net.InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = 0                       // 读阻塞，靠 close 打断
        val s: Socket = if (secure) {
            ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true) as javax.net.ssl.SSLSocket).apply { startHandshake() }
        } else raw
        sock = s
        input = s.getInputStream()
        output = s.getOutputStream()

        // ---- 握手 ----
        val keyBytes = ByteArray(16).also { rnd.nextBytes(it) }
        val key = Base64.getEncoder().encodeToString(keyBytes)
        val req = buildString {
            append("GET ").append(path).append(" HTTP/1.1\r\n")
            append("Host: ").append(host).append("\r\n")
            append("Upgrade: websocket\r\n")
            append("Connection: Upgrade\r\n")
            append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            append("Sec-WebSocket-Version: 13\r\n")
            append("User-Agent: zhiyi\r\n\r\n")
        }
        output!!.write(req.toByteArray(Charsets.UTF_8))
        output!!.flush()

        // ---- 读响应头，直到空行 ----
        val head = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input!!.read()
            if (b < 0) throw java.io.IOException("握手时连接被关闭")
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break       // \r\n\r\n
            if (head.length > 16384) throw java.io.IOException("握手响应过长")
        }
        val status = head.lineSequence().firstOrNull().orEmpty()
        if (!status.contains("101")) throw java.io.IOException("握手失败：$status")

        // 校验 Accept（不合格就断开，防中间人）
        val expect = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.UTF_8))
        )
        val got = head.lineSequence().firstOrNull { it.startsWith("Sec-WebSocket-Accept:", true) }
            ?.substringAfter(':')?.trim()
        if (got != null && got != expect) throw java.io.IOException("Sec-WebSocket-Accept 不匹配")

        running = true
        reader = Thread({ readLoop() }, "qq-ws-reader").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        val frag = ByteArrayOutputStream()
        var fragOp = 0
        try {
            while (running) {
                val b0 = input!!.read(); if (b0 < 0) break
                val fin = (b0 and 0x80) != 0
                val op = b0 and 0x0F
                val b1 = input!!.read(); if (b1 < 0) break
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = ((input!!.read() shl 8) or input!!.read()).toLong()
                } else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or input!!.read().toLong() }
                }
                val mask = if (masked) ByteArray(4).also { input!!.readFully(it) } else null
                if (len > 8L * 1024 * 1024) throw java.io.IOException("帧过大：$len")
                val payload = ByteArray(len.toInt())
                input!!.readFully(payload)
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()

                when (op) {
                    0x0 -> {                        // 续帧
                        frag.write(payload)
                        if (fin) {
                            val all = frag.toByteArray(); frag.reset()
                            if (fragOp == 0x1) onText(String(all, Charsets.UTF_8))
                        }
                    }
                    0x1, 0x2 -> {
                        if (fin) { if (op == 0x1) onText(String(payload, Charsets.UTF_8)) }
                        else { fragOp = op; frag.reset(); frag.write(payload) }
                    }
                    0x8 -> {                        // close
                        val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                        val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                        onClose(code, reason); break
                    }
                    0x9 -> sendFrame(0xA, payload)  // ping → pong（原样回）
                    0xA -> { /* pong，忽略 */ }
                }
            }
        } catch (e: Throwable) {
            if (running) onError(e)
        }
    }

    /** 发文本帧。客户端发出的帧必须掩码。 */
    fun send(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)                  // FIN=1
        val n = payload.size
        when {
            n < 126 -> head.write(0x80 or n)
            n < 65536 -> { head.write(0x80 or 126); head.write(n shr 8); head.write(n and 0xFF) }
            else -> {
                head.write(0x80 or 127)
                for (i in 7 downTo 0) head.write(((n.toLong() shr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        head.write(mask)
        val masked = ByteArray(n) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        synchronized(this) {
            out.write(head.toByteArray()); out.write(masked); out.flush()
        }
    }

    fun close(code: Int = 1000, reason: String = "") {
        running = false
        runCatching {
            val p = ByteArrayOutputStream()
            p.write((code shr 8) and 0xFF); p.write(code and 0xFF)
            p.write(reason.toByteArray(Charsets.UTF_8))
            sendFrame(0x8, p.toByteArray())
        }
        runCatching { sock?.close() }
        sock = null; input = null; output = null
    }

    val isOpen: Boolean get() = running && sock != null && sock!!.isConnected
}

/** InputStream 没有 readFully，补一个（Socket 的流只保证"至少读到 1 字节"） */
private fun InputStream.readFully(buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = read(buf, off, buf.size - off)
        if (n < 0) throw java.io.EOFException("流提前结束")
        off += n
    }
}
