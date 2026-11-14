package app.zhiyi.persona

import android.util.Base64
import app.zhiyi.data.LoreEntry
import app.zhiyi.data.Persona
import org.json.JSONObject
import java.util.zip.Inflater

/**
 * SillyTavern 角色卡导入：PNG 内嵌 tEXt/zTXt/iTXt 的 "chara" 字段（base64 JSON），
 * 兼容 chara_card_v2 / v3 / v1。这是全网角色卡的通用格式，所以不用自创。
 */
object StCard {

    fun fromPng(b: ByteArray): Pair<Persona, String>? {
        val json = extract(b) ?: return null
        val p = fromJsonString(json) ?: return null
        return p to json
    }

    fun fromJsonString(s: String): Persona? {
        val root = runCatching { JSONObject(s.trim().removePrefix("\uFEFF")) }.getOrNull() ?: return null
        val d = if (root.has("data") && root.optJSONObject("data") != null) root.getJSONObject("data") else root
        fun str(k: String) = d.optString(k, "")
        val name = str("name").ifBlank { "无名" }
        val desc = str("description")
        val pers = str("personality")
        val sys = str("system_prompt")
        val scenario = str("scenario")
        val first = str("first_mes")
        val example = str("mes_example")

        val lore = mutableListOf<LoreEntry>()
        d.optJSONObject("character_book")?.optJSONArray("entries")?.let { arr ->
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                if (e.optBoolean("enabled", true).not()) continue
                val content = e.optString("content", "")
                if (content.isBlank()) continue
                val keys = mutableListOf<String>()
                e.optJSONArray("keys")?.let { ka -> for (k in 0 until ka.length()) keys.add(ka.optString(k)) }
                e.optJSONArray("key")?.let { ka -> for (k in 0 until ka.length()) keys.add(ka.optString(k)) }
                val sec = mutableListOf<String>()
                e.optJSONArray("secondary_keys")?.let { ka -> for (k in 0 until ka.length()) sec.add(ka.optString(k)) }
                e.optJSONArray("secondaryKeys")?.let { ka -> for (k in 0 until ka.length()) sec.add(ka.optString(k)) }
                lore.add(
                    LoreEntry(
                        keys = keys.filter { it.isNotBlank() },
                        content = content,
                        secondaryKeys = sec.filter { it.isNotBlank() },
                        constant = e.optBoolean("constant", false),
                        caseSensitive = e.optBoolean("case_sensitive", false),
                    )
                )
            }
        }

        val oneLine = desc.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: ""
        return Persona(
            id = "st_" + name.hashCode().toUInt().toString(16) + "_" + System.currentTimeMillis().toString(36),
            name = name,
            role = "",
            callYou = "你",
            summary = oneLine,
            personality = listOf(desc, pers, sys).filter { it.isNotBlank() }.joinToString("\n\n"),
            speech = "",
            scenario = scenario,
            firstMes = first,
            exampleDialog = example.take(1200),
            lore = lore.take(80),
            proactiveStyle = "",
            source = "st",
        )
    }

    /** 只接受真的是角色卡的 JSON，避免把纯描述文本的 chunk 当成卡 */
    private fun looksLikeCard(s: String): Boolean = runCatching {
        val o = JSONObject(s)
        o.has("name") || o.has("data") || o.has("description")
    }.getOrDefault(false)

    private val CHUNK_KEYWORDS = setOf("chara", "ccv3", "character_card", "description", "comment")

    // ---------------- PNG 解析 ----------------
    private fun extract(b: ByteArray): String? {
        if (b.size < 16) return null
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) if (b[i] != sig[i]) return null
        var pos = 8
        var found: String? = null
        while (pos + 12 <= b.size) {
            val len = readInt(b, pos)
            if (len < 0 || pos + 12 + len > b.size) break
            val type = String(b, pos + 4, 4, Charsets.US_ASCII)
            val ds = pos + 8
            val data = b.copyOfRange(ds, ds + len)
            when (type) {
                "tEXt", "zTXt", "iTXt" -> {
                    val t = decodeText(type, data)
                    if (t != null) {
                        val kw = t.first.lowercase()
                        if (kw in CHUNK_KEYWORDS) {
                            // 大部分卡是 base64，少数工具直接存明文，两种都试
                            val decoded = b64utf8(t.second) ?: String(t.second, Charsets.UTF_8)
                            if (looksLikeCard(decoded)) {
                                if (kw == "ccv3") return decoded
                                if (found == null) found = decoded
                            }
                        }
                    }
                }
            }
            pos = ds + len + 4
        }
        return found
    }

    private fun decodeText(type: String, data: ByteArray): Pair<String, ByteArray>? = runCatching {
        val z = idx(data, 0, 0)
        if (z <= 0) return null
        val kw = String(data, 0, z, Charsets.ISO_8859_1)
        when (type) {
            "tEXt" -> kw to data.copyOfRange(z + 1, data.size)
            "zTXt" -> {
                val compressed = data.copyOfRange(z + 2, data.size)
                kw to inflate(compressed)
            }
            else -> { // iTXt
                var i = z + 1
                val flag = data[i].toInt(); i++
                i++ // method
                val langEnd = idx(data, i, 0).let { if (it < 0) data.size else it }
                i = langEnd + 1
                val transEnd = idx(data, i, 0).let { if (it < 0) data.size else it }
                i = transEnd + 1
                val body = data.copyOfRange(i, data.size)
                kw to if (flag == 1) inflate(body) else body
            }
        }
    }.getOrNull()

    private fun inflate(b: ByteArray): ByteArray {
        val inf = Inflater()
        inf.setInput(b)
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inf.finished()) {
            val n = inf.inflate(buf)
            if (n == 0) break
            out.write(buf, 0, n)
        }
        inf.end()
        return out.toByteArray()
    }

    private fun b64utf8(b: ByteArray): String? = runCatching {
        val s = String(b, Charsets.ISO_8859_1).trim()
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    private fun idx(b: ByteArray, from: Int, target: Int): Int {
        var i = from
        while (i < b.size) { if (b[i].toInt() == target) return i; i++ }
        return -1
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
}
