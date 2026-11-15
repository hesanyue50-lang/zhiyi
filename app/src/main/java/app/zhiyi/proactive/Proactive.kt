package app.zhiyi.proactive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.zhiyi.MainActivity
import app.zhiyi.core.ChatCore
import app.zhiyi.data.CfgData
import app.zhiyi.data.Db
import app.zhiyi.data.Prefs
import app.zhiyi.data.modelList
import app.zhiyi.memory.Assembler
import app.zhiyi.memory.MemoryEngine
import app.zhiyi.memory.EmbedCtx
import app.zhiyi.llm.Llm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * 主动发话策略引擎。
 * 技术不是难点，"懂分寸"才是：每日上限、最小间隔、免打扰、被冷落就退避。
 */
object Proactive {

    private const val KEY_REPLY = "zhiyi_reply"

    fun inQuiet(cfg: CfgData, hour: Int): Boolean =
        if (cfg.quietStart <= cfg.quietEnd) hour >= cfg.quietStart && hour < cfg.quietEnd
        else hour >= cfg.quietStart || hour < cfg.quietEnd

    /** 打分 0~1，同时返回触发原因，便于排查为什么没说话 */
    /** 最低起步空闲时长：刚聊完就不该马上又冒一条（这和 minGapMin 不是一回事，那个管两次主动之间的间隔） */
    private const val MIN_IDLE_HOURS = 1.5

    fun score(ctx: Context, pid: String): Pair<Double, String> {
        val db = Db.get(ctx)
        val pr = Prefs.get(ctx)
        val cfg = pr.data
        if (pid.isBlank()) return 0.0 to "no_persona"

        val now = System.currentTimeMillis()
        val lastUser = db.getNum("last_user_ts_$pid", 0)
        val lastPro = db.getNum("last_proactive_ts_$pid", 0)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val hour = cal.get(Calendar.HOUR_OF_DAY)

        // 硬闸门
        if (!cfg.proactiveEnabled) return 0.0 to "off"
        if (inQuiet(cfg, hour)) return 0.0 to "quiet"
        if (lastPro > 0 && now - lastPro < cfg.minGapMin * 60_000L) return 0.0 to "gap"
        val todayKey = "pro_count_${pid}_${dayKey(now)}"
        val todayCount = db.getNum(todayKey, 0)
        if (todayCount >= cfg.dailyCap) return 0.0 to "cap"

        // 连续发了几条都没人回 → 先自己安静下来，而不是继续吵
        val streak = db.getNum("pro_streak_$pid", 0)
        val silenceUntil = db.getNum("pro_silence_until_$pid", 0)
        if (now < silenceUntil) return 0.0 to "backoff"

        // 空闲时长：3 小时满格（原来 12 小时满格太慢，用户离开半天都触发不了）
        val idleH = if (lastUser == 0L) 8.0 else (now - lastUser) / 3_600_000.0
        // 刚安静一会儿就冒泡太黏人。1.5 小时是最低起步价，低于这个不管别的项多高都不开口
        if (idleH < MIN_IDLE_HOURS) return 0.0 to "刚聊完没多久"
        val idle = (idleH / 3.0).coerceIn(0.0, 1.0)

        // 时间契合：白天也给足分，别只在早晚才肯开口
        val timeFit = when (hour) {
            in 7..9 -> 0.95; in 12..13 -> 0.85; in 21..23 -> 1.0; in 0..1 -> 0.85
            in 2..6 -> 0.1; in 9..12 -> 0.7; in 13..21 -> 0.75; else -> 0.5
        }

        // 记忆钩子：最近 72 小时有过"值得记住的事"，主动开口才有由头
        val recentEpisode = db.allEpisodes(pid).any { now - it.ts < 72 * 3_600_000L && it.importance > 0.5 }
        val hasPlan = db.allFacts(pid).any { it.predicate == "计划" && now - it.created < 7 * 24 * 3_600_000L }
        val hasConflict = db.get("conflict_$pid", "").isNotBlank()
        val hook = when {
            hasConflict -> 1.0   // 记忆矛盾 = 最该开口确认
            hasPlan -> 0.98
            recentEpisode -> 0.95
            db.allFacts(pid).size >= 3 -> 0.55
            else -> 0.25
        }

        // 上次是我们说的、对方还没回 → 再追一句要克制
        val lastMsg = db.recentMsgs(ChatCore.threadKey(pid, cfg.chatMode), 1).lastOrNull()
        // 原来是 +0.12*pending：注释写着"要克制"，代码却在给分，等于「我说完没人回就更想说」。
        // 反过来才对，改成扣分。
        val pending = if (lastMsg?.role == "assistant") 1.0 else 0.0

        // ---- 九维情绪：她此刻想不想说话，由心决定，不是纯看表 ----
        val heart = app.zhiyi.memory.Heart.tick(db, pid)
        val hFat = heart[app.zhiyi.memory.Heart.Dim.FATIGUE]
        val hStr = heart[app.zhiyi.memory.Heart.Dim.STRESS]
        if (hFat >= 78) return 0.0 to "太累了"
        if (hStr >= 70) return 0.0 to "还在绷着"
        val want = (heart[app.zhiyi.memory.Heart.Dim.ATTACH] +
            heart[app.zhiyi.memory.Heart.Dim.INTIMACY] +
            heart[app.zhiyi.memory.Heart.Dim.SOCIAL]) / 3.0 / 100.0
        val tired = hFat / 100.0
        // 心里惦记着具体的事 -> 有由头开口
        val thoughtHook = if (heart.thought.isNotBlank()) 1.0 else hook

        val rand = Math.random()
        // 空闲权重从 0.26 提到 0.34：这句"她主动想起你"的前提本来就是「你有一阵没出现了」，
        // 其他项加起来不该在没有空闲的时候也够分。
        var s = 0.34 * idle + 0.18 * timeFit + 0.17 * thoughtHook +
            0.14 * want - 0.10 * pending + 0.09 * rand - 0.22 * tired
        s = s.coerceIn(0.0, 1.0)
        return s to "idle=${"%.2f".format(idle)} want=${"%.2f".format(want)} " +
            "thought=${if (heart.thought.isNotBlank()) heart.thought else "无"} 心情=${app.zhiyi.memory.Heart.moodLabel(heart)}"
    }

