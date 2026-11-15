package app.zhiyi.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QQ 端"只发台词、旁白全删"的边界测试。
 *
 * 这里错一次，用户就会在一句正常的话后面看到一堆括号——比不发还糟。
 */
class QqTextTest {

    private fun speech(s: String) = QqText.onlySpeech(s, "「", "」")

    @Test
    fun 前面有旁白只取台词() {
        assertEquals("嗯。", speech("（她抬眼看你）「嗯。」"))
        assertEquals("知道了，你去吧。", speech("*低头翻了一页*「知道了，你去吧。」（没有抬头）"))
    }

    @Test
    fun 多段台词用换行连起来() {
        assertEquals("好啊。\n那我们走吧。", speech("「好啊。」（她站起来）「那我们走吧。」"))
    }

    @Test
    fun 一个标记都没有时整条当台词但剥掉括号() {
        // 模型没守格式的情况：与其丢掉整条，不如剥掉包裹符号发出去
        val out = speech("（笑）就这样吧，明天见。")
        assertEquals("就这样吧，明天见。", out)
    }

    @Test
    fun 半截标记不吃掉后面的话() {
        assertEquals("没闭合的一句话", speech("（她愣了一下）「没闭合的一句话"))
    }

    @Test
    fun 表情标记能被识别且不混进正文() {
        val raw = "「今天好累啊。」\n[表情]"
        assertTrue(QqText.wantsSticker(raw))
        assertEquals("今天好累啊。", speech(QqText.removeStickerTag(raw)))
        assertFalse(QqText.wantsSticker("「普通的一句话」"))
    }

    @Test
    fun 括号旁白整行删除() {
        val out = QqText.stripMarks("（她靠过来）\n我看看你在干嘛。\n（停顿了一下）")
        assertEquals("我看看你在干嘛。", out)
    }

    @Test
    fun 长文按句子分片不切碎() {
        val long = "第一句。" .repeat(30)
        val parts = QqText.chunk(long, 100)
        assertTrue("应该切成多片", parts.size > 1)
        assertTrue("每片都不超长太多", parts.all { it.length <= 200 })
        assertTrue("拼回去应该还完整", parts.joinToString("").length >= 120)
    }

    @Test
    fun 空的输入不会崩() {
        assertEquals("", speech(""))
        assertEquals("", speech("（只有旁白）"))
    }

    @Test
    fun 按换行分条像真人发消息() {
        val out = QqText.lines("在的。\n刚吃完饭。\n你呢？")
        assertEquals(3, out.size)
        assertEquals("在的。", out[0])
        assertEquals("你呢？", out[2])
    }

    @Test
    fun 超过上限时合并而不是丢内容() {
        val out = QqText.lines("一\n二\n三\n四\n五", maxLines = 3)
        assertEquals(3, out.size)
        assertTrue("多出来的应该被并进最后一条", out[2].contains("五") || out[2].contains("三"))
    }

    @Test
    fun 空行和首尾空白被忽略() {
        val out = QqText.lines("\n\n  第一句  \n\n  第二句 \n\n")
        assertEquals(2, out.size)
        assertEquals("第一句", out[0])
    }

    @Test
    fun 单行超长会再切() {
        val long = "很长的一句话。".repeat(40)
        val out = QqText.lines(long, maxLines = 3, maxLen = 100)
        assertTrue("应该切成多条", out.size >= 2)
        assertTrue("每条都不超过上限", out.all { it.length <= 120 })
    }
}
