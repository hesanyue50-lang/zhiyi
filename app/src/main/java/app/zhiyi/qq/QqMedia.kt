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

    /** 群聊发图。群和私聊的 media 接口不互通，file_info 也不能跨用 */
    fun sendGroupImage(ctx: Context, acc: QqAccount, groupOpenId: String, fileInfo: String, msgId: String? = null, msgSeq: Int = 1) {
        val tk = QqApi.token(ctx, acc)
        val body = JSONObject().put("msg_type", 7).put("media", JSONObject().put("file_info", fileInfo))
        if (msgId != null) body.put("msg_id", msgId).put("msg_seq", msgSeq)
        val res = QqApi.request(
            "POST", "$BASE/v2/groups/${java.net.URLEncoder.encode(groupOpenId, "UTF-8")}/messages",
            tk, body.toString()
        )
        val code = res.optInt("code", 0)
        if (code != 0) throw QqException("群发图失败（code=$code）：${res.optString("message", "")}", code)
    }

    /**
     * 挑一张表情并发出去（本地优先，本地没有了就联网搜关键词）。
     * 一个都没有就返回 false，调用方安静跳过——没表情包不是错误。
     */
    fun sendRandomSticker(ctx: Context, acc: QqAccount, openId: String, msgId: String?, msgSeq: Int, keyword: String = ""): Boolean {
        val f = StickerPick.pick(ctx, keyword) ?: return false
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return false
        if (bytes.size > 2 * 1024 * 1024) return false
        val info = uploadImage(ctx, acc, openId, bytes, f.name)
        sendImage(ctx, acc, openId, info, msgId, msgSeq)
        StickerPick.remember(ctx, f.name)
        return true
    }

    /** 上传到群（群和私聊的 files 接口不互通，必须分开传） */
    fun uploadGroupImage(ctx: Context, acc: QqAccount, groupOpenId: String, bytes: ByteArray, name: String = "sticker.png"): String {
        if (bytes.isEmpty()) throw QqException("图片是空的")
        val tk = QqApi.token(ctx, acc)
        val body = JSONObject()
            .put("file_type", FILE_TYPE_IMAGE)
            .put("file_data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("file_name", name)
            .put("srv_send_msg", false)
        val res = QqApi.request(
            "POST",
            "$BASE/v2/groups/${java.net.URLEncoder.encode(groupOpenId, "UTF-8")}/files",
            tk, body.toString()
        )
        val code = res.optInt("code", 0)
        if (code != 0) throw QqException("群上传图片失败（code=$code）：${res.optString("message", "")}", code)
        return res.optString("file_info", "").also { if (it.isBlank()) throw QqException("没拿到 file_info") }
    }

    /** 群里发一张表情 */
    fun sendRandomStickerGroup(ctx: Context, acc: QqAccount, groupOpenId: String, msgId: String?, msgSeq: Int, keyword: String = ""): Boolean {
        val f = StickerPick.pick(ctx, keyword) ?: return false
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return false
        if (bytes.size > 2 * 1024 * 1024) return false
        val info = uploadGroupImage(ctx, acc, groupOpenId, bytes, f.name)
        sendGroupImage(ctx, acc, groupOpenId, info, msgId, msgSeq)
        StickerPick.remember(ctx, f.name)
        return true
    }
}