    /** 人格并行：所有人格各自独立评分、各自主动发话（多线程并行） */
    /**
     * 每次只让**一个人格**开口，不是"有几个人格就发几条"。
     * 打乱后依次试，第一个条件满足的就发，发出一条就停。
     * 另外有一道全局总量闸门：所有人格加起来每天不超过 cfg.totalDailyCap。
     */
    suspend fun maybeFireAll(ctx: Context, force: Boolean = false, test: Boolean = false) {
        val db = Db.get(ctx)
        val cfg = Prefs.get(ctx).data
        if (!test && !force) {
            val totalKey = "pro_total_${dayKey(System.currentTimeMillis())}"
            if (db.getNum(totalKey, 0) >= cfg.totalDailyCap) return
        }
        val personas = db.personas().filter { it.id.isNotBlank() }
        if (personas.isEmpty()) return
        for (p in personas.shuffled()) {
            val fired = runCatching { maybeFire(ctx, force, p.id) }.getOrDefault(false)
            if (fired) {
                if (!test) {
                    val totalKey = "pro_total_${dayKey(System.currentTimeMillis())}"
                    db.putNum(totalKey, db.getNum(totalKey, 0) + 1)
                }
                return
            }
        }
    }

    suspend fun maybeFire(ctx: Context, force: Boolean = false, pid: String? = null, test: Boolean = false): Boolean {
        val db = Db.get(ctx)
        val pr = Prefs.get(ctx)
        val cfg = pr.data
        val target = pid ?: ChatCore.activePersona(ctx).id
        if (target.isBlank()) return false
        val (s, why) = score(ctx, target)
        // 只认一个来源：否则消息会以 prefs 里的旧 id 落库，成为任何人格下都看不到的孤儿
        val persona = db.persona(target) ?: return false
        val pid = persona.id
        if (pid.isBlank()) return false
        db.logProactive(s, false, why)
        // 硬闸门：force 也不能绕过这些
        if (!cfg.proactiveEnabled) return false
        if (inQuiet(cfg, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) return false
        // 用户正在场（30 分钟内说过话）就别插话
        val lastUser0 = db.getNum("last_user_ts_$pid", 0)
        if (!test && lastUser0 > 0 && System.currentTimeMillis() - lastUser0 < 30 * 60_000L) return false
        // 最小间隔 / 每日上限 / 退避：原来是 score() 里返回 0，force 会绕过，现在搬到这里
        if (!test) {
            val lastPro0 = db.getNum("last_proactive_ts_$pid", 0)
            // 首次运行（装好/重装清数据后）只播种时间，不要"打开 App 立刻冒一条"
            if (lastPro0 == 0L) {
                db.putNum("last_proactive_ts_$pid", System.currentTimeMillis())
                return false
            }
            if (lastPro0 > 0 && System.currentTimeMillis() - lastPro0 < cfg.minGapMin * 60_000L) return false
            val cntKey = "pro_count_${pid}_${dayKey(System.currentTimeMillis())}"
            if (db.getNum(cntKey, 0) >= cfg.dailyCap) return false
            if (System.currentTimeMillis() < db.getNum("pro_silence_until_$pid", 0)) return false
        }
        // 门槛随「连续未被回应」次数抬高：0 / .05 / .10 / .15
        val streak = db.getNum("pro_streak_$pid", 0)
        // ---- 阈值不再是一个固定数，而是她此刻心情算出来的 ----
        // 同一个「空闲两小时」，黏人的时候该开口，累了的时候就不该。固定阈值做不到这件事。
        // 这里用 load 而不是 tick：只读，不推进情绪（推进已经在 score() 里做过一次）
        val h = app.zhiyi.memory.Heart.load(db, pid)
        val hWant = (h[app.zhiyi.memory.Heart.Dim.ATTACH] +
            h[app.zhiyi.memory.Heart.Dim.INTIMACY] +
            h[app.zhiyi.memory.Heart.Dim.SOCIAL]) / 3.0 / 100.0
        val hTired = h[app.zhiyi.memory.Heart.Dim.FATIGUE] / 100.0
        val hStress = h[app.zhiyi.memory.Heart.Dim.STRESS] / 100.0
        val hJoy = h[app.zhiyi.memory.Heart.Dim.JOY] / 100.0
        val hRumi = h[app.zhiyi.memory.Heart.Dim.RUMINATION] / 100.0
        var need = 0.68
        need -= 0.20 * hWant        // 依恋/亲密/社交高 → 更想找他说话
        need += 0.24 * hTired       // 累了就不硬撑
        need += 0.20 * hStress      // 还绷着就不想开口
        need -= 0.10 * hJoy         // 心情好更愿意撩一下
        need -= 0.06 * hRumi        // 心里反复想着某件事 → 更想找他确认
        need = need.coerceIn(0.50, 0.95) + 0.05 * streak.coerceAtMost(3)
        if (!force && s < need) return false

        val prov = ChatCore.providerFor(ctx, pid) ?: return false
        val llm = Llm(prov)
        val asm = Assembler(db, cfg)
        EmbedCtx.last = null
        val plans = db.allFacts(pid)
            .filter { it.predicate == "计划" && System.currentTimeMillis() - it.created < 7 * 24 * 3_600_000L }
            .take(5)
            .joinToString("\n") { "· " + it.obj }
        // 记忆矛盾：她之前记住的 A，对方现在说成 B，要开口确认
        val conflict = runCatching {
            val o = org.json.JSONObject(db.get("conflict_$pid", ""))
            Triple(o.optString("pred"), o.optString("old"), o.optString("new"))
        }.getOrNull()
        // 时间问候 + 纪念日：让她开口更有人情味
        val hourNow = Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }.get(Calendar.HOUR_OF_DAY)
        val timeHint = when (hourNow) {
            in 5..9 -> "现在是早上，可以顺口问候早安。"
            in 21..23, 0, 1 -> "现在是深夜，可以说句晚安或早点休息。"
            else -> ""
        }
        val firstTs = db.getNum("first_chat_ts_$pid", 0)
        val days = if (firstTs > 0) ((System.currentTimeMillis() - firstTs) / 86_400_000L + 1).toInt() else 0
        val anniHint = when {
            days > 0 && (days in intArrayOf(7, 30, 100, 200, 365) || (days > 365 && days % 365 == 0)) ->
                "今天是你们相识第 $days 天，值得提一句。"
            else -> ""
        }
        val calNow = Calendar.getInstance()
        val bdayHint = persona.birthday.takeIf { it.isNotBlank() }?.let { b ->
            val m = Regex("(\\d{1,2})月(\\d{1,2})").find(b)
            val isBday = m != null &&
                m.groupValues[1].toIntOrNull() == (calNow.get(Calendar.MONTH) + 1) &&
                m.groupValues[2].toIntOrNull() == calNow.get(Calendar.DAY_OF_MONTH)
            if (isBday) "今天是她的生日，记得好好说句生日快乐。" else ""
        } ?: ""
        val styleHint = persona.proactiveStyle.takeIf { it.isNotBlank() }?.let { "（你的主动发话风格：$it）\n" } ?: ""
        val heartNow = app.zhiyi.memory.Heart.load(db, pid)
        val thoughtHint = if (heartNow.thought.isNotBlank())
            "（可以往这个方向递一句话，用你自己的话，别把它当台词念：${heartNow.thought}）"
        else ""
        val task = """
# 现在的任务
${styleHint}
对方此刻没有在跟你说话。你忽然想起了什么，决定主动开口。
要求：
- 只说 1~2 句，像当面忽然开口那样自然，不用称呼开场，不说「在吗」「在干嘛」这种空话。
- **绝对不能接着你们上次聊的内容说**：不要复述、不要引用、不要接上一次的话头、不要承接任何正在进行的对话。
  就当那段对话已经翻篇了，你是隔了一阵子突然想找对方。
- 从下面这些方向里**自己挑一个全新的**（每次换着来，别连续两次同一个方向）：
  · 你自己此刻在做的事/看到的东西（你在家里、在外面、在收拾东西、在发呆……由你的人格决定）
  · 你刚想到的一个念头、一个奇怪的问题、一个没头没尾的感叹
  · 当下的时间/天气带给你的感受
  · 你最近的一件小事（见下面「你今天的一件小事」）
  · 你突然关心起对方的某件**与刚才聊天无关**的事
- 只有在确实存在"记忆里的矛盾"时才优先确认它（这是最该开口的）；
  其次是对方说过的「计划/承诺」——但要用"突然想起来"的口吻，不要在接着聊。
- 不要问句连篇，不要一次问三个问题。
- **只说简体中文**，一个英文单词都不要有。也不要复述或确认任何指令。

你现在的心境：
${app.zhiyi.memory.Heart.hint(Db.get(ctx), pid).ifBlank { "（无）" }}
${thoughtHint}

现在的时刻：
${timeHint.ifBlank { "（无特别提示）" }}
${anniHint.ifBlank { "" }}
${bdayHint.ifBlank { "" }}

记忆里的矛盾：
${conflict?.let { "你之前记得「${it.second}」，现在对方说「${it.third}」（关于${it.first}）" } ?: "（无）"}

对方提过的计划/承诺：
${plans.ifBlank { "（无）" }}
""".trimIndent()
        val tk = ChatCore.threadKey(pid, cfg.chatMode)
        // proactive=true：不要把往事 / 最近摘要带进来，这是"另起话题"的前提
        val sys = asm.systemPrompt(pid, tk, persona, "", null, proactive = true)
        // 不带任何聊天原话（带上历史她就会顺着上文说，正是上一版不满意的地方），
        // 但**必须有一条 user 消息**：只剩 system 的话，智谱系上游会判「参数有误」
        // (11133 / 1214 -> 网关 no_healthy_account)，表现就是"立即测试"一直 503。
        val msgs = listOf("system" to sys, "user" to task)
        // 模型名是逗号分隔的列表，必须逐个试（之前整串发过去，网关不认 -> 空内容）
        val models = (prov.cheapModel.modelList().ifEmpty { prov.chatModel.modelList() })
        var text: String? = null
        var lastErr: Throwable? = null
        for (m in models) {
            try {
                val r = llm.chat(m, msgs, 0.95, 400, false).trim()
                if (app.zhiyi.llm.OutputGuard.reject(r)) {
                    android.util.Log.w("ZhiYi", "proactive: model $m 输出不合格（英文/复述指令），换下一个")
                    continue
                }
                if (r.isNotBlank()) { text = r; break }
                android.util.Log.w("ZhiYi", "proactive: model $m 返回空")
            } catch (e: Throwable) {
                lastErr = e
                android.util.Log.w("ZhiYi", "proactive: model $m 失败 ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (text.isNullOrBlank()) {
            val why = lastErr?.let { it.message ?: it.javaClass.simpleName }
                ?: "所有模型都返回空内容（模型名=${models.joinToString("/")}）"
            Db.get(ctx).put("pro_last_error_$pid", "${System.currentTimeMillis()}|$why")
        }
        if (text.isNullOrBlank()) {
            android.util.Log.w("ZhiYi", "proactive text blank, skip")
            return false
        }

        db.addMsg(app.zhiyi.data.Msg(pid = tk, role = "assistant", content = text))
        db.putNum("last_assistant_ts_$pid", System.currentTimeMillis())
        // 矛盾已经开口问过了，清掉标记，等下一次新矛盾
        if (conflict != null) db.put("conflict_$pid", "")
        db.putNum("last_proactive_ts_$pid", System.currentTimeMillis())
        if (!test) {
            db.putNum("pro_count_${pid}_${dayKey(System.currentTimeMillis())}", db.getNum("pro_count_${pid}_${dayKey(System.currentTimeMillis())}", 0) + 1)
            // 连续 3 次没人回 -> 安静 24 小时
            val nextStreak = db.getNum("pro_streak_$pid", 0) + 1
            db.putNum("pro_streak_$pid", nextStreak)
            if (nextStreak >= 3) {
                db.putNum("pro_silence_until_$pid", System.currentTimeMillis() + 24 * 3_600_000L)
                db.putNum("pro_streak_$pid", 0)
            }
        }
        db.logProactive(s, true, why)
        // 渠道是**按人格**设置的：
        //   auto（默认）——配了 QQ 就走 QQ，发不出去再退回通知（人就在 QQ 那边，塞通知栏看不见）
        //   qq          ——只走 QQ，失败就是失败，不要再发通知（否则两处同时冒出来）
        //   app         ——只发通知
        val channel = db.get("pro_channel_$pid", "auto")
        val sentToQq = channel != "app" && app.zhiyi.qq.QqOut.send(ctx, pid, text)
        if (!sentToQq && channel != "qq") {
            Notifier.show(ctx, persona.name, text, pid)
        }
        return true
    }

    private val dailyLock = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 每日例行：写日记 + 生成生活流（每天第一次心跳时跑，带互斥防并发重复写） */
    suspend fun dailyRoutine(ctx: Context) {
        if (!dailyLock.compareAndSet(false, true)) return
        try {
            dailyRoutineInner(ctx)
        } finally {
            dailyLock.set(false)
        }
    }

    private suspend fun dailyRoutineInner(ctx: Context) {
        val db = Db.get(ctx)
        val cfg = Prefs.get(ctx).data
        val persona = ChatCore.activePersona(ctx)
        val pid = persona.id
        if (pid.isBlank()) return
        val today = dayKey(System.currentTimeMillis())
        if (db.get("diary_$pid", "") == today && db.get("life_$pid", "") == today) return

        // 自动备份：每天第一份例行工作时导一次，保留最近 3 份（换机/误操作能救回来）
        runCatching {
            val dir = java.io.File(ctx.filesDir, "auto_backup").apply { mkdirs() }
            val f = java.io.File(dir, "zhiyi-$today.json")
            if (!f.exists()) {
                val json = db.exportAll()
                f.writeText(json)
                dir.listFiles()?.sortedByDescending { it.name }?.drop(3)?.forEach { it.delete() }
            }
        }

        // 人格自进化：每天一次，让它慢慢长成更合适的样子
        runCatching { app.zhiyi.memory.PersonaEvolve.daily(ctx, pid) }

        val prov = ChatCore.providerFor(ctx, pid) ?: return
        val llm = Llm(prov)
        val cheapModel = prov.cheapModel.modelList().firstOrNull()
            ?: prov.chatModel.modelList().firstOrNull() ?: return
        val mem = MemoryEngine(db, cfg)
        val cheap: suspend (String, String) -> String = { s, u ->
            llm.chat(cheapModel, listOf("system" to s, "user" to u), 0.7, 800, false)
        }

        if (db.get("diary_$pid", "") != today) {
            val d = mem.writeDiary(pid, persona, cheap)
            if (d.isNotBlank()) { db.addDiary(pid, today, d); db.put("diary_$pid", today) }
        }
        if (db.get("life_$pid", "") != today) {
            val l = mem.writeLife(pid, persona, cheap)
            if (l.isNotBlank()) { db.addLife(pid, l); db.put("life_$pid", today) }
        }
    }

    private fun dayKey(ts: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = ts }
        return String.format(java.util.Locale.US, "%04d%02d%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    const val REPLY_KEY = KEY_REPLY
}

object Notifier {
    private const val CH = "zhiyi_chat"

    private fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CH) == null) {
                val c = NotificationChannel(CH, "她发来的消息", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "聊天与主动消息"
                    enableVibration(true)
                }
                mgr.createNotificationChannel(c)
            }
        }
    }

    fun show(ctx: Context, name: String, text: String, pid: String) {
        channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("open", "chat:$pid"),          // 点通知直接进她那个会话
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remote = androidx.core.app.RemoteInput.Builder(Proactive.REPLY_KEY).setLabel("回复").build()
        val pidNum = pid.hashCode() and 0x0FFFFFFF      // 不取模，降低多人格撞车概率
        val replyPi = PendingIntent.getBroadcast(
            ctx, 1000 + pidNum, Intent(ctx, ReplyReceiver::class.java).putExtra("pid", pid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = NotificationCompat.Action.Builder(0, "回复", replyPi).addRemoteInput(remote).build()
        val n = NotificationCompat.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(action)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)   // 锁屏不露正文
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1001 + pidNum % 100000, n)
    }
}

