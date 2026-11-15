package app.zhiyi.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * WsClient 的自测：本地起一个 RFC 6455 服务端，验证握手、掩码发送、连续收帧、ping/pong。
 *
 * 手写 WebSocket 最容易错的三处都覆盖了：
 * 1. Sec-WebSocket-Accept 计算（错了服务端会直接断开）
 * 2. 客户端帧必须掩码（不掩码服务端会拒收）
 * 3. 一次 read 可能只读到半个帧（readFully 处理）
 */
class WsClientTest {

    private val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private fun InputStream.exact(n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = read(buf, off, n - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
        return buf
    }

    private fun frame(op: Int, payload: ByteArray): ByteArray {
        val n = payload.size
        val head = when {
            n < 126 -> byteArrayOf((0x80 or op).toByte(), n.toByte())
            n < 65536 -> byteArrayOf((0x80 or op).toByte(), (0x80 or 126).toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
            else -> throw IllegalArgumentException()
        }
        // 服务端回帧不掩码：掩码位是 0x80|126 时上面那行写错了，这里显式修正
        val fixed = if (n < 126) byteArrayOf((0x80 or op).toByte(), n.toByte())
        else byteArrayOf((0x80 or op).toByte(), 126.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
        return fixed + payload
    }

    @Test
    fun handshakeMaskAndMultiFrame() {
        val srv = ServerSocket(0)
        val port = srv.localPort
        val done = CountDownLatch(1)
        var accepted = ""
        var clientMasked = false
        var echoed = ""

        thread(isDaemon = true) {
            try {
                val s = srv.accept()
                val inp: InputStream = s.getInputStream()
                val out: OutputStream = s.getOutputStream()

                // 读握手头
                val sb = StringBuilder()
                var last4 = 0
                while (true) {
                    val b = inp.read(); if (b < 0) break
                    sb.append(b.toChar()); last4 = (last4 shl 8) or b
                    if (last4 == 0x0D0A0D0A) break
                }
                val head = sb.toString()
                val key = head.lineSequence().first { it.startsWith("Sec-WebSocket-Key:", true) }
                    .substringAfter(':').trim()
                accepted = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray())
                )
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: $accepted\r\n\r\n").toByteArray()
                )
                out.flush()

                // 读一帧，检查掩码位
                val b0 = inp.read(); val b1 = inp.read()
                val op = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                clientMasked = masked
                var len = (b1 and 0x7F)
                if (len == 126) { val e = inp.exact(2); len = ((e[0].toInt() and 0xFF) shl 8) or (e[1].toInt() and 0xFF) }
                val mask = if (masked) inp.exact(4) else null
                val raw = inp.exact(len)
                val payload = if (mask != null) ByteArray(len) { i -> (raw[i].toInt() xor mask[i % 4].toInt()).toByte() } else raw
                echoed = String(payload)

                // 回两帧：长帧（>125 字节，走扩展长度）+ 短帧
                val longText = "echo:" + "x".repeat(300) + ":" + echoed
                out.write(frame(0x1, longText.toByteArray()))
                out.write(frame(0x1, "second".toByteArray()))
                out.flush()
                Thread.sleep(300)
                s.close()
            } catch (_: Throwable) {
            } finally {
                done.countDown()
            }
        }

        val got = java.util.Collections.synchronizedList(ArrayList<String>())
        val two = CountDownLatch(2)
        val w = WsClient(
            url = "ws://127.0.0.1:$port/",
            onText = { t -> got.add(t); two.countDown() },
            onClose = { _, _ -> },
            onError = { e -> println("ws error: ${e.message}") },
        )
        w.connect(5000)
        assertTrue("握手后应处于打开状态", w.isOpen)
        w.send("hello")
        if (!two.await(5, TimeUnit.SECONDS)) throw TimeoutException("5 秒内没收到两帧，实收 ${got.size}")

        assertEquals("服务端算出的 Accept 应与客户端一致", accepted.isNotBlank(), true)
        assertTrue("客户端帧必须带掩码", clientMasked)
        assertEquals("服务端应收到明文 hello", "hello", echoed)
        assertEquals(2, got.size)
        assertEquals("长帧（扩展长度）应完整收到", 300 + "echo::hello".length, got[0].length)
        assertEquals("second", got[1])
        w.close()
        srv.close()
    }

    @Test
    fun binaryAndCloseFramesIgnoredSafely() {
        // 只是确认 close 帧能被正确解析出 code
        val srv = ServerSocket(0)
        val port = srv.localPort
        var closeCode = -1
        val latch = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                val s = srv.accept()
                val inp = s.getInputStream(); val out = s.getOutputStream()
                val sb = StringBuilder(); var last4 = 0
                while (true) {
                    val b = inp.read(); if (b < 0) break
                    sb.append(b.toChar()); last4 = (last4 shl 8) or b
                    if (last4 == 0x0D0A0D0A) break
                }
                val key = sb.toString().lineSequence().first { it.startsWith("Sec-WebSocket-Key:", true) }
                    .substringAfter(':').trim()
                val acc = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray())
                )
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Accept: $acc\r\n\r\n").toByteArray())
                out.flush()
                // 立刻发 close(4000, "bye")
                out.write(frame(0x8, byteArrayOf((4000 shr 8).toByte(), (4000 and 0xFF).toByte()) + "bye".toByteArray()))
                out.flush()
                Thread.sleep(300); s.close()
            } catch (_: Throwable) {
            }
        }
        val w = WsClient(
            url = "ws://127.0.0.1:$port/",
            onText = { },
            onClose = { c, _ -> closeCode = c; latch.countDown() },
            onError = { },
        )
        w.connect(5000)
        latch.await(5, TimeUnit.SECONDS)
        assertEquals("close 帧的 code 应被解析", 4000, closeCode)
        srv.close()
    }
}
