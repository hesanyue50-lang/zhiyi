package app.zhiyi.qq

import android.content.Context
import android.util.Base64
import app.zhiyi.data.Db
import org.json.JSONObject

/**
 * QQ 富媒体：表情包 / 图片。
 *
 * 两步走，不能一步到位：
 * 1. 先把图片传到 QQ（`/v2/users/{openid}/files`），拿到 `file_info`；
 * 2. 再用 `msg_type=7` + `media.file_info` 发出去。
 *
 * 坑：
 * - `file_info` 有 ttl，且**单聊和群聊的接口不互通**，别缓存太久也别跨场景用；
 * - 上传是 base64 内联（`file_data`），图别太大，压到几百 KB 以内；
 * - 被动回复的 4 次额度是**每条原消息**算的，发一张图也用一次。
 */
object QqMedia {

    private const val BASE = "https://api.bot.qq.com"

    /** 图片类型：1=图片 2=视频 3=语音 4=文件 */
    private const val FILE_TYPE_IMAGE = 1

    /**
     * 上传一张图，返回 file_info。失败抛 QqException。
     * 超过 1MB 的图建议先压——这里不替你压，因为表情包通常都很小。
     */
    fun uploadImage(ctx: Context, acc: QqAccount, openId: String, bytes: ByteArray, name: String = "sticker.png"): String {
        if (bytes.isEmpty()) throw QqException("图片是空的")
        val tk = QqApi.token(ctx, acc)
        val body = JSONObject()
            .put("file_type", FILE_TYPE_IMAGE)
            .put("file_data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("file_name", name)
            .put("srv_send_msg", false)
        val res = QqApi.request("POST", "$BASE/v2/users/${java.net.URLEncoder.encode(openId, "UTF-8")}/files", tk, body.toString())
        val code = res.optInt("code", 0)
        if (code != 0) {
            throw QqException("上传图片失败（code=$code）：${res.optString("message", "")}", code)
        }
        val info = res.optString("file_info", "")
        if (info.isBlank()) throw QqException("上传成功但没拿到 file_info：$res")
        return info
    }

    /** 发一张已经上传好的图。msgId 非空 = 被动回复 */
    fun sendImage(ctx: Context, acc: QqAccount, openId: String, fileInfo: String, msgId: String? = null, msgSeq: Int = 1) {
        val tk = QqApi.token(ctx, acc)
        val body = JSONObject()
            .put("msg_type", 7)
            .put("media", JSONObject().put("file_info", fileInfo))
        if (msgId != null) body.put("msg_id", msgId).put("msg_seq", msgSeq)
        val res = QqApi.request("POST", "$BASE/v2/users/${java.net.URLEncoder.encode(openId, "UTF-8")}/messages", tk, body.toString())
        val code = res.optInt("code", 0)
        if (code != 0) {
            throw QqException("发图失败（code=$code）：${res.optString("message", "")}", code)
        }
    }

    /**
     * 从本地表情库随机挑一张，上传并发出去。
     * 一个都没有就返回 false，调用方安静跳过（没表情包不是错误）。
     */
    fun sendRandomSticker(ctx: Context, acc: QqAccount, openId: String, msgId: String?, msgSeq: Int): Boolean {
        val names = runCatching { app.zhiyi.data.StickerStore.list(ctx) }.getOrDefault(emptyList())
        if (names.isEmpty()) return false
        val db = Db.get(ctx)
        // 避免连着两次发同一张
        val last = db.get("qq_last_sticker", "")
        val pool = if (names.size > 1) names.filter { it != last } else names
        val pick = pool.random()
        val f = java.io.File(app.zhiyi.data.StickerStore.dir(ctx), pick)
        if (!f.exists()) return false
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return false
        // QQ 对富媒体有大小限制，太大的直接跳过而不是报错
        if (bytes.size > 2 * 1024 * 1024) return false
        val info = uploadImage(ctx, acc, openId, bytes, pick)
        sendImage(ctx, acc, openId, info, msgId, msgSeq)
        db.put("qq_last_sticker", pick)
        return true
    }
}