/** 每 15 分钟的检查心跳 */
class TickReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val p = goAsync()
        val daily = intent.getIntExtra("daily", -1)
        // ExactAndAllowWhileIdle 不会自动重复：每次触发后重排（但关掉主动发话就别再续命）
        runCatching {
            if (Prefs.get(ctx).data.proactiveEnabled) {
                if (daily > 0) ProactiveAlarm.scheduleDailyNext(ctx, daily / 100, daily % 100)
                else ProactiveAlarm.scheduleNextHeartbeat(ctx)
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (daily > 0) {
                    kotlinx.coroutines.withTimeout(60_000) { Proactive.maybeFire(ctx.applicationContext, force = true) }
                } else {
                    kotlinx.coroutines.withTimeout(60_000) { Proactive.maybeFireAll(ctx.applicationContext) }
                }
            } catch (e: Throwable) {
                android.util.Log.w("ZhiYi", "tick failed", e)
            }
            try {
                // 每日例行：写日记 + 生活流（每天第一次心跳才真正跑，内部有日期判断）
                kotlinx.coroutines.withTimeout(40_000) { Proactive.dailyRoutine(ctx.applicationContext) }
            } catch (e: Throwable) {
                android.util.Log.w("ZhiYi", "daily routine failed", e)
            } finally {
                p.finish()
            }
        }
    }
}

