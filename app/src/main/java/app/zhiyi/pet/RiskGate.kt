package app.zhiyi.pet

/**
 * 识屏前的安全闸门。
 *
 * 桌宠同时握着 Shizuku（shell）、截屏、无障碍三把钥匙，比一般聊天 App 危险一个量级。
 * 最常见的踩坑不是被攻击，而是：她在你输支付密码的时候截了一张图，图上还带着余额。
 *
 * 做的不是"弹框确认"（聊天场景里没人愿意被反复打断），而是**按前台应用静默拦截**。
 */
object RiskGate {

    /**
     * 精确包名表。不用 `contains("bank")` 这种子串匹配：
     * "tax" 会命中所有打车（taxi），"eid" 会命中微店（weidian），实测误伤。
     */
    private val exactPkgs = mapOf(
        // 支付
        "com.eg.android.alipaygphone" to "支付宝",
        "com.unionpay" to "云闪付",
        // 银行
        "com.icbc" to "工商银行",
        "com.chinamworld.main" to "建设银行",
        "com.bankcomm.bankcomm" to "交通银行",
        "com.cmbchina.ccd.pluto.cmbactivity" to "招商银行",
        "com.android.bankabc" to "农业银行",
        "com.chinamworld.bocmbci" to "中国银行",
        "com.spdb.mobilebank.per" to "浦发银行",
        "com.pingan.paces.ccms" to "平安银行",
        "com.ecitic.bank.mobile" to "中信银行",
        "com.cebbank.mobile.cemb" to "光大银行",
        "com.psbc.mobilebank" to "邮储银行",
        "com.yitong.mbank.psbc" to "邮储银行",
        // 密码 / 验证器
        "com.onepassword.android" to "密码管理器",
        "com.x8bit.bitwarden" to "密码管理器",
        "com.agilebits.onepassword" to "密码管理器",
        "com.lastpass.lpandroid" to "密码管理器",
        "com.keepassdroid" to "密码管理器",
        "com.authy.authy" to "验证器",
        "com.google.android.apps.authenticator2" to "验证器",
    )

    /** activity 名里出现这些词，就算在微信/QQ 里也要拦（收付款、转账、钱包都在主包内） */
    private val riskyActivity = listOf("wallet", "pay", "remit", "offline", "coinpurse", "transfer")

    /** 微信 / QQ 主包，按 activity 细分判断 */
    private val imPkgs = setOf("com.tencent.mm", "com.tencent.mobileqq")

    /** 政务/实名类：包名里常带毫无规律的字段，只能靠 activity 或域名段粗筛 */
    private val govSegs = listOf("gov", "tax", "eid", "ctid", "12333", "socialsecurity")

    data class Top(val pkg: String, val activity: String)

    /** 前台应用（走 Shizuku）。拿不到返回 null —— 调用方要按"未知"处理，不能当"安全" */
    fun topApp(): Top? {
        return try {
            if (!ScreenCapture.shizukuReady()) return null
            val strArr = Class.forName("[Ljava.lang.String;")
            val m = rikka.shizuku.Shizuku::class.java
                .getDeclaredMethod("newProcess", strArr, strArr, String::class.java)
            m.isAccessible = true
            val proc = m.invoke(null, arrayOf("sh", "-c", "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus"), null, null) as Process
            var out = ""
            val t = Thread { runCatching { out = proc.inputStream.bufferedReader().readText() } }
            t.start(); t.join(1200)
            if (t.isAlive) { runCatching { proc.destroy() }; return null }
            runCatching { proc.waitFor() }
            // 形如：mCurrentFocus=Window{abc u0 com.tencent.mm/com.tencent.mm.ui.LauncherUI}
            val mm = Regex("([a-zA-Z][\\w]*(?:\\.[\\w]+)+)/([\\w.$]+)").find(out) ?: return null
            Top(mm.groupValues[1], mm.groupValues[2])
        } catch (e: Throwable) {
            null
        }
    }

    /** @return 命中时返回提示给用户的理由，null = 安全 */
    fun riskOf(top: Top?): String? {
        if (top == null) return null
        val pkg = top.pkg.lowercase()
        exactPkgs[pkg]?.let { return it }
        if (pkg in imPkgs) {
            val act = top.activity.lowercase()
            if (riskyActivity.any { act.contains(it) }) return "收付款界面"
            return null
        }
        val segs = pkg.split('.')
        if (segs.any { it in govSegs }) return "实名政务界面"
        if (segs.any { it == "inputmethod" || it.endsWith("inputmethod") }) return "输入法界面"
        return null
    }
}
