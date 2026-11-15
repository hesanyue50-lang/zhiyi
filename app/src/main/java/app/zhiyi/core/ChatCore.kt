package app.zhiyi.core

import android.content.Context
import app.zhiyi.data.CfgData
import app.zhiyi.data.Db
import app.zhiyi.data.ImageStore
import app.zhiyi.data.Msg
import app.zhiyi.data.Persona
import app.zhiyi.data.Prefs
import app.zhiyi.data.modelList
import app.zhiyi.data.Provider
import app.zhiyi.llm.Llm
import app.zhiyi.llm.LlmException
import app.zhiyi.memory.Assembler
import app.zhiyi.memory.EmbedCtx
import app.zhiyi.memory.MemoryEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withContext

/** 统一入口：App 内对话、通知直接回复、主动发话都走这里 */
object ChatCore {

    /**
     * 按人格串行化生成。入口不止 UI 一个：通知栏回复、主动发话都会调 reply，
     * 并行会各自读一遍历史再各自追加 → 消息错位、记忆被抽两遍。
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 记忆整理用的应用级作用域：不随 ViewModel 销毁，也不阻塞回复 */
    private val background = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
    private fun lockFor(pid: String): Mutex = locks.getOrPut(pid) { Mutex() }

    /**
     * 消息按界面分线程：小说界面用 pid，聊天界面用 "pid#chat"。
     * 事实/回忆/摘要仍按 pid，所以两个界面记忆互通、对话互不干扰。
     */
    /** 单一消息流（聊天/小说界面已合并），恒返回 pid。保留函数签名避免改一堆调用点 */
    fun threadKey(pid: String, mode: String) = pid

    fun db(ctx: Context) = Db.get(ctx)
    fun prefs(ctx: Context) = Prefs.get(ctx)
    fun cfg(ctx: Context): CfgData = Prefs.get(ctx).data

    /** 每个身份自己的模型：kv 里存 provider_<pid>，没设过就用第一个 */
    fun providerFor(ctx: Context, pid: String): Provider? {
        val db = Db.get(ctx)
        val all = db.providers()
        if (all.isEmpty()) return null
        val id = db.get("provider_$pid", "")
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setProviderFor(ctx: Context, pid: String, providerId: String) {
        Db.get(ctx).put("provider_$pid", providerId)
    }

    /** 回退候选：首选排最前，其余填过 Key 的按顺序跟上（去重） */
    fun fallbackCandidates(ctx: Context, pid: String): List<Provider> {
        val all = Db.get(ctx).providers().filter { it.key.isNotBlank() }
        val primary = providerFor(ctx, pid)
        return (listOfNotNull(primary) + all.filter { it.id != primary?.id }).distinctBy { it.id }
    }

    fun activeProvider(ctx: Context): Provider? {
        val id = Prefs.get(ctx).activeProvider
        val list = Db.get(ctx).providers()
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    fun activePersona(ctx: Context): Persona {
        val db = Db.get(ctx)
        val pr = Prefs.get(ctx)
        val id = pr.activePersona
        db.persona(id)?.let { return it }
        val found = db.personas().firstOrNull() ?: return Persona(id = "", name = "未命名")
        // 回写：否则 UI 用 fallback、Proactive 读 prefs，两边不是同一个人格
        if (pr.activePersona != found.id) pr.activePersona = found.id
        return found
    }

    /** 首次启动播种：内置人格 + 开场白 */
    suspend fun ensureSeed(ctx: Context) = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val pr = Prefs.get(ctx)
        // 只在第一次安装时播种。用户删光了就是删光了，不再偷偷塞回来
        if (db.get("seeded", "") != "1") {
            if (db.personas().isEmpty()) Persona.builtin().forEach { db.savePersona(it) }
            db.put("seeded", "1")
        }
        // 清理：内置的「大肥鱼」人格和鲸鱼娘皮肤已下线，老用户升级时一并移除
        if (db.get("purged_fish", "") != "1") {
            val fish = db.persona("builtin_fish")
            if (fish != null && fish.source == "builtin") {
                db.deletePersonaAll("builtin_fish")
                if (pr.activePersona == "builtin_fish") {
                    pr.activePersona = db.personas().firstOrNull()?.id ?: ""
                }
            }
            val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)
            if (sp.getString("pet_skin", "") == "deepseek") {
                sp.edit().putString("pet_skin", "elysia_q").commit()
            }
            if ((sp.getString("builtin_skin_persona", "") ?: "") == "builtin_fish") {
                sp.edit().putString("builtin_skin_persona", "").commit()
            }
            sp.edit().remove("ds_skin_persona").commit()
            db.put("purged_fish", "1")
        }
        if (pr.activePersona.isBlank() || db.persona(pr.activePersona) == null) {
            pr.activePersona = db.personas().firstOrNull()?.id ?: ""
        }
        // 合并双消息流（老版本聊天界面 pid#chat → pid，只跑一次）
        if (db.get("thread_merged", "") != "1") {
            db.mergeChatThreads()
            db.put("thread_merged", "1")
        }
        // 旧数据迁移：人格自带的 lore → 独立世界观并绑定（只跑一次）
        if (db.get("world_migrated", "") != "1") {
            db.personas().forEach { p ->
                if (p.worldId.isBlank() && p.lore.isNotEmpty()) {
                    val wid = "w_" + p.id
                    db.saveWorld(app.zhiyi.data.World(wid, p.name + " 的世界观", "从角色卡导入", p.lore))
                    db.savePersona(p.copy(worldId = wid, lore = emptyList()))
                }
            }
            db.put("world_migrated", "1")
        }

        val pid = pr.activePersona
        if (pid.isNotBlank() && db.msgCount(pid) == 0) {
            val p = db.persona(pid)
            if (p != null && p.firstMes.isNotBlank()) {
                db.addMsg(Msg(pid = pid, role = "assistant", content = p.firstMes))
                db.putNum("last_assistant_ts_$pid", System.currentTimeMillis())
            }
        }
    }