/** 通知栏直接回复 */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val results = android.app.RemoteInput.getResultsFromIntent(intent) ?: return
        val text = results.getCharSequence(Proactive.REPLY_KEY)?.toString()?.trim() ?: return
        if (text.isBlank()) return
        val p = goAsync()
        val app = ctx.applicationContext
        val targetPid = intent.getStringExtra("pid")?.takeIf { it.isNotBlank() }
        // 广播 pending result 的预算约 10s，先落用户消息保证不丢，再异步生成
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ChatCore.saveUserMsg(app, text, explicitPid = targetPid)
                kotlinx.coroutines.withTimeout(8_000) {
                    val reply = ChatCore.reply(app, explicitPid = targetPid)
                    val p2 = targetPid?.let { Db.get(app).persona(it) } ?: ChatCore.activePersona(app)
                    Notifier.show(app, p2.name, reply, p2.id)
                }
            } catch (e: Throwable) {
                android.util.Log.w("ZhiYi", "reply from notification failed", e)
            } finally {
                p.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (Prefs.get(ctx).data.proactiveEnabled) ProactiveAlarm.schedule(ctx)
        // QQ 长连接：开过就跟着开机拉起来，否则重启手机后她在 QQ 里就哑了
        if (app.zhiyi.qq.QqAccounts.all(app.zhiyi.data.Db.get(ctx)).any { it.enabled && it.ready }) {
            runCatching { app.zhiyi.qq.QqService.start(ctx) }
        }
    }
}
