package app.zhiyi.qq

import android.content.Context
import app.zhiyi.data.Db
import org.json.JSONArray
import org.json.JSONObject

/**
 * QQ 发送台账。
 *
 * 为什么要这个：群里不回复的时候，可能是「没收到」「收到了被挡」「发出去了但平台拒了」
 * 三种完全不同的原因，而 App 里一个都看不到。这里把每一次**尝试**都记下来：
 * 发给谁、走群还是私聊、被动还是主动、成功还是失败、平台错误码是什么。
 *
 * 环形 40 条，够看清最近发生了什么，也不会撑爆存储。
 */
object QqLog {

    private const val MAX = 40

    /**
     * @param kind   "群被动" / "群主动" / "私聊被动" / "私聊主动" / "上传图片" / "事件"
     * @param target 群或人的 id 尾号
     */
    fun add(ctx: Context, appId: String, kind: String, target: String, ok: Boolean, detail: String = "") {
        runCatching {
            val db = Db.get(ctx)
            val arr = JSONArray(db.get("qq_log_$appId", "[]"))
            arr.put(
                JSONObject()
                    .put("t", System.currentTimeMillis())
                    .put("k", kind)
                    .put("to", target.takeLast(8))
                    .put("ok", ok)
                    .put("d", detail.take(80))
            )
            while (arr.length() > MAX) arr.remove(0)
            db.put("qq_log_$appId", arr.toString())
        }
    }

    /** 给界面用：最近的在前面 */
    fun lines(ctx: Context, appId: String): List<String> {
        val arr = runCatching { JSONArray(Db.get(ctx).get("qq_log_$appId", "[]")) }
            .getOrDefault(JSONArray())
        val out = ArrayList<String>()
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val mark = if (o.optBoolean("ok")) "✓" else "✕"
            val d = o.optString("d")
            out.add("$mark ${o.optString("k")} → …${o.optString("to")}" + if (d.isBlank()) "" else "  $d")
        }
        return out
    }

    fun clear(ctx: Context, appId: String) {
        Db.get(ctx).put("qq_log_$appId", "[]")
    }
}
