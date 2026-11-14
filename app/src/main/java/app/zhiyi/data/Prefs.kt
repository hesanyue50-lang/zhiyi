package app.zhiyi.data

import android.content.Context

data class CfgData(
    var ctxTokens: Int = 6000,          // 上下文预算（token）
    var temperature: Float = 0.85f,
    var maxTokens: Int = 500,
    var summarizeEvery: Int = 20,       // 每 N 条消息压一层摘要
    var factsEvery: Int = 3,            // 每 N 轮抽取一次事实
    var embedEnabled: Boolean = true,   // 有 embedding 模型就走向量召回
    var proactiveEnabled: Boolean = false,
    var quietStart: Int = 23,           // 免打扰起（含）
    var quietEnd: Int = 8,              // 免打扰止（不含）
    var dailyCap: Int = 3,              // 单个人格每天主动消息上限
    var totalDailyCap: Int = 5,         // 所有人物加起来每天总量上限
    var minGapMin: Int = 90,            // 两条主动消息最小间隔（分钟）
    var reasoningEffort: String = "low", // 思考强度 low/medium/high/off
    var userName: String = "你",
    var trust: Float = 0.6f,            // 关系亲密度 0~1
    var theme: String = "system",       // system / dark / light
    var chatMode: String = "novel",     // novel=带旁白提示 / chat=像聊天软件
    var city: String = "",             // 所在城市（空=不启用天气感知）
    var petEnabled: Boolean = false,   // 桌宠常驻
    var stickerCrawl: Boolean = false,  // 允许联网抓表情
    var stickerSource: String = "",     // 表情接口地址
    var ttsKey: String = "",            // MiMo API Key（声音克隆用）
    var ttsPet: Boolean = false,        // 桌宠说话时也念出声
    var ttsAuto: Boolean = false,       // 聊天页自动朗读：来新消息就念
)

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var data: CfgData
        get() = CfgData(
            ctxTokens = sp.getInt("ctx", 6000),
            temperature = sp.getFloat("temp", 0.85f),
            maxTokens = sp.getInt("max", 500),
            summarizeEvery = sp.getInt("sumevery", 20),
            factsEvery = sp.getInt("factevery", 3),
            embedEnabled = sp.getBoolean("embed", true),
            proactiveEnabled = sp.getBoolean("pro_on", false),
            quietStart = sp.getInt("q_s", 23),
            quietEnd = sp.getInt("q_e", 8),
            dailyCap = sp.getInt("cap", 3),
            totalDailyCap = sp.getInt("totalcap", 5),
            minGapMin = sp.getInt("gap", 90),
            reasoningEffort = sp.getString("effort", "low") ?: "low",
            userName = sp.getString("uname", "你") ?: "你",
            trust = sp.getFloat("trust", 0.6f),
            theme = sp.getString("theme", "system") ?: "system",
            chatMode = sp.getString("chatmode", "novel") ?: "novel",
            city = sp.getString("city", "") ?: "",
            petEnabled = sp.getBoolean("pet_on", false),
            stickerCrawl = sp.getBoolean("sticker_on", false),
            stickerSource = sp.getString("sticker_src", "") ?: "",
            ttsKey = sp.getString("tts_key", "") ?: "",
            ttsPet = sp.getBoolean("tts_pet", false),
            ttsAuto = sp.getBoolean("tts_auto", false),
        )
        set(v) {
            sp.edit()
                .putInt("ctx", v.ctxTokens).putFloat("temp", v.temperature).putInt("max", v.maxTokens)
                .putInt("sumevery", v.summarizeEvery).putInt("factevery", v.factsEvery)
                .putBoolean("embed", v.embedEnabled)
                .putBoolean("pro_on", v.proactiveEnabled).putInt("q_s", v.quietStart).putInt("q_e", v.quietEnd)
                .putInt("cap", v.dailyCap).putInt("gap", v.minGapMin)
                .putString("uname", v.userName).putFloat("trust", v.trust).putString("theme", v.theme).putString("chatmode", v.chatMode).putString("city", v.city).putBoolean("pet_on", v.petEnabled).putBoolean("sticker_on", v.stickerCrawl).putString("effort", v.reasoningEffort).putInt("totalcap", v.totalDailyCap).putString("sticker_src", v.stickerSource)
                .putString("tts_key", v.ttsKey).putBoolean("tts_pet", v.ttsPet)
                .putBoolean("tts_auto", v.ttsAuto)
                .apply()
        }

    var activeProvider: String
        get() = sp.getString("pid", "") ?: ""
        set(v) = sp.edit().putString("pid", v).apply()

    var activePersona: String
        get() = sp.getString("persona", "") ?: ""
        set(v) = sp.edit().putString("persona", v).apply()

    companion object {
        @Volatile private var inst: Prefs? = null
        fun get(ctx: Context): Prefs = inst ?: synchronized(this) {
            inst ?: Prefs(ctx).also { inst = it }
        }
    }
}