    suspend fun saveUserMsg(
        ctx: Context,
        text: String,
        images: List<String> = emptyList(),
        explicitPid: String? = null,
        /** 记忆隔离维度："" = 本机；"<openid>" = 某个 QQ 用户自己的记忆线 */
        scope: String = "",
    ): String = withContext(Dispatchers.IO) {
        val base = explicitPid ?: activePersona(ctx).id
        val pid = if (scope.isBlank()) base else "$base#$scope"
        val tk = threadKey(pid, cfg(ctx).chatMode)
        val imgJson = org.json.JSONArray().apply { images.forEach { put(it) } }.toString()
        Db.get(ctx).addMsg(Msg(pid = tk, role = "user", content = text, images = imgJson))
        Db.get(ctx).putNum("last_user_ts_$pid", System.currentTimeMillis())
        // 相识日：第一次主动说话的时间（只记一次）
        if (Db.get(ctx).getNum("first_chat_ts_$pid", 0) == 0L) {
            Db.get(ctx).putNum("first_chat_ts_$pid", System.currentTimeMillis())
        }
        // 被回应了，退避清零
        Db.get(ctx).putNum("pro_streak_$pid", 0)
        Db.get(ctx).putNum("pro_silence_until_$pid", 0)
        pid
    }

    /**
     * 主对话：拼上下文 → 流式生成 → 落库 → 跑记忆流水线
     */
    suspend fun reply(
        ctx: Context,
        onDelta: ((String) -> Unit)? = null,
        cancel: (() -> Boolean)? = null,
        task: String? = null,
        explicitPid: String? = null,
        /** 场景："" 聊天窗口 / "qq" QQ 端（线上打字，和面对面的提示词相反） */
        scene: String = "",
        /**
         * 记忆隔离维度。"" = 这台手机的主人；
         * 非空（QQ 的 openid）= 这个人有自己独立的一条记忆线：
         * 消息、事实、摘要、心境、主动搭话计数全部按 pid#scope 分开，
         * 但人格设定还是共享原来那一份——同一个人格服务多个人，各记各的。
         */
        scope: String = "",
    ): String = withContext(Dispatchers.IO) {
        val db = Db.get(ctx)
        val cfg = cfg(ctx)
        val persona = if (explicitPid != null) {
            db.persona(explicitPid) ?: throw LlmException("人格已删除，无法回复")
        } else activePersona(ctx)
        val realPid = persona.id
        if (realPid.isBlank()) throw LlmException("还没有人格")
        // pid 从这里往下代表"记忆维度"，persona 仍是原来那个人格
        val pid = if (scope.isBlank()) realPid else "$realPid#$scope"

        val candidates = fallbackCandidates(ctx, pid)
        if (candidates.isEmpty()) throw LlmException("还没配置 API，去设置里填一个")

        // 没人传 cancel 时默认跟随本协程：用户切走/重新生成后不该继续把回复跑完并落库
        val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
        val cancelled: () -> Boolean = cancel ?: { job?.isActive == false }

        lockFor(pid).withLock {
            val tk = threadKey(pid, cfg.chatMode)
            val hist = db.recentMsgs(tk, 60)
            val lastUserMsg = hist.lastOrNull { it.role == "user" }
            val lastUser = lastUserMsg?.content ?: ""

            // ---- 收尾闸门：互道晚安会让模型一直答下去，这里直接短路，不调模型 ----
            // 两个条件同时满足才拦（这轮是纯收尾语 + 她上一条也在收尾），否则正常对话里的"嗯"会被误杀
            val lastAi = hist.lastOrNull { it.role == "assistant" }?.content.orEmpty()
            if (ClosingGate.isClosing(lastUser) && ClosingGate.isAiClosing(lastAi)) {
                val r = ClosingGate.shortReply()
                db.addMsg(Msg(pid = tk, role = "assistant", content = r))
                db.putNum("last_assistant_ts_$pid", System.currentTimeMillis())
                db.putNum("close_streak_$pid", db.getNum("close_streak_$pid", 0) + 1)
                return@withLock r
            }
            db.putNum("close_streak_$pid", 0)
            // 最后一条用户消息带的图 → data URI（多模态模型看得到）
            val imageUris = lastUserMsg?.imageList()
                ?.mapNotNull { ImageStore.toDataUri(ctx, it) } ?: emptyList()

            Llm.effort = cfg.reasoningEffort     // 思考强度跟随设置
            // 先把这一句喂给「心」：情绪推进是纯规则，不额外发请求
            runCatching { app.zhiyi.memory.Heart.onUser(db, pid, lastUser) }
            val asm = Assembler(db, cfg)
            // 先算本轮 query 向量，再 build（原来是 build 之后才赋值，导致本轮用上一轮的向量）
            var qvec: FloatArray? = null
            if (cfg.embedEnabled) {
                val em = providerFor(ctx, pid)?.embedModel?.modelList()?.firstOrNull()
                if (em != null) qvec = runCatching { Llm(providerFor(ctx, pid)!!).embed(lastUser, em) }.getOrNull()
            }
            var msgs = asm.build(pid, tk, persona, hist, qvec, scene = scene)

            // 真人反应延迟：只给 UI 流式入口（通知直回/主动发话不需要"打字感"）
            if (onDelta != null) {
                val thinkMs = (900 + kotlin.random.Random.nextDouble() * 1500).toLong()
                val dl = System.currentTimeMillis() + thinkMs
                while (System.currentTimeMillis() < dl) {
                    if (cancel?.invoke() == true) throw LlmException("已取消")
                    delay(80)
                }
            }

            // 联网搜索（只在明显需要时，且严格限时：4.2s 内没结果就放弃，保证整条回复 ≤20s）
            if (lastUser.isNotBlank() && app.zhiyi.data.WebSearch.shouldSearch(lastUser)) {
                val sr = kotlinx.coroutines.withTimeoutOrNull(4200L) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.zhiyi.data.WebSearch.search(lastUser, 3)
                    }
                }
                if (!sr.isNullOrBlank()) {
                    msgs = listOf("system" to (
                        msgs.first().second +
                        "\n\n# 刚查到的网上信息（可以据此回答；不要照抄，简短带一句来源感即可）\n" + sr
                    )) + msgs.drop(1)
                }
            }
            if (task != null) msgs = listOf("system" to (msgs.first().second + "\n\n" + task)) + msgs.drop(1)


