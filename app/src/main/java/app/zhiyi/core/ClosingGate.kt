package app.zhiyi.core

/**
 * 收尾闸门：拦住"互道晚安"的死循环。
 *
 * 现实里两个人道过晚安就不再出声了。模型不会——你说「晚安」，它答「晚安，好梦」，
 * 你回「嗯」，它又要答「嗯，睡吧」。提示词压不住这个，只能靠代码短路。
 *
 * 判据必须**同时**满足两条，否则正常对话里的"嗯"会被误杀：
 * 1. 用户这条是纯收尾词（≤6 字、只含收尾词，没有新信息）；
 * 2. 她上一条也在收尾（短、且带道别意象）。
 *
 * 只拦截，不调用模型——省一次请求，也避免她说出第三句晚安。
 */
object ClosingGate {

    /** 只有整句话就是这个才拦；"嗯我今天加班到十点"不会命中 */
    private val WORDS = setOf(
        "晚安", "晚安啦", "晚安了", "好梦", "做个好梦",
        "睡了", "去睡了", "我先睡了", "先睡了", "睡觉去了", "该睡了",
        "早", "早安", "早上好", "早呀",
        "拜拜", "再见", "回见", "明天见", "回头见", "下次聊", "先这样", "先这样吧",
        "睡吧", "你也睡", "你也早点睡",
        "嗯", "嗯嗯", "哦", "好", "好的", "行", "知道了", "收到",
    )

    /** 她那边出现这些就是已经在收尾了 */
    private val AI_TAILS = listOf("晚安", "好梦", "睡吧", "早点睡", "早点休息", "明天见", "拜拜", "再见", "去睡")

    private val SHORT = listOf("嗯。", "嗯，睡吧。", "晚安。", "好梦。")

    /** 用户这条是不是纯收尾语 */
    fun isClosing(text: String): Boolean {
        var s = text.trim()
        // 只剥尾部的标点和语气词，剥多了会把"晚安啦"变成"晚安"、把"好"变成空
        s = s.trimEnd('。', '，', '！', '!', '~', '～', '…', '.', ' ', '\n', '\t', '？', '?')
        if (s.isEmpty() || s.length > 6) return false
        if (s in WORDS) return true
        // "晚安啦""睡了啊"这类：剥掉尾部语气词再试一次
        val t = s.trimEnd('啦', '了', '呀', '啊', '哦')
        return t.isNotEmpty() && t in WORDS
    }

    /** 她只回一声「嗯。」「好。」——这就是在收尾了，不用等她说晚安 */
    private val BARE_ACK = setOf("嗯", "嗯嗯", "嗯嗯嗯", "好", "好的", "行", "哦")

    /** 她上一条是不是也在收尾（短且带道别意象） */
    fun isAiClosing(text: String): Boolean {
        if (text.isBlank() || text.length > 24) return false
        if (AI_TAILS.any { text.contains(it) }) return true
        val s = text.trim().trim('。', '，', '！', '~', '…', '.', ' ')
        return s in BARE_ACK
    }

    fun shortReply(): String = SHORT.random()
}
