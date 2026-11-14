package app.zhiyi

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.zhiyi.core.ChatCore
import app.zhiyi.data.CfgData
import app.zhiyi.data.ChatStats
import app.zhiyi.data.Db
import app.zhiyi.data.Episode
import app.zhiyi.data.Fact
import app.zhiyi.data.Msg
import app.zhiyi.data.Persona
import app.zhiyi.data.PersonaRow
import app.zhiyi.data.Prefs
import app.zhiyi.data.Provider
import app.zhiyi.data.SummaryRow
import app.zhiyi.data.World
import app.zhiyi.persona.StCard
import app.zhiyi.proactive.Proactive
import app.zhiyi.proactive.ProactiveAlarm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppVm(app: Application) : AndroidViewModel(app) {

    private val ctx = app
    val db = Db.get(app)
    val prefs = Prefs.get(app)
    private val main = Handler(Looper.getMainLooper())

    var cfg by mutableStateOf(prefs.data); private set
    var persona by mutableStateOf<Persona?>(null); private set
    var providers by mutableStateOf<List<Provider>>(emptyList()); private set
    var personas by mutableStateOf<List<Persona>>(emptyList()); private set
    /** 主动发话测试的最近结果（设置页显示） */
    var proactiveLastHint by mutableStateOf("还没测试过")
    var worlds by mutableStateOf<List<World>>(emptyList()); private set
    var rows by mutableStateOf<List<PersonaRow>>(emptyList()); private set
    var currentWorld by mutableStateOf<World?>(null); private set
    var activeProvider by mutableStateOf<Provider?>(null); private set
    var messages by mutableStateOf<List<Msg>>(emptyList()); private set
    var streaming by mutableStateOf(""); private set
    var sending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var facts by mutableStateOf<List<Fact>>(emptyList()); private set
    var factsEnded by mutableStateOf(true); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var summaries by mutableStateOf<List<SummaryRow>>(emptyList()); private set
    var diaries by mutableStateOf<List<Triple<Long, String, String>>>(emptyList()); private set
    var statusLine by mutableStateOf(""); private set   // 顶栏状态：情绪 · 天气
    var pendingShare by mutableStateOf<String?>(null)  // 从别的 App 分享过来的待发文本
    var daysTogether by mutableStateOf(0); private set  // 相识第几天
    var moodHistory by mutableStateOf<List<Float>>(emptyList()); private set  // 心情曲线（最近 50 次）
    var life by mutableStateOf<List<String>>(emptyList()); private set  // 她最近的生活流
    var chatStats by mutableStateOf<ChatStats?>(null); private set  // 聊天统计
    var toast by mutableStateOf<String?>(null)
    /** 记忆界面当前查看的是哪个人格（默认跟随当前人格） */
    var memPid by mutableStateOf(""); private set
    private var reloadSeq = 0   // loadMoreFacts 用的代次
    private var chatSeq = 0     // 对话通道版本号
    private var memSeq = 0      // 记忆通道版本号
    /** 输入框草稿：放在 VM 里，切 tab 不会丢 */
    var draft by mutableStateOf("")
    var busy by mutableStateOf(false); private set

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { db.migratePredicates() }
        }
        viewModelScope.launch {
            ChatCore.ensureSeed(ctx)
            reload()
        }
    }

    fun providerFor(pid: String) = ChatCore.providerFor(ctx, pid)

    fun setProviderFor(pid: String, providerId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ChatCore.setProviderFor(ctx, pid, providerId) }
            reload()
        }
    }

    fun reload(onDone: (() -> Unit)? = null) = reloadInternal(chat = true, memory = true, onDone = onDone)

    /** 只刷新对话（消息/人格/列表），不读记忆数据。切换人格/进对话用，避免全量读 */
    fun reloadChat(onDone: (() -> Unit)? = null) = reloadInternal(chat = true, memory = false, onDone = onDone)

    /** 只刷新记忆数据（事实/回忆/摘要/日记/心情/生活/统计），不碰对话和列表。记忆页切换查看人格用 */
    fun reloadMemory() = reloadInternal(chat = false, memory = true, onDone = null)

    private fun reloadInternal(chat: Boolean, memory: Boolean, onDone: (() -> Unit)?) {
        // chat / memory 两条通道各用独立版本号，互不吞对方的 main.post
        val cSeq = if (chat) ++chatSeq else chatSeq
        val mSeq = if (memory) ++memSeq else memSeq
        val seq = ++reloadSeq
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) {
                val p = ChatCore.activePersona(ctx)
                val mp = memPid.takeIf { it.isNotBlank() && db.persona(it) != null } ?: p.id
                val tk = ChatCore.threadKey(p.id, prefs.data.chatMode)
                val msgs = if (chat) db.recentMsgs(tk, 120) else emptyList()
                val f = if (memory) db.factsPage(mp, 0, 200) else emptyList()
                val e = if (memory) db.allEpisodes(mp) else emptyList()
                val s = if (memory) db.allSummaries(mp) else emptyList()
                val d = if (memory) db.diaries(mp) else emptyList()
                val pv = if (chat) db.providers() else emptyList()
                val listRows = if (chat) db.personaRows() else emptyList()
                val ps = if (chat) listRows.map { it.persona } else emptyList()
                val ws = if (chat) db.worlds() else emptyList()
                val cw = if (chat) p.worldId.takeIf { it.isNotBlank() }?.let { db.world(it) } else null
                val moodNote = if (memory) db.get("moodnote_$mp", "") else ""
                val mood = if (memory) db.get("mood_$mp", "").toDoubleOrNull() else null
                val emoji = when { mood == null -> ""; mood < 0.3 -> "😢"; mood < 0.5 -> "😔"; mood < 0.7 -> "🙂"; else -> "😊" }
                val weather = if (memory) app.zhiyi.data.WeatherHelper.cached(db, prefs.data.city) ?: "" else ""
                val firstTs = if (memory) db.getNum("first_chat_ts_$mp", 0) else 0
                val days = if (firstTs > 0) ((System.currentTimeMillis() - firstTs) / 86_400_000L + 1).toInt().coerceAtLeast(1) else 0
                val mh = if (memory) runCatching { org.json.JSONArray(db.get("mood_hist_$mp", "[]")) }.getOrDefault(org.json.JSONArray()) else org.json.JSONArray()
                val moods = (0 until mh.length()).map { mh.getJSONObject(it).optDouble("m", 0.5).toFloat() }
                val lifeRows = if (memory) db.recentLife(mp, 7) else emptyList()
                val stats = if (memory) db.chatStats(mp) else ChatStats()
                // 主线程不查库：activeProvider / cfg 在 IO 线程算好带出来
                val activeProv = if (chat) ChatCore.providerFor(ctx, p.id) else activeProvider
                val cfgSnap = if (chat) prefs.data else cfg
                main.post {
                    if (chat && cSeq == chatSeq) {
                        personas = ps; worlds = ws; rows = listRows; currentWorld = cw
                        persona = p; messages = msgs; providers = pv
                        activeProvider = activeProv; cfg = cfgSnap
                    }
                    if (memory && mSeq == memSeq) {
                        facts = f
                        factsEnded = f.size < 200
                        episodes = e; summaries = s; diaries = d
                        statusLine = listOf(if (moodNote.isNotBlank()) emoji + moodNote else "", weather).filter { it.isNotBlank() }.joinToString(" · ")
                        daysTogether = days
                        moodHistory = moods
                        life = lifeRows
                        chatStats = stats
                    }
                    onDone?.invoke()
                }
            } }.onFailure { main.post { onDone?.invoke() } }
        }
    }

    private var loadingMore = false

    /** 事实分页：滚动到底加载更多。带互斥 + 代次校验，避免重复 key 崩溃 / 跨人格串数据 */
    fun loadMoreFacts() {
        if (factsEnded || loadingMore) return
        val mp = memPid.takeIf { it.isNotBlank() } ?: ChatCore.activePersona(ctx).id
        val gen = reloadSeq
        val offset = facts.size
        loadingMore = true
        viewModelScope.launch {
            try {
                val more = withContext(Dispatchers.IO) { db.factsPage(mp, offset, 100) }
                val curMem = memPid.ifBlank { ChatCore.activePersona(ctx).id }
                if (gen != reloadSeq || mp != curMem) return@launch  // 已过期/切人格，丢弃
                if (more.isEmpty()) { factsEnded = true; return@launch }
                facts = (facts + more).distinctBy { it.id }
            } finally {
                loadingMore = false
            }
        }
    }

    fun clearError() { error = null }

    // ---------------- 备份与恢复 ----------------
    fun exportBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching { db.exportAll() }.getOrNull()
            } ?: run { toast = "导出失败"; return@launch }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null
                }.getOrDefault(false)
            }
            toast = if (ok) "已导出备份" else "导出失败"
        }
    }

    fun importBackup(uri: android.net.Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?.let { String(it, Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) { toast = "读不到这个文件"; return@launch }
            val n = withContext(Dispatchers.IO) {
                runCatching { db.importAll(text) }.getOrDefault(-1)
            }
            if (n < 0) { toast = "备份文件不完整或不合法"; return@launch }
            reload()
            toast = "已恢复 $n 条消息"
        }
    }

    // ---------------- 对话 ----------------
    /** 手动触发一次主动发话（跳过评分），结果写进 proactiveLastHint */
    fun testProactive() {
        viewModelScope.launch {
            proactiveLastHint = "测试中…"
            val r = runCatching {
                withContext(Dispatchers.IO) {          // 必须离开主线程，否则 NetworkOnMainThreadException
                    kotlinx.coroutines.withTimeout(90_000) {
                        app.zhiyi.proactive.Proactive.maybeFire(ctx, force = true)
                    }
                }
            }
            proactiveLastHint = r.fold(
                onSuccess = { ok ->
                    if (ok) "成功发出了一条"
                    else {
                        val e = runCatching {
                            Db.get(ctx).get("pro_last_error_" + ChatCore.activePersona(ctx).id, "")
                        }.getOrDefault("")
                        if (e.isNotBlank()) "失败：" + e.substringAfter('|', e) else "没发出来（评分通过但生成失败，看日志 ZhiYi）"
                    }
                },
                onFailure = { "失败：${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }

    fun send(text: String, images: List<String> = emptyList()) {
        val t = text.trim()
        if ((t.isEmpty() && images.isEmpty()) || sending) return
        viewModelScope.launch {
            sending = true
            streaming = ""
            error = null
            // try 必须从第一行就生效：落用户消息/刷新列表任一抛异常，
            // 否则 finally 不执行 → sending 永远 true，输入框永久禁用
            try {
                withContext(Dispatchers.IO) { ChatCore.saveUserMsg(ctx, t, images) }
                reloadWithMessages()
                ChatCore.reply(
                    ctx = ctx,
                    onDelta = { d -> main.post { streaming += d } },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e                      // 取消不是错误，别弹"发送失败"
            } catch (e: Throwable) {
                main.post { error = e.message ?: "发送失败" }
            } finally {
                // 关键：把「刷出完整回复」和「关掉流式气泡」放进同一帧（reloadChat 的 main.post 里），
                // 否则两者竞争 → 完整回复和流式残影重叠「原文闪一下」，或回复先消失再出现。
                reloadChat {
                    streaming = ""
                    sending = false
                }
            }
        }
    }

    /** 只刷新消息列表，避免流式期间整页数据抖动 */
    private suspend fun reloadWithMessages() = withContext(Dispatchers.IO) {
        val p = ChatCore.activePersona(ctx)
        val msgs = db.recentMsgs(ChatCore.threadKey(p.id, prefs.data.chatMode), 120)
        main.post { persona = p; messages = msgs }
    }


    fun copyText(text: String) {
        runCatching {
            val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("知忆", text))
            toast = "已复制"
        }
    }

    /**
     * 撤回：删掉这条消息，以及它之后紧跟着的回复（直到下一条用户消息前）。
     * 用户消息 → 连带那条回答一起没；助手消息 → 只删它自己和后面连着的助手消息。
     */
    fun recall(m: Msg) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val next = db.firstUserIdAfter(m.pid, m.id)
                val to = next?.minus(1) ?: Long.MAX_VALUE
                db.deleteMsgsRange(m.pid, m.id, to)
            }
            reload()
            toast = "已撤回"
        }
    }

    /** 重新生成：删掉这条助手回复，让她重说（不碰用户消息和历史） */
    fun regenerate(m: Msg) {
        if (sending || m.role != "assistant") return
        viewModelScope.launch {
            sending = true
            streaming = ""
            error = null
            try {
                withContext(Dispatchers.IO) { db.deleteMsgsRange(m.pid, m.id, m.id) }
                reloadWithMessages()
                ChatCore.reply(ctx = ctx, onDelta = { d -> main.post { streaming += d } })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                main.post { error = e.message ?: "重新生成失败" }
            } finally {
                reload { streaming = ""; sending = false }
            }
        }
    }

    /** 清空某个身份的会话（只删聊天记录，记忆库不动） */
    fun clearChat(pid: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // 小说线程 + 聊天线程都清（不依赖当前模式）
                db.clearMsgs(pid)
                val p = db.persona(pid)
                if (p != null && p.firstMes.isNotBlank()) {
                    db.addMsg(Msg(pid = pid, role = "assistant", content = p.firstMes))
                }
            }
            reload()
            toast = "会话已清空（记忆保留）"
        }
    }

    /** 清空某个身份的记忆库（事实/回忆/摘要），聊天记录不动 */
    fun clearMemory(pid: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.clearMemory(pid) }
            reload()
            toast = "记忆库已清空（会话保留）"
        }
    }

    /** 记忆界面切换查看对象 */
    fun viewMemory(pid: String) {
        memPid = pid
        reloadMemory()
    }

    // ---------------- 设置 ----------------
    /** 外部（桌宠服务/通知）改了 prefs 之后，把内存里的 cfg 重新读一遍，开关才会跟着动 */
    fun refreshCfg() { runCatching { cfg = prefs.data } }

    fun saveCfg(v: CfgData) {
        prefs.data = v
        cfg = v
        if (v.proactiveEnabled) {
            ProactiveAlarm.schedule(ctx)
            app.zhiyi.proactive.ProactiveService.start(ctx)
        } else {
            ProactiveAlarm.cancel(ctx)
            app.zhiyi.proactive.ProactiveService.stop(ctx)
        }
        if (v.petEnabled && android.provider.Settings.canDrawOverlays(ctx))
            app.zhiyi.pet.PetService.start(ctx)
        else if (!v.petEnabled)
            app.zhiyi.pet.PetService.stop(ctx)
    }

    fun saveProvider(p: Provider, activate: Boolean = true) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.saveProvider(p)
                if (activate || prefs.activeProvider.isBlank()) prefs.activeProvider = p.id
            }
            reload()
            toast = "已保存：${p.name}"
        }
    }

    fun newId() = UUID.randomUUID().toString().take(8)

    fun deleteProvider(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.deleteProvider(id)
                if (prefs.activeProvider == id) prefs.activeProvider = ""
            }
            reload()
        }
    }

    fun activateProvider(id: String) {
        prefs.activeProvider = id
        activeProvider = ChatCore.activeProvider(ctx)
        toast = "已切换到 ${activeProvider?.name ?: "未命名"}"
    }

    fun switchPersona(id: String) {
        // 生成中切人格：清掉旧人格的流式残影，避免旧文字流进新人格气泡
        streaming = ""
        sending = false
        viewModelScope.launch {
            prefs.activePersona = id
            // 切人格时，记忆查看也回到当前人格——否则主页/记忆页会串成上一个人的数据
            memPid = ""
            withContext(Dispatchers.IO) {
                val p = db.persona(id)
                val tk = ChatCore.threadKey(id, prefs.data.chatMode)
                if (p != null && db.recentMsgs(tk, 1).isEmpty() && p.firstMes.isNotBlank())
                    db.addMsg(Msg(pid = tk, role = "assistant", content = p.firstMes))
            }
            reload()
            toast = "现在和你说话的是 ${db.persona(id)?.name ?: ""}"
        }
    }

    fun savePersona(p: Persona) {
        viewModelScope.launch {
            // 编辑保存不再做同名去重：这里的 id 已经定了，用户只是改了名字，
            // 万一撞上另一个人，删掉别人一整份记忆是灾难。
            withContext(Dispatchers.IO) { db.savePersona(p) }
            reload()
            toast = "人格已保存"
        }
    }

    /** 回滚人格到某个历史版本（产生新版本，历史不动） */
    fun restorePersona(pid: String, rev: Int) {
        viewModelScope.launch {
            val p = withContext(Dispatchers.IO) { db.restorePersona(pid, rev) }
            if (p == null) { toast = "这个版本读不出来" } else { toast = "已回到 r$rev" }
            reload()
        }
    }

    /** 清空她从相处里学到的调整 */
    fun resetEvolve(pid: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { app.zhiyi.memory.PersonaEvolve.reset(db, pid) }
            toast = if (ok) "她回到你写的样子了" else "她本来就没变过"
            reload()
        }
    }

    fun deletePersona(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.deletePersonaAll(id)
                if (prefs.activePersona == id) prefs.activePersona = ""
                // 清理皮肤对这个已删人格的绑定
                runCatching {
                    val sp = ctx.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE)
                    if (sp.getString("builtin_skin_persona", "") == id) {
                        sp.edit().putString("builtin_skin_persona", "").commit()
                    }
                    app.zhiyi.pet.PetSkin.list(ctx).forEach { sk ->
                        if (sk.personaId == id && sk.id != app.zhiyi.pet.PetSkin.BUILTIN) {
                            app.zhiyi.pet.PetSkin.setPersona(ctx, sk.id, "")
                        }
                    }
                }
            }
            ChatCore.ensureSeed(ctx)
            reload()
        }
    }

    /** 导入 SillyTavern PNG 角色卡（V2/V3/V1 通用） */
    fun importCard(bytes: ByteArray) {
        viewModelScope.launch {
            busy = true
            val res = withContext(Dispatchers.IO) { StCard.fromPng(bytes) }
            busy = false
            if (res == null) {
                toast = "导入失败：PNG 里没有 chara 字段（不是 SillyTavern 卡？）"
                return@launch
            }
            val p = adoptSameName(res.first)
            withContext(Dispatchers.IO) { db.savePersona(p) }
            prefs.activePersona = p.id
            seedFirstIfEmpty(p.id)
            reload()
            toast = "已导入「${p.name}」${if (p.lore.isNotEmpty()) "，含 ${p.lore.size} 条世界书" else ""}"
        }
    }

    /**
     * 导入人格 JSON。三种情况都要认：
     *  1) 本 App 自己导出的（含 summary / sayPrefix / worldId 等独有字段）
     *  2) SillyTavern v1/v2/v3 角色卡
     *  3) 卡里带 character_book / lore —— 顺手落地成一个世界观并绑定
     */
    fun importJson(text: String) {
        viewModelScope.launch {
            val t = text.trim()
            val obj = runCatching { org.json.JSONObject(t) }.getOrNull()
            if (obj == null) { toast = "导入失败：不是合法 JSON"; return@launch }

            val p = withContext(Dispatchers.IO) {
                val own = Persona.fromJson(t)
                val looksOwn = own.name.isNotBlank() && (
                    obj.has("sayPrefix") || obj.has("summary") || obj.has("worldId") ||
                        obj.has("firstMes") || obj.has("catchphrase")
                    )
                if (looksOwn) {
                    own.copy(id = "p_" + System.currentTimeMillis().toString(36))
                } else {
                    StCard.fromJsonString(t)
                }
            }
            if (p == null) {
                toast = "导入失败：既不是本 App 的人格文件，也不是角色卡"
                return@launch
            }

            // 世界观：优先取世界书条目，没有就用空
            val lore = p.lore
            val worldId = if (lore.isNotEmpty()) {
                val wid = "w_" + System.currentTimeMillis().toString(36)
                withContext(Dispatchers.IO) {
                    db.saveWorld(
                        World(id = wid, name = "${p.name} 的世界观", desc = "导入时带来的设定", entries = lore)
                    )
                }
                wid
            } else p.worldId

            val finalP = adoptSameName(p.copy(worldId = worldId, lore = emptyList()))
            withContext(Dispatchers.IO) { db.savePersona(finalP) }
            prefs.activePersona = finalP.id
            seedFirstIfEmpty(finalP.id)
            reload()
            toast = "已导入「${finalP.name}」" + if (lore.isNotEmpty()) "，含 ${lore.size} 条设定" else ""
        }
    }

    /** 用户主动点「恢复内置人格」才写回来，且用新 id —— 不会带回旧聊天 */
    /**
     * 同名人格：**换人不换 id**。
     *
     * 导入一个和已有角色同名的人格时，要的是"她换了一版设定"，不是"来了个新人和一个旧人的尸体"。
     * 所以：人格内容用新的（savePersona 会记一个新版本，可回滚），
     *       但 id 沿用旧的 —— 记忆是按 pid 存的，id 不变，记忆就一条都不会丢。
     *
     * 之前这里是 deletePersonaAll，等于"导入同名人格 = 把她的记忆清空"，
     * 这正是最不该发生的：设定可以换，经历不能换。
     */
    private suspend fun adoptSameName(p: Persona): Persona {
        val n = p.name.trim()
        if (n.isEmpty()) return p
        val hit = withContext(Dispatchers.IO) {
            db.personas().firstOrNull { it.id != p.id && it.name.trim() == n }
        } ?: return p
        if (prefs.activePersona == p.id) prefs.activePersona = hit.id
        if (memPid == p.id) memPid = hit.id
        return p.copy(id = hit.id)
    }

    /** 激活人格时补开场白（导入/切换/恢复三条路径共用） */
    private suspend fun seedFirstIfEmpty(pid: String) {
        withContext(Dispatchers.IO) {
            val p = db.persona(pid) ?: return@withContext
            val tk = ChatCore.threadKey(pid, prefs.data.chatMode)
            if (db.recentMsgs(tk, 1).isEmpty() && p.firstMes.isNotBlank()) {
                db.addMsg(Msg(pid = tk, role = "assistant", content = p.firstMes))
            }
        }
    }

    fun restoreBuiltins() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val stamp = System.currentTimeMillis().toString(36)
                Persona.builtin().forEach { p -> db.savePersona(p.copy(id = p.id + "_" + stamp)) }
                db.put("seeded", "1")
                prefs.activePersona = ""
            }
            ChatCore.ensureSeed(ctx)
            reload()
            toast = "已恢复内置人格"
        }
    }

    // ---------------- 世界观 ----------------
    fun newWorldId() = "w_" + UUID.randomUUID().toString().take(8)

    fun saveWorld(w: World) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.saveWorld(w) }
            reload()
        }
    }

    fun deleteWorld(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.deleteWorld(id) }
            reload()
            toast = "世界观已删除（绑定它的人格保留）"
        }
    }

    /** 给当前人格换一个世界观；传空字符串 = 解绑 */
    fun bindWorld(worldId: String) {
        val p = persona ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { db.savePersona(p.copy(worldId = worldId)) }
            reload()
            toast = if (worldId.isBlank()) "已解绑世界观" else "已切换世界观"
        }
    }

    /** 从别处导入的世界观（分享过来的 JSON） */
    fun importWorldJson(text: String) {
        viewModelScope.launch {
            val w = withContext(Dispatchers.IO) { runCatching { World.fromJson(text) }.getOrNull() }
            if (w == null || w.entries.isEmpty()) { toast = "导入失败：这不是世界观文件"; return@launch }
            val fresh = w.copy(id = newWorldId(), name = w.name.ifBlank { "导入的世界观" })
            withContext(Dispatchers.IO) { db.saveWorld(fresh) }
            persona?.let { p ->
                withContext(Dispatchers.IO) { db.savePersona(p.copy(worldId = fresh.id)) }
            }
            reload()
            toast = "已导入世界观「${fresh.name}」，${fresh.entries.size} 条设定"
        }
    }

    /** 分享给别的 App / 别的设备 */
    fun sharePersona(p: Persona) {
        shareFile("persona_${p.name}.json", p.toJson(), "分享人格：${p.name}")
    }

    fun shareWorld(w: World) {
        shareFile("world_${w.name}.json", w.toJson(), "分享世界观：${w.name}")
    }

    private fun shareFile(fileName: String, json: String, subject: String) {
        viewModelScope.launch {
            runCatching {
                val dir = java.io.File(ctx.cacheDir, "shared").apply { mkdirs() }
                val f = java.io.File(dir, fileName.replace('/', '_'))
                withContext(Dispatchers.IO) { f.writeText(json) }
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    ctx, "app.zhiyi.fileprovider", f
                )
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                    putExtra(android.content.Intent.EXTRA_TEXT, "用「知忆」导入这个文件即可")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = android.content.Intent.createChooser(send, subject)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(chooser)
            }.onFailure { error = "分享失败：${it.message}" }
        }
    }

    /**
     * 自动分流：先看是不是角色卡，再看是不是世界观。
     * 之前只按 `"entries" + "keys"` 判断，结果带 character_book 的角色卡会被误判成世界观。
     */
    fun importAny(text: String) {
        val t = text.trim()
        if (t.isBlank()) { toast = "导入失败：内容为空"; return }
        val obj = runCatching { org.json.JSONObject(t) }.getOrNull()
        if (obj == null) { toast = "导入失败：不是合法 JSON（开头应是 { ）"; return }

        val data = obj.optJSONObject("data") ?: obj
        val spec = obj.optString("spec", "")
        val looksPersona = spec.startsWith("chara_card") ||
            data.has("first_mes") || data.has("description") ||
            data.has("personality") || data.has("mes_example") ||
            data.has("sayPrefix") || data.has("summary") || data.has("worldId") ||
            (data.has("name") && !obj.has("entries") && !obj.has("updated"))
        // 只有"有 entries 且完全没有人格特征"才当纯世界观
        val looksWorld = (obj.has("entries") || obj.has("world")) && !looksPersona

        when {
            looksPersona -> importJson(t)
            looksWorld -> importWorldJson(t)
            else -> toast = "导入失败：认不出这是人格卡还是世界观"
        }
    }

    /** 记忆回溯：按 src 字段（逗号分隔的消息 id）取原文（IO 上跑） */
    suspend fun sourcesFor(src: String): List<Msg> =
        withContext(Dispatchers.IO) {
            val ids = src.split(",").mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) emptyList() else db.msgsByIds(ids.take(20))
        }

    fun deleteFact(id: Long) { viewModelScope.launch { withContext(Dispatchers.IO) { db.deleteFact(id) }; reload() } }

    fun editFact(id: Long, subject: String, predicate: String, obj: String, importance: Double) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.updateFact(id, subject.trim(), predicate.trim(), obj.trim(), importance)
            }
            reload()
            toast = "记忆已纠正"
        }
    }
    fun deleteEpisode(id: Long) { viewModelScope.launch { withContext(Dispatchers.IO) { db.deleteEpisode(id) }; reload() } }

    /** 手动测试主动发话，立刻看到效果 */

    fun proactiveScoreHint(): String {
        val pid = ChatCore.activePersona(ctx).id
        val (s, why) = Proactive.score(ctx, pid)
        return "当前触发评分 %.2f（%s）".format(s, why)
    }
}