            // 模型回退：同一 provider 的主模型 + 回退模型逐个试，再换下一个 provider。
            // 只在还没吐出任何字时回退；已经说了一半就绝不换模型重说。
            var finalText = ""
            var used: Provider? = null
            var lastErr: LlmException? = null
            var emitted = false

            outer@ for (prov in candidates) {
                val llm = Llm(prov)
                if (prov.key.isBlank()) { lastErr = LlmException("API Key 是空的"); continue@outer }
                // 带图的消息必须走视觉模型：主模型若是纯文本，图片会被静默忽略（她只看到文字）
                val chatModels = if (imageUris.isNotEmpty()) {
                    prov.visionModel.modelList().ifEmpty { prov.chatModel.modelList() }
                } else {
                    prov.chatModel.modelList().ifEmpty { prov.cheapModel.modelList() }
                }
                if (chatModels.isEmpty()) { lastErr = LlmException("模型名没填"); continue@outer }

                val embedModel = prov.embedModel.modelList().firstOrNull()
                EmbedCtx.last = if (cfg.embedEnabled && embedModel != null)
                    runCatching { llm.embed(lastUser, embedModel) }.getOrNull() else null

                for (model in chatModels) {
                    try {
                        val sb = StringBuilder()
                        val text = llm.chat(
                            model = model,
                            messages = msgs,
                            temp = cfg.temperature.toDouble(),
                            maxTok = cfg.maxTokens,
                            stream = onDelta != null,
                            onDelta = { d -> sb.append(d); emitted = true; onDelta?.invoke(d) },
                            cancel = cancelled,
                            images = imageUris,
                        )
                        val out = (if (onDelta != null) sb.toString() else text).trim()
                        if (out.isBlank()) throw LlmException("模型返回了空内容，检查模型名")
                        finalText = out
                        used = prov
                        break@outer
                    } catch (e: LlmException) {
                        lastErr = e
                        if (emitted) throw e          // 已经说了一半，不回退
                        // 否则试下一个模型 / 下一个 provider
                    }
                }
            }

            val prov = used ?: throw (lastErr ?: LlmException("所有模型都失败了"))
            val llm = Llm(prov)

            if (job?.isActive == false) throw kotlinx.coroutines.CancellationException("回复已取消")
            // 整段跑英文 / 复述指令的，不落库也不给用户看
            if (app.zhiyi.llm.OutputGuard.reject(finalText)) {
                throw LlmException("模型输出异常（疑似跑英文或复述指令），已丢弃。换个模型或再说一次试试")
            }
            db.addMsg(Msg(pid = tk, role = "assistant", content = finalText))
            db.putNum("last_assistant_ts_$pid", System.currentTimeMillis())
            runCatching { app.zhiyi.memory.Heart.onAssistant(db, pid, true) }

            background.launch {
                runCatching {
                    val cheapModels = prov.cheapModel.modelList().ifEmpty { prov.chatModel.modelList() }
                    val embedModel = prov.embedModel.modelList().firstOrNull()
                    MemoryEngine(db, cfg).tick(
                        pid = pid,
                        thread = tk,
                        p = persona,
                        history = db.recentMsgs(tk, 40),
                        cheap = { s, u ->
                            var le: LlmException? = null
                            var out: String? = null
                            for (cm in cheapModels) {
                                try { out = llm.chat(cm, listOf("system" to s, "user" to u), 0.3, 800, false); break }
                                catch (e: LlmException) { le = e }
                            }
                            out ?: throw (le ?: LlmException("记忆模型都失败了"))
                        },
                        embed = { t -> if (embedModel != null) llm.embed(t, embedModel) else null },
                    )
                    // 后台刷新天气缓存（下次拼装就能感知到）
                    app.zhiyi.data.WeatherHelper.refresh(db, cfg.city)
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            }
            finalText
        }
    }
}