package app.zhiyi.pet

import app.zhiyi.voice.MimoTts
import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationCompat
import app.zhiyi.R
import app.zhiyi.core.ChatCore
import app.zhiyi.data.Db
import app.zhiyi.data.modelList
import app.zhiyi.llm.Llm
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.random.Random

/**
 * 桌宠 v2：像素立绘 + 5 条气泡（含旁白）+ 点击对话 + 侧边探头进场 + 对角退出按钮。
 * 对话走该人格的聊天线程（explicitPid）。
 */
class PetService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var root: FrameLayout
    private lateinit var pet: ImageView
    private lateinit var exitBtn: TextView
    private lateinit var params: WindowManager.LayoutParams
    private var anim: ValueAnimator? = null
    private val h = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var pid = ""
    private var curFace = "calm"
    private var blinkUntil = 0L
    private var busy = false
    private var lastInteractTs = 0L
    private var dlg: android.app.AlertDialog? = null
    private var shown = false

    private var skinId = PetSkin.BUILTIN
    private val lines = listOf(
        "嗯？在忙吗～", "我在这儿陪着你哦", "要不要休息一下？", "今天也要开心呀",
        "…看什么呢，这么入神", "我是不是很可爱？", "累了就靠过来嘛", "别一直盯着屏幕啦",
    )

    private val prefs get() = getSharedPreferences("cfg", MODE_PRIVATE)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            startForegroundCompat()   // 先满足 5s 规则，再退出
            hidePet(); return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RELOAD) {
            // 换皮肤/改尺寸：先移除旧视图再重建，避免 stop+start 竞态
            runCatching { if (::root.isInitialized) wm.removeView(root) }
            runCatching { closeRing() }
            runCatching { hideInput() }
            bubbleRoot?.let { runCatching { wm.removeView(it) } }; bubbleRoot = null
            hideExit.run()                                // P0-8：别让旧气泡/× 卡在屏上
            anim?.cancel()
            runCatching { stopVoice() }   // 不清的话：speech 还活着但超时被清空 -> 麦克风永久占用
            videoMode = false; videoJob?.cancel(); videoJob = null
            h.removeCallbacksAndMessages(null)
            shown = false
        }
        startForegroundCompat()
        if (!shown) {
            if (!android.provider.Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
            val ok = runCatching { show() }.onFailure { android.util.Log.e("PetService", "show失败", it) }.isSuccess
            if (!ok) { stopSelf(); return START_NOT_STICKY }
            shown = true
        }
        return START_STICKY
    }

    private fun show() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pid = prefs.getString("persona", "") ?: ""
        skinId = prefs.getString("pet_skin", PetSkin.BUILTIN) ?: PetSkin.BUILTIN
        // 皮肤绑定了人格 -> 对话走那个人格
        PetSkin.list(this).firstOrNull { it.id == skinId }?.personaId?.takeIf { it.isNotBlank() }?.let { pid = it }
        val size = dp(prefs.getInt("pet_size", 100))

        root = FrameLayout(this)

        // 立绘（唯一子视图，窗口只包住它）
        pet = ImageView(this)
        pet.setImageDrawable(PetSkin.face(this, skinId, "calm"))
        pet.scaleType = ImageView.ScaleType.FIT_CENTER
        pet.setPadding(dp(4), dp(4), dp(4), dp(4))
        root.addView(pet, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        applyPixel()


        params = WindowManager.LayoutParams(
            size + dp(8), size + dp(8),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("pet_x", dp(30))
            y = prefs.getInt("pet_y", dp(300))
        }
        clamp()
        val dm = resources.displayMetrics
        val targetX = params.x
        val fromRight = targetX > dm.widthPixels / 2
        params.x = if (fromRight) dm.widthPixels else -params.width
        wm.addView(root, params)
        playEnter(fromRight, targetX)
        setupTouch()
        startBreathing(); startBlink(); startMoodPoll()
        positionExit()
    }

    private fun applyPixel() { (pet.drawable as? BitmapDrawable)?.paint?.isFilterBitmap = false }

    private fun setFace(name: String) {
        if (System.currentTimeMillis() < blinkUntil && name != "blink") return
        val d = PetSkin.face(this, skinId, name) ?: return
        curFace = name
        pet.setImageDrawable(d)
        (pet.drawable as? BitmapDrawable)?.paint?.isFilterBitmap = false
    }

    // ---------- 触摸 ----------
    private var tapCount = 0
    private var tapR: Runnable? = null
    private var autoScreen = false
    private var autoR: Runnable? = null

    // ---- 环形菜单状态 ----
    private var menuOpen = false
    private var baseW = 0; private var baseH = 0
    private var ringBaseX = 0; private var ringBaseY = 0
    private var ringDir = ""

    private fun setupTouch() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var moved = false
        val longPress = Runnable { openRing() }

        pet.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; menuOpen = false; ringDir = ""
                    anim?.pause()
                    h.postDelayed(longPress, 1500)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (menuOpen) {
                        val lp = ringLp
                        if (lp != null) {
                            val rcx = lp.x + lp.width / 2f
                            val rcy = lp.y + lp.height / 2f
                            val dir = ringRv?.keyForOffset(e.rawX - rcx, e.rawY - rcy) ?: ""
                            if (dir != ringDir) { ringDir = dir; ringRv?.setSelected(dir) }
                        }
                    } else {
                        // 8dp 才判定拖动：轻微手抖不会取消长按（这是之前长按唤不出的原因）
                        if (!moved && (abs(dx) > dp(8) || abs(dy) > dp(8))) {
                            moved = true; h.removeCallbacks(longPress)
                        }
                        if (moved) {
                            params.x = startX + dx; params.y = startY + dy
                            runCatching { wm.updateViewLayout(root, params) }
                            updateBubblePos()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    h.removeCallbacks(longPress)
                    tapCount = 0; tapR?.let { h.removeCallbacks(it) }; tapR = null
                    if (anim?.isPaused == true) anim?.resume()
                    if (menuOpen) { closeRing() }
                }
                MotionEvent.ACTION_UP -> {
                    h.removeCallbacks(longPress)
                    if (anim?.isPaused == true) anim?.resume()
                    when {
                        menuOpen -> {
                            val dir = ringDir
                            closeRing()
                            if (dir.isNotEmpty()) runMenuAction(dir)
                        }
                        moved -> {
                            tapCount = 0; tapR?.let { h.removeCallbacks(it) }; tapR = null
                            snapToEdge()
                            prefs.edit().putInt("pet_x", params.x).putInt("pet_y", params.y).apply()
                            positionExit()
                        }
                        else -> onPetTap()
                    }
                }
            }
            true
        }
    }

    // ---------- 环形菜单 ----------
    /** 当前人格：皮肤绑了就用绑定的，否则实时读 prefs（切换人格会自动跟随） */
    private fun currentPid(): String {
        val bound = runCatching {
            PetSkin.list(this).firstOrNull { it.id == skinId }?.personaId
        }.getOrNull()
        if (!bound.isNullOrBlank()) return bound
        return prefs.getString("persona", "") ?: ""
    }

    data class RingSpec(val key: String, val icon: String, val label: String)

    private fun ringSpecs(): List<RingSpec> {
        val soundOn = prefs.getBoolean("tts_pet", false)
        return listOf(
            RingSpec("text",  "💬", "说话"),
            RingSpec("voice", "🎤", "语音"),
            RingSpec("sound", if (soundOn) "🔊" else "🔇", "声音"),
            RingSpec("app",   "🪟", "打开App"),
            RingSpec("size",  "📏", "大小"),
            RingSpec("hide",  "🫥", "隐藏"),
        )
    }

    /** 桌宠出声开关。菜单里点一下就切，不用回 App 翻设置 */
    private fun toggleSound() {
        val on = !prefs.getBoolean("tts_pet", false)
        prefs.edit().putBoolean("tts_pet", on).apply()
        if (!on) MimoTts.stop()
        // 和 hidePet() 一样通知 AppVm 重读配置：否则设置页下一次 saveCfg 会拿内存里的旧
        // cfg.ttsPet 把这个值写回去，声音就莫名被关掉
        runCatching { sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)) }
        // 用旁白样式显示，这样这条提示本身不会被念出来
        showBubble(if (on) "声音打开了，以后我说话你都能听见" else "好，我先安静一会儿", true)
    }

    private var ringWin: FrameLayout? = null
    private var ringLp: WindowManager.LayoutParams? = null
    private var ringRv: RingView? = null

    private fun openRing() {
        if (menuOpen) return
        menuOpen = true
        ringDir = ""                      // 清掉上一次的选择，否则长按直接松手会重放
        ringRv?.setSelected("")
        val dm = resources.displayMetrics
        val size = dp(300)
        // 记住原位，桌宠先移到屏幕中央，保证菜单完整可见
        ringBaseX = params.x; ringBaseY = params.y
        val centerX = (dm.widthPixels - params.width) / 2
        val centerY = (dm.heightPixels - params.height) / 2
        val wrap = FrameLayout(this)
        val dmw = dm.widthPixels
        val atRightSide = (params.x + params.width / 2) > dmw / 2
        val rv = RingView(this, ringSpecs(), clockwise = !atRightSide)
        wrap.addView(rv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,   // 允许超出屏幕边，保证桌宠在正中心
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = centerX + params.width / 2 - size / 2
            y = centerY + params.height / 2 - size / 2
        }
        runCatching { wm.addView(wrap, lp) }
        ringWin = wrap; ringLp = lp; ringRv = rv
        animatePetTo(centerX, centerY)     // 桌宠滑到菜单正中央
        bounce()
    }

    private fun closeRing() {
        if (!menuOpen) return
        menuOpen = false
        ringWin?.let { runCatching { wm.removeView(it) } }
        ringWin = null; ringLp = null; ringRv = null
        if (ringBaseX != 0 || ringBaseY != 0) animatePetTo(ringBaseX, ringBaseY)   // 回原位
    }

    /** 平滑移动桌宠；菜单窗口同步跟随，保证桌宠始终在菜单正中央 */
    private fun animatePetTo(tx: Int, ty: Int) {
        val sx = params.x; val sy = params.y
        if (sx == tx && sy == ty) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            addUpdateListener {
                val f = it.animatedValue as Float
                params.x = (sx + (tx - sx) * f).toInt()
                params.y = (sy + (ty - sy) * f).toInt()
                runCatching { wm.updateViewLayout(root, params) }
                ringLp?.let { lp ->
                    lp.x = params.x + params.width / 2 - lp.width / 2
                    lp.y = params.y + params.height / 2 - lp.height / 2
                    ringWin?.let { w -> runCatching { wm.updateViewLayout(w, lp) } }
                }
            }
            start()
        }
    }

    private fun runMenuAction(key: String) {
        when (key) {
            "text" -> showInput()
            "voice" -> startVoice()
            "sound" -> toggleSound()
            "app" -> openApp()
            "size" -> pickSize()
            "hide" -> hidePet()
        }
    }

    /** 截屏 -> 多模态识屏 -> 气泡回答 */
    private fun shotMode(): String {
        val m = prefs.getString("shot_mode", "auto") ?: "auto"
        return if (m == "auto") {
            if (ScreenCapture.shizukuReady()) "adb" else "projection"
        } else m
    }

    /** 名字启发式：粗挑可能支持视觉的模型；一个都挑不出来就全试一遍 */
    private fun looksVision(m: String): Boolean {
        val n = m.lowercase()
        return listOf("vl", "vision", "-4v", "4o", "omni", "glm-4v", "llava", "internvl", "gemini", "claude", "gpt-4")
            .any { n.contains(it) }
    }


    /** 屏幕描述 → 事实库。只捞与「对方本人」有关且以后还用得上的，商品/广告/别人说的全丢 */
    private suspend fun extractFromScreen(pid: String, desc: String) {
        val provider = ChatCore.providerFor(this, pid) ?: return
        val m = provider.cheapModel.modelList().firstOrNull()
            ?: provider.chatModel.modelList().firstOrNull() ?: return
        val sys = """
从下面的手机屏幕描述里，只抽取「关于使用者本人、且以后还有用」的信息。
规则：
- 只抽使用者自己的事：他的计划、行程、待办、身体状况、工作学业、正在处理的麻烦
- 屏幕上的商品、价格、广告、博主推荐、别人说的话 —— 一律不要
- 一条都没有就返回 []
- predicate 只能是 "计划"（他接下来要做的事、行程、待办）
- 不要写"状态"：屏幕上一瞬间的状态对长期记忆没价值，还会覆盖他自己说过的状态
- 长期事实（职业、健康状况、喜好、往事）一律不写 —— 那些得他自己说，屏幕上看不出来
- 否则返回 JSON 数组，最多 2 条，每项 {"predicate":"计划","obj":"一句话，不超过20字"}
只输出 JSON，不要别的。
""".trimIndent()
        val raw = runCatching { Llm(provider).chat(m, listOf("system" to sys, "user" to desc), 0.2, 260) }
            .getOrNull() ?: return
        val arr = app.zhiyi.memory.JsonUtil.array(raw) ?: return
        val db = ChatCore.db(this)
        for (i in 0 until minOf(arr.length(), 2)) {
            val o = arr.optJSONObject(i) ?: continue
            val obj = o.optString("obj", "").trim()
            if (obj.length < 2) continue
            val pred = app.zhiyi.memory.FactCategories.normalize(o.optString("predicate", "其他"))
            runCatching {
                db.upsertFact(
                    app.zhiyi.data.Fact(
                        pid = pid,
                        subject = "用户",
                        predicate = pred,
                        obj = obj.take(60),
                        importance = 0.45,   // 屏幕猜的，别压过用户亲口说的
                        src = "screen",
                    )
                )
            }
        }
    }

    // ================= 视频模式 =================
    // 双击进入。每秒 2 帧，攒满 16 帧（8 秒）拼一张 4×4 图版发给模型。
    // 关键：**采集和模型调用是两条并行流水线**——
    // 模型还在回答这一批的时候，下一批 16 帧已经在采了，不会空等。
    // 用 CONFLATED 队列：模型比采集慢时只保留最新一批，不会越积越多、越说越滞后。
    @Volatile private var videoMode = false
    private var videoJob: kotlinx.coroutines.Job? = null

    private fun toggleVideoMode() {
        if (videoMode) stopVideoMode() else startVideoMode()
    }

    private fun stopVideoMode() {
        videoMode = false
        videoJob?.cancel(); videoJob = null
        showBubble("（不看了）", true)
    }

    private fun startVideoMode() {
        if (videoMode) return
        pid = currentPid()
        val mode = shotMode()
        val ready = if (mode == "adb") ScreenCapture.shizukuReady() else ScreenCapture.isReady()
        if (!ready) {
            showBubble(
                if (mode == "adb") "（ADB 截图还没授权：装好 Shizuku 并授权后我再试）"
                else "（先在知忆里允许截屏，我才能一起看）", true)
            openApp(); return
        }
        if (mode != "adb") runCatching { startForegroundCompat(withProjection = true) }
        videoMode = true
        showBubble("（一起看…再双击我就停）", true)

        videoJob = scope.launch {
            val batches = kotlinx.coroutines.channels.Channel<ArrayList<android.graphics.Bitmap>>(
                capacity = kotlinx.coroutines.channels.Channel.CONFLATED
            )
            // ---- 生产者：一直采，不停 ----
            val producer = launch(Dispatchers.IO) {
                try {
                    while (isActive && videoMode) {
                        val frames = ArrayList<android.graphics.Bitmap>()
                        for (i in 0 until 16) {
                            if (!isActive || !videoMode) break
                            // 每 4 帧复查前台应用：一批要采 8 秒，只在开头查等于没查
                            if (i % 4 == 0) {
                                val rk = RiskGate.riskOf(runCatching { RiskGate.topApp() }.getOrNull())
                                if (rk != null) {
                                    videoMode = false
                                    frames.forEach { f -> runCatching { f.recycle() } }
                                    withContext(Dispatchers.Main) { showBubble("（切到${rk}了，先不看了）", true) }
                                    return@launch
                                }
                            }
                            val t0 = System.currentTimeMillis()
                            runCatching { ScreenCapture.captureBitmap(this@PetService, 180) }
                                .getOrNull()?.let { frames.add(it) }
                            val wait = 500L - (System.currentTimeMillis() - t0)
                            if (wait > 0) delay(wait)
                        }
                        if (!videoMode) { frames.forEach { f -> runCatching { f.recycle() } }; break }
                        if (frames.size < 8) {          // 截图基本失败，别刷模型
                            frames.forEach { f -> runCatching { f.recycle() } }
                            delay(1000); continue
                        }
                        // CONFLATED：队列里只留最新一批，旧的直接被丢弃
                        val dropped = batches.trySend(frames)
                        if (dropped.isFailure) { frames.forEach { f -> runCatching { f.recycle() } }; break }
                    }
                } finally {
                    batches.close()
                }
            }

            // ---- 消费者：拼图 + 说话（慢，但不挡住采集）----
            for (batch in batches) {
                if (!videoMode) { batch.forEach { f -> runCatching { f.recycle() } }; break }
                try {
                    val uri = withContext(Dispatchers.IO) { buildSheetUri(batch) }
                    if (uri != null && videoMode) {
                        // llm.chat 是阻塞读，必须切 IO，否则卡在主线程（scope 是 Main）-> 整个视频模式装死
                        runCatching { withContext(Dispatchers.IO) { talkAboutSheet(uri) } }
                    }
                } finally {
                    batch.forEach { f -> runCatching { f.recycle() } }
                }
            }
            producer.cancel()
        }
    }

    /** 16 帧 → 4×4 图版，单帧 180 宽，整张约 720×1600，压 JPEG 82 */
    private fun buildSheetUri(frames: List<android.graphics.Bitmap>): String? {
        return try {
            val cols = 4
            val rows = 4
            val fw = frames[0].width
            val fh = frames[0].height
            val out = android.graphics.Bitmap.createBitmap(fw * cols, fh * rows, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawColor(android.graphics.Color.BLACK)
            val paint = android.graphics.Paint().apply { isFilterBitmap = true }
            frames.take(cols * rows).forEachIndexed { i, b ->
                val x = (i % cols) * fw
                val y = (i / cols) * fh
                c.drawBitmap(b, null, android.graphics.Rect(x, y, x + fw, y + fh), paint)
            }
            val bos = java.io.ByteArrayOutputStream()
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos)
            out.recycle()
            "data:image/jpeg;base64," + android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (e: Throwable) {
            android.util.Log.w("ZhiYi", "拼图版失败", e); null
        }
    }

    /** 图版 → 多模态描述 → 主模型带人格回一句 */
    private suspend fun talkAboutSheet(dataUri: String) {
        val provider = ChatCore.providerFor(this, pid) ?: ChatCore.activeProvider(this) ?: return
        val llm = Llm(provider)
        val explicit = provider.visionModel.modelList()
        val all = (provider.chatModel.modelList() + provider.cheapModel.modelList()).distinct()
        val vm = explicit.ifEmpty { all.filter { looksVision(it) }.ifEmpty { all } }
        var desc = ""
        for (m in vm) {
            val r = runCatching {
                llm.chat(
                    m,
                    listOf("user" to """
这是手机屏幕连续截的 20 帧，按时间顺序从左到右、从上到下排成 5×4 图版（每格间隔约 0.5 秒，合计约 10 秒）。
客观描述：这段画面在讲什么？发生了什么变化？有没有字幕、人物、场景切换？
3~4 句，只讲事实，不要评价。
""".trimIndent()),
                    maxTok = 400,
                    images = listOf(dataUri),
                )
            }.getOrNull()?.trim()
            if (!r.isNullOrBlank()) { desc = r; break }
        }
        if (desc.isBlank()) return
        val cm = provider.chatModel.modelList().ifEmpty { provider.cheapModel.modelList() }
        val db0 = ChatCore.db(this)
        val cfg0 = ChatCore.cfg(this)
        val persona0 = db0.persona(pid) ?: ChatCore.activePersona(this)
        val tk0 = ChatCore.threadKey(pid, cfg0.chatMode)
        // desc 作为 userText 传进去：场景判定、语气示例都靠它，传空串人格完全不生效
        val sys0 = runCatching {
            app.zhiyi.memory.Assembler(db0, cfg0).systemPrompt(pid, tk0, persona0, desc, scene = "pet_screen")
        }.getOrDefault("")
        val task = """
# 现在的任务
你正和对方一起看视频/刷手机，刚刚这 8 秒屏幕上是：
$desc

用你自己的性格说 1~2 句：吐槽、感慨、好奇、搭一句话都行。
不要复述上面这段描述，不要念说明书，就像坐在旁边一起看的人随口说的话。
""".trimIndent()
        // 桌宠也要有上下文：带上最近几轮真实对话，否则她永远接不上刚聊的话
        val petHistory = runCatching { db0.recentMsgs(tk0, 6) }.getOrDefault(emptyList())
        for (m in cm) {
            val out = runCatching {
                val msgs = ArrayList<Pair<String, String>>()
                if (sys0.isNotBlank()) msgs.add("system" to sys0)
                petHistory.forEach { h ->
                    if (h.content.isNotBlank())
                        msgs.add((if (h.role == "assistant") "assistant" else "user") to h.content.take(300))
                }
                msgs.add("user" to task)
                llm.chat(m, msgs, maxTok = 260)
            }.getOrNull()?.trim()
            if (!out.isNullOrBlank() && !app.zhiyi.llm.OutputGuard.reject(out)) {
                // 她说过的话要进聊天记录，下次（含回到聊天页）才接得上
                runCatching { db0.addMsg(app.zhiyi.data.Msg(pid = tk0, role = "assistant", content = out)) }
                withContext(Dispatchers.Main) {
                    showReply(out); setFace(faceForReply(out))
                }
                return
            }
        }
    }

    private fun captureAndUnderstand(deep: Boolean = false, auto: Boolean = false) {
        if (busy) return
        pid = currentPid()
        if (!auto) lastInteractTs = System.currentTimeMillis()
        busy = true
        if (!auto) showBubble(if (deep) "（让我仔细看一会儿…）" else "（让我看看…）", true)
        val mode = shotMode()
        val ready = if (mode == "adb") ScreenCapture.shizukuReady() else ScreenCapture.isReady()
        if (!ready) {
            showBubble(
                if (mode == "adb") "（ADB 截图还没授权：装好 Shizuku 并授权后我再试）"
                else "（先在知忆里允许截屏，我才能看见你的屏幕）", true)
            openApp(); busy = false; return
        }
        if (mode != "adb") runCatching { startForegroundCompat(withProjection = true) }
        scope.launch {
            try {
                // 支付/银行/密码页不截：她不需要看这些，截了反而有风险
                val top = withContext(Dispatchers.IO) { runCatching { RiskGate.topApp() }.getOrNull() }
                val risk = RiskGate.riskOf(top)
                if (risk != null) {
                    showBubble("（${risk}呀，这个我就不看了）", true)
                    return@launch
                }
                if (top == null) {
                    android.util.Log.w("ZhiYi", "识屏闸门拿不到前台包名（Shizuku 不可用？），本次不拦截")
                    if (!prefs.getBoolean("riskgate_warned", false)) {
                        prefs.edit().putBoolean("riskgate_warned", true).apply()
                        showBubble("（这次没确认到是哪个界面，屏幕上要是有机密内容，先别点我）", true)
                    }
                }
                // 深度看的时候连拍 3 帧：视频/动画里单帧信息太少，多帧才能看出"在发生什么"
                val frameCount = if (deep) 3 else 1
                val shots = withContext(Dispatchers.IO) {
                    val list = ArrayList<java.io.File>()
                    for (i in 0 until frameCount) {
                        if (i > 0) kotlinx.coroutines.delay(320)
                        // 必须给唯一文件名：默认名固定，连拍 3 次全写同一个文件，
                        // 第一帧编码完就 delete，后两帧 readBytes 直接 FileNotFoundException -> 静默只剩 1 帧
                        val nm = if (frameCount > 1) "pet_shot_$i.png" else "pet_shot.png"
                        val f = if (shotMode() == "adb") ScreenCapture.captureAdb(this@PetService, nm)
                                else ScreenCapture.captureToCache(this@PetService, nm)
                        if (f == null) break
                        list.add(f)
                    }
                    list
                }
                if (shots.isEmpty()) { showBubble("（没截到，可能得重新授权一次）", true); return@launch }
                val isPng = shotMode() == "adb"
                val prefix = if (isPng) "data:image/png;base64," else "data:image/jpeg;base64,"
                val dataUris = withContext(Dispatchers.IO) {
                    shots.mapNotNull { f ->
                        try {
                            // 直接发原图：1080p PNG 单张 3~5MB，连拍 3 张 base64 后能到 20MB，
                            // 网关多半直接 413/超时。统一重采样到宽 1080 再压 JPEG 75。
                            val raw = f.readBytes()
                            val big = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                            if (big == null) return@mapNotNull null   // 解不出来就丢弃，别把 PNG 标成 JPEG 发
                            val bw = big.width.coerceAtLeast(1)
                            val w = if (bw > 1080) 1080 else bw
                            val h = (big.height.toFloat() * w / bw).toInt().coerceAtLeast(1)
                            val bos = java.io.ByteArrayOutputStream()
                            try {
                                val fit = android.graphics.Bitmap.createScaledBitmap(big, w, h, true)
                                try {
                                    fit.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, bos)
                                } finally {
                                    if (fit !== big) runCatching { fit.recycle() }
                                }
                            } finally {
                                runCatching { big.recycle() }   // 异常路径也必须回收，一帧就 10MB
                            }
                            "data:image/jpeg;base64," +
                                android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
                        } catch (e: Throwable) {
                            null
                        } finally {
                            runCatching { f.delete() }      // 读失败也必须删，否则临时目录越攒越多
                        }
                    }
                }
                if (dataUris.isEmpty()) { showBubble("（图读不出来，再试一次？）", true); return@launch }
                val provider = ChatCore.providerFor(this@PetService, pid) ?: ChatCore.activeProvider(this@PetService)
                if (provider == null) { showBubble("（还没配置模型）", true); return@launch }
                // ---------- 第一步：多模态模型只做「客观描述」 ----------
                val desc = withContext(Dispatchers.IO) {
                    val llm = Llm(provider)
                    // 关键修正：原来是 cheapModel 优先。便宜模型多是纯文本小模型，
                    // 拿它识图只会胡编，这就是"识屏/识视频很一般"的主因。
                    // 优先用 API 里配的视觉模型；留空就按名字挑，再留空就全试
                    val explicit = provider.visionModel.modelList()
                    val allModels = (provider.chatModel.modelList() + provider.cheapModel.modelList()).distinct()
                    val visionModels = explicit.ifEmpty {
                        allModels.filter { looksVision(it) }.ifEmpty { allModels }
                    }
                    var d = ""
                    var lastE: Throwable? = null
                    for (m in visionModels) {
                        try {
                            val r = llm.chat(
                                m,
                                listOf("user" to if (deep) """
这是同一块屏幕连续截的 ${dataUris.size} 张图（间隔 0.3 秒）。
客观描述：是什么 App、在做什么、屏幕上的关键内容（标题、字幕、正文、人物、进度条）。
如果是视频或正在播放的内容，说明画面里在发生什么、有没有字幕、进度大概在哪、前后帧有没有变化。
3~5 句。只讲事实，不要评价，不要用任何人设口吻。
""".trimIndent() else """
客观描述这张手机截图：是什么 App、在做什么、屏幕上的关键内容（标题、字幕、文字）。
如果是视频，说明画面在发生什么、有没有字幕。
2~4 句。只讲事实，不要评价，不要用人设口吻。
""".trimIndent()),
                                maxTok = 450,
                                images = dataUris,
                            ).trim()
                            if (r.isNotBlank()) { d = r; break }
                        } catch (e: Throwable) { lastE = e }
                    }
                    if (d.isBlank()) throw (lastE ?: IllegalStateException("识屏失败（模型没返回内容）"))
                    d
                }

                // ---------- 第二步：主模型（带人格）看着描述回话 ----------
                val reply = withContext(Dispatchers.IO) {
                    val llm = Llm(provider)
                    val chatModels = provider.chatModel.modelList().ifEmpty { provider.cheapModel.modelList() }
                    val db0 = ChatCore.db(this@PetService)
                    val cfg0 = ChatCore.cfg(this@PetService)
                    val persona0 = db0.persona(pid) ?: ChatCore.activePersona(this@PetService)
                    // userText 必须传：场景判定和语气示例都靠它。传空串 = 人格完全不生效
                    val tk0 = ChatCore.threadKey(pid, cfg0.chatMode)
                    val sys0 = runCatching {
                        app.zhiyi.memory.Assembler(db0, cfg0).systemPrompt(pid, tk0, persona0, desc, scene = "pet_screen")
                    }.getOrDefault("")
                    val task = if (deep) """
# 现在的任务
对方把手机屏幕给你看了一眼，屏幕上：
$desc

你正和他一起看（可能一起刷视频、看文章、打游戏）。用**你自己的性格和语气**说 2~3 句：
- 像坐在旁边一起看的人：吐槽、好奇、表个态、逗他一句都行
- 不要复述这段描述，也不要念说明书
- 说你的感受和看法
""".trimIndent() else """
# 现在的任务
对方把手机屏幕给你看了一眼，屏幕上：
$desc

用**你自己的性格和语气**说 1~2 句自然反应（吐槽/好奇/关心都行），别像在描述画面。
""".trimIndent()
                    var out = ""
                    var lastE: Throwable? = null
                    val petHistory = runCatching { db0.recentMsgs(tk0, 6) }.getOrDefault(emptyList())
                    for (m in chatModels) {
                        try {
                            val msgs = ArrayList<Pair<String, String>>()
                            if (sys0.isNotBlank()) msgs.add("system" to sys0)
                            petHistory.forEach { h ->
                                if (h.content.isNotBlank())
                                    msgs.add((if (h.role == "assistant") "assistant" else "user") to h.content.take(300))
                            }
                            msgs.add("user" to task)
                            val r = llm.chat(m, msgs, maxTok = if (deep) 500 else 350).trim()
                            if (r.isNotBlank()) { out = r; break }
                        } catch (e: Throwable) { lastE = e }
                    }
                    if (out.isBlank()) throw (lastE ?: IllegalStateException("人格模型没返回内容"))
                    runCatching { db0.addMsg(app.zhiyi.data.Msg(pid = tk0, role = "assistant", content = out)) }
                    out
                }

                val safeReply = if (app.zhiyi.llm.OutputGuard.reject(reply)) "（看不太清呢）" else reply
                showReply(safeReply)
                setFace(faceForReply(reply))
                // ---------- 第三步：从屏幕里捞「对方自己身上、以后还有用」的信息 ----------
                // 她看完了东西，得能记住有用的部分（视频里"绿色大肥鱼把结果返回核心"的最小版）
                if (desc.length > 20) {
                    // 不能 await：这里面是一次完整 LLM 请求（最坏 90s），
                    // 而 busy 要等它结束才复位 -> 期间点桌宠全部被吞
                    scope.launch {
                        withTimeoutOrNull(25_000) {
                            withContext(Dispatchers.IO) { runCatching { extractFromScreen(pid, desc) } }
                        }
                    }
                }
            } catch (e: Throwable) {
                val msg = e.message ?: ""
                if (msg.contains("人格已删除") || msg.contains("还没人格")) {
                    pid = ""                       // 退回跟随当前人格
                    showBubble("（我好像被换了身份…点我再试一次）", true)
                } else showBubble("（看不太清…$msg）", true)
            } finally { busy = false }
        }
    }

    // ---------- 单击 / 双击 / 三击 ----------

    /**
     * 统一的连击判定。
     *
     * 之前是「上一击到这一击 < 320ms 就算双击」，于是三击的第三下会重新起一个计时器，
     * 最后变成「进了视频模式 + 又顺手识了一次屏」，两件事同时发生。
     *
     * 改成计数 + 一个滑动超时：每次点击只是把计时往后推，等真的停手了才按次数分发。
     * 代价是单击要多等 TAIL_MS，换来 2 击和 3 击互不打架。
     */
    private fun onPetTap() {
        tapCount++
        tapR?.let { h.removeCallbacks(it) }
        val r = Runnable {
            val n = tapCount
            tapCount = 0
            tapR = null
            if (videoMode) {
                // 一起看的时候只认「双击停」——否则一次误触就把视频打断
                if (n == 2) stopVideoMode()
                return@Runnable
            }
            when {
                n <= 1 -> captureAndUnderstand()          // 单击：看一眼屏幕
                n == 2 -> toggleVideoMode()               // 双击：进入/退出视频模式
                else -> startVoice()                      // 三击：直接说话（不想展开环形菜单时最快）
            }
        }
        tapR = r
        h.postDelayed(r, TAP_TAIL_MS)
    }

    // ---------- 单击 / 弹跳 ----------
    private fun onTap() {
        lastInteractTs = System.currentTimeMillis()
        bounce()
        setFace("surprised")
        h.postDelayed({ applyMoodFace() }, 1800)
        captureAndUnderstand()
    }

    private fun bounce() {
        pet.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
            pet.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }.start()
    }

    // ---------- 语音转文字（系统识别器，不跳界面） ----------
    private var speech: android.speech.SpeechRecognizer? = null
    private var voiceJob: Runnable? = null          // 超时兜底
    /** 连击的收尾窗口：最后一次点击后等这么久才分发。太短会切碎三击，太长单击会发木 */
    private val TAP_TAIL_MS = 380L
    private var voiceSeq = 0                        // 会话序号：旧实例迟到的回调直接丢掉
    private var voiceReady = false                  // 是否收到 onReadyForSpeech
    private var voiceFatal = false                  // 本机识别器是否已判定不可用
    private var voiceFatalAt = 0L                   // 判定时间（5 分钟后允许重试，免得装个语音服务就永远回不来）
    private var voiceTipShown = false

    /** 各 ROM 的错误码含义差别很大，给具体原因，别一律"没听清" */
    private fun voiceErrText(code: Int): String = when (code) {
        android.speech.SpeechRecognizer.ERROR_AUDIO -> "麦克风读不到声音"
        android.speech.SpeechRecognizer.ERROR_CLIENT -> "这手机的语音识别不给第三方用"
        android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "没拿到麦克风权限"
        android.speech.SpeechRecognizer.ERROR_NETWORK -> "识别要联网，网络不通"
        android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "识别联网超时"
        android.speech.SpeechRecognizer.ERROR_NO_MATCH -> "没听清"
        android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器被占用"
        android.speech.SpeechRecognizer.ERROR_SERVER -> "识别服务出错"
        android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没听到你说话"
        else -> "识别失败($code)"
    }

    private fun stopVoice() {
        voiceSeq++                                  // 之后的回调一律忽略
        voiceJob?.let { h.removeCallbacks(it) }
        voiceJob = null
        val r = speech
        speech = null
        voiceReady = false
        runCatching { r?.cancel() }
        runCatching { r?.destroy() }
    }

    private fun startVoice() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showBubble("（给我个麦克风权限，弹窗点允许就好）", true)
            // 让 MainActivity 弹系统授权框（Service 自己不能弹），比丢用户去设置页里翻好
            runCatching {
                startActivity(Intent(this, Class.forName("app.zhiyi.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("open", "mic"))
            }
            return
        }
        // 正在听、或已排队等 openVoice、或有超时在跑：再点一次都算取消。
        // 原来只判 speech != null，但 openVoice 是延后 150ms 跑的，150ms 内连点会排两个
        // openVoice，第二个把第一个 destroy 掉，第一个的 5s 超时成了孤儿 -> 5 分钟后误判"不可用"
        if (speech != null || voiceJob != null) {
            stopVoice()
            showBubble("（那不听了）", true)
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            showBubble("（这手机没有系统语音识别，直接打字吧）", true)
            return
        }
        if (voiceFatal && System.currentTimeMillis() - voiceFatalAt < 5 * 60_000L) {
            showBubble("（这手机的语音识别不给我用，下滑打字吧）", true)
            return
        }
        if (voiceFatal) voiceFatal = false          // 过 5 分钟再给它一次机会
        showBubble("（听着呢…）", true)
        // 150ms 是为了等上一步的动画/气泡排完，太短部分机器会 ERROR_CLIENT
        voiceJob = Runnable { runCatching { openVoice() }.onFailure { showBubble("（语音起不来：${it.message}）", true) } }
        h.postDelayed(voiceJob!!, 150)
    }

    private fun openVoice() {
        if (!::wm.isInitialized) return
        runCatching { speech?.destroy() }
        speech = null
        voiceReady = false
        val r = runCatching { android.speech.SpeechRecognizer.createSpeechRecognizer(this) }.getOrNull()
        if (r == null) { showBubble("（语音识别起不来，直接打字吧）", true); return }
        speech = r
        val mySeq = ++voiceSeq
        fun stale() = mySeq != voiceSeq
        r.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(b: android.os.Bundle?) {
                if (stale()) return
                val list = b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                // 有的机型第一位是空串，不能直接 firstOrNull()
                val t = list?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                stopVoice()
                if (t.isNotEmpty()) {
                    sendText(t)
                } else {
                    showBubble("（没听清，再说一次？）", true)
                }
            }

            override fun onError(error: Int) {
                if (stale()) return
                val wasReady = voiceReady
                stopVoice()
                // 这几个错误码说明"这台机器上第三方根本用不了系统识别"
                // 只有 ERROR_CLIENT（第三方被拒）才算本机不可用；
                // ERROR_SERVER 是服务端抽风/断网，锁 5 分钟毫无道理
                if (error == android.speech.SpeechRecognizer.ERROR_CLIENT) {
                    voiceFatal = true
                    voiceFatalAt = System.currentTimeMillis()
                    showBubble("（这手机的语音识别不给我用…下滑打字吧）", true)
                    return
                }
                if (error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    showBubble("（识别器还占着，等我一下再试）", true)
                    return
                }
                // 从没 ready 过 + 权限正常 -> 基本可以判定服务不可用
                if (!wasReady && (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH ||
                        error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                ) {
                    voiceFatal = true
                    voiceFatalAt = System.currentTimeMillis()
                    showBubble("（听不到声音…可能系统识别没给权限，下滑打字更快）", true)
                    return
                }
                showBubble("（${voiceErrText(error)}）", true)
            }

            override fun onReadyForSpeech(p: android.os.Bundle?) {
                voiceReady = true
                // 真的开始听了，把超时续到 12 秒（说完会自动结束）
                voiceJob?.let { h.removeCallbacks(it) }
                voiceJob = Runnable { if (speech != null) { stopVoice(); showBubble("（等太久啦，先不听了）", true) } }
                h.postDelayed(voiceJob!!, 12_000L)
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: android.os.Bundle?) {}
            override fun onEvent(t: Int, b: android.os.Bundle?) {}
        })
        val i = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            .putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            .putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            .putExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        // 主线程已就绪，直接开（SpeechRecognizer 必须在主线程调用）
        runCatching { r.startListening(i) }.onFailure {
            stopVoice()
            showBubble("（语音起不来：${it.message}）", true)
        }
        // 5 秒内连 onReadyForSpeech 都没有 = 服务没响应，别让"听着呢"挂一整天
        voiceJob = Runnable {
            if (speech != null && !voiceReady) {
                stopVoice()
                voiceFatal = true
                voiceFatalAt = System.currentTimeMillis()
                showBubble("（系统语音识别没响应，下滑打字吧）", true)
            }
        }
        h.postDelayed(voiceJob!!, 5_000L)
    }

    // ---------- 气泡（独立小窗口，不占桌宠窗口） ----------
    private var bubbleRoot: LinearLayout? = null
    private var bubbleLp: WindowManager.LayoutParams? = null

    private fun showBubble(text: String, narration: Boolean) { showBubble(text, narration, false) }

    private fun showBubble(
        text: String,
        narration: Boolean,
        fromUser: Boolean,
        speak: Boolean = true,
    ) {
        if (text.isBlank()) return
        // 只念她自己的台词：旁白（动作/环境）念出来会很怪，用户的话也没必要重复
        if (speak && !narration && !fromUser && prefs.getBoolean("tts_pet", false)) {
            runCatching { MimoTts.speak(this, currentPid(), text) }
        }
        var box = bubbleRoot
        if (box == null) {
            box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(2), dp(4), dp(2))
            }
            bubbleRoot = box
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            bubbleLp = lp
            runCatching { wm.addView(box, lp) }
        }
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(
                when {
                    narration -> Color.parseColor("#9A8B95")
                    fromUser -> Color.parseColor("#FFFFFF")
                    else -> Color.parseColor("#4A3340")
                }
            )
            textSize = if (narration) 11f else 12.5f
            if (narration) typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
            maxWidth = if (narration) dp(200) else dp(230)
            setLineSpacing(0f, 1.15f)
            gravity = Gravity.START
            setPadding(dp(11), dp(7), dp(11), dp(7))
            elevation = dp(3).toFloat()
            background = if (narration) null else GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                if (fromUser) {
                    setColor(Color.parseColor("#F2A0C0"))
                } else {
                    setColor(Color.parseColor("#FFFFFF"))
                    setStroke(dp(1), Color.parseColor("#F2D0DE"))
                }
            }
            alpha = 0f
        }
        box.addView(tv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })
        tv.animate().alpha(1f).setDuration(160).start()
        while (box.childCount > 5) box.removeViewAt(0)
        updateBubblePos()
        // 气泡窗口随内容量测后重新定位（贴在立绘上方）
        box.post { updateBubblePos() }
        h.postDelayed({
            tv.animate().alpha(0f).setDuration(400).withEndAction {
                runCatching { box.removeView(tv) }
                if (box.childCount == 0) {
                    runCatching { wm.removeView(box) }
                    bubbleRoot = null
                }
            }.start()
        }, 7000)
    }

    /** 气泡窗口贴在桌宠正上方；气泡太高时改为覆盖在上方（不超出屏幕） */
    private fun updateBubblePos() {
        val box = bubbleRoot ?: return
        val lp = bubbleLp ?: return
        val dm = resources.displayMetrics
        val bh = if (box.height > 0) box.height else dp(40)
        val bw = if (box.width > 0) box.width else dp(160)
        val sw = dm.widthPixels
        val margin = dp(6)
        val petCenter = params.x + params.width / 2
        // 重点：气泡靠向桌宠那一侧，绝不让气泡把桌宠挤走
        lp.x = when {
            petCenter > sw * 2 / 3 -> (sw - bw - margin)                 // 贴右：气泡右对齐
            petCenter < sw / 3 -> margin                                  // 贴左：气泡左对齐
            else -> (petCenter - bw / 2).coerceIn(margin, (sw - bw - margin).coerceAtLeast(margin))
        }
        // 气泡太靠上时翻到桌宠下方
        lp.y = if (params.y - bh - dp(4) < dp(8)) {
            (params.y + params.height + dp(4)).coerceAtMost(dm.heightPixels - bh - dp(8))
        } else {
            (params.y - bh - dp(4)).coerceAtLeast(dp(4))
        }
        runCatching { wm.updateViewLayout(box, lp) }
    }

    /** 把回复拆旁白 + 台词，分别入气泡 */
    private fun showReply(reply: String) {
        val parts = Regex("(\\*[^*]+\\*|（[^）]*）|\\([^)]*\\))").findAll(reply)
        val say = ArrayList<String>()
        fun push(t: String, narration: Boolean) {
            if (t.isBlank()) return
            if (!narration) say.add(t)
            // 逐段出现时先别出声：每段的 speak 都会 stop() 掉上一段，
            // 「（顿了顿）」「A。」「B。」这种只念得最后一段。攒完再一次性念。
            showBubble(t, narration, false, false)
        }
        if (parts.none()) {
            push(reply.trim(), false)
        } else {
            var last = 0
            for (m in parts) {
                push(reply.substring(last, m.range.first).trim(), false)
                push(m.value.trim('*', '（', '）', '(', ')'), true)
                last = m.range.last + 1
            }
            push(reply.substring(last).trim(), false)
        }
        if (say.isNotEmpty() && prefs.getBoolean("tts_pet", false)) {
            runCatching { MimoTts.speak(this, currentPid(), say.joinToString(" ")) }
        }
    }

    // ---------- 输入 / 对话 ----------
    // ---------- 自定义输入浮窗（不是系统弹窗） ----------
    private var inputWin: FrameLayout? = null

    private fun showInput() {
        if (inputWin != null) return
        val dm = resources.displayMetrics
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#F2FFFFFF"))
                setStroke(dp(2), Color.parseColor("#E8B4CA"))
            }
            elevation = dp(10).toFloat()
        }
        val et = EditText(this).apply {
            hint = "和她说点什么…"
            setTextColor(Color.parseColor("#4A3340"))
            setHintTextColor(Color.parseColor("#A8929E"))
            textSize = 14f
            background = null
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(dp(2), dp(8), dp(2), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = TextView(this).apply {
            text = "→"
            setTextColor(Color.WHITE)
            textSize = 17f
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(
                Color.parseColor("#F7B8D2"), Color.parseColor("#EE8FB4"),
            )).apply { shape = GradientDrawable.OVAL }
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            setOnClickListener {
                val t = et.text.toString().trim()
                hideInput(); if (t.isNotEmpty()) sendText(t)
            }
        }
        card.addView(et); card.addView(send)

        val wrap = FrameLayout(this).apply {
            addView(card, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val width = dp(292)
        val lp = WindowManager.LayoutParams(
            width, WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,   // 点外面能收到 ACTION_OUTSIDE
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (params.x + params.width / 2 - width / 2).coerceIn(0, (dm.widthPixels - width).coerceAtLeast(0))
            y = (params.y - dp(76)).coerceAtLeast(dp(4))
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        val okIn = runCatching { wm.addView(wrap, lp) }.isSuccess
        if (!okIn) { showBubble("（输入框弹不出来，先回 App 看看）", true); return }
        inputWin = wrap
        et.requestFocus()
        h.postDelayed({
            runCatching {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120)
        // 点输入框外面（收到返回键等）时收起
        wrap.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_OUTSIDE) { hideInput(); true } else false }
    }

    private fun hideInput() {
        val w = inputWin ?: return
        inputWin = null
        runCatching {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(w.windowToken, 0)
        }
        runCatching { wm.removeView(w) }
    }

    /** 按回复内容挑表情（关键词优先，兜底 mood） */
    private fun faceForReply(t: String): String = when {
        Regex("哈哈|嘿嘿|笑|开心|太好|喜欢|爱|棒|厉害|可爱").containsMatchIn(t) -> "happy"
        Regex("哼|生气|讨厌|烦|别|不许|不行").containsMatchIn(t) -> "angry"
        Regex("难过|唉|伤心|对不起|抱歉|哭|失落").containsMatchIn(t) -> "sad"
        Regex("真的吗|什么|惊|居然|竟然|！|!|？|[?]").containsMatchIn(t) -> "surprised"
        Regex("困|睡|累|哈欠|晚安").containsMatchIn(t) -> "sleepy"
        Regex("不好意思|害羞|那个|嗯…|呃").containsMatchIn(t) -> "shy"
        else -> "calm"
    }

    private fun sendText(text: String) {
        if (busy) return
        pid = currentPid()          // 实时跟随当前人格
        lastInteractTs = System.currentTimeMillis()
        busy = true
        showBubble(text, false, fromUser = true)
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ChatCore.saveUserMsg(this@PetService, text, emptyList(), explicitPid = pid)
                }
                setFace("calm")
                val reply = withContext(Dispatchers.IO) {
                    ChatCore.reply(this@PetService, explicitPid = pid)
                }
                showReply(reply)
                setFace(faceForReply(reply))
            } catch (e: Throwable) {
                showBubble("（她说不出话了…${e.message ?: ""}）", true)
            } finally {
                busy = false
            }
        }
    }

    // ---------- 退出按钮位置：贴右→左，贴左→右 ----------
    private var exitWin: TextView? = null
    private var exitLp: WindowManager.LayoutParams? = null

    /** 关闭按钮：风格化圆角矩形 ×，浮在桌宠外侧（不遮挡立绘） */
    private fun positionExit() {
        if (exitWin != null) { relayoutExit(); return }
        val dm = resources.displayMetrics
        val btn = TextView(this).apply {
            text = "×"
            setTextColor(Color.parseColor("#8A5A6E"))
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(1))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()          // 圆角矩形
                setColor(Color.parseColor("#F2FFF7FA"))
                setStroke(dp(1), Color.parseColor("#E8B4CA"))
            }
            elevation = dp(4).toFloat()
            setOnClickListener { hidePet() }
        }
        val lp = WindowManager.LayoutParams(
            dp(34), dp(26),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        exitWin = btn
        exitLp = lp
        runCatching { wm.addView(btn, lp) }
        relayoutExit()
        h.removeCallbacks(hideExit)
        h.postDelayed(hideExit, 2000)
    }

    /** 按钮贴在桌宠外侧上方：靠右贴在左边，靠左贴在右边（都不压住立绘） */
    private fun relayoutExit() {
        val btn = exitWin ?: return
        val lp = exitLp ?: return
        val dm = resources.displayMetrics
        val sw = dm.widthPixels
        val petCx = params.x + params.width / 2
        val atRight = petCx > sw / 2
        lp.x = if (atRight) (params.x - dp(38)).coerceAtLeast(dp(2))
               else (params.x + params.width - dp(2)).coerceAtMost(sw - dp(36))
        lp.y = (params.y - dp(30)).coerceAtLeast(dp(2))
        runCatching { wm.updateViewLayout(btn, lp) }
    }

    private val hideExit = Runnable {
        exitWin?.let { runCatching { wm.removeView(it) } }
        exitWin = null; exitLp = null
    }

    /** 隐藏桌宠：同步关闭开关，避免下次进 App 又弹出来 */
    private fun hidePet() {
        prefs.edit().putBoolean("pet_on", false).apply()
        // 通知设置页/主界面：桌宠已关闭，开关跟着关掉
        runCatching {
            sendBroadcast(Intent(ACTION_STATE).setPackage(packageName))
        }
        stopSelf()
    }

    // ---------- 菜单 ----------
    private fun showMenu() {
        runCatching {
            val items = arrayOf("更换皮肤", "调整大小", "隐藏桌宠", "回到主页")
            val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("桌宠")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> openAppAt("pet_skin"); 1 -> pickSize(); 2 -> hidePet(); 3 -> openApp()
                    }
                }.create()
            dlg.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE)
            dlg.show()
        }
    }

    private fun pickSize() {
        runCatching {
            val labels = arrayOf("小", "中", "大", "特大")
            val dpValues = intArrayOf(72, 100, 132, 168)
            val cur = prefs.getInt("pet_size", 100)
            val idx = dpValues.indexOfFirst { it == cur }.coerceAtLeast(1)
            val dlg = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("桌宠大小")
                .setSingleChoiceItems(labels, idx) { d, which ->
                    prefs.edit().putInt("pet_size", dpValues[which]).apply(); d.dismiss()
                    reload(this)
                }.create()
            dlg.window?.setType(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE)
            dlg.show()
        }
    }

    private fun openApp() = openAppAt("")
    private fun openAppAt(extra: String) {
        runCatching {
            startActivity(Intent(this, Class.forName("app.zhiyi.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("open", extra))
        }
    }

    // ---------- 进场 ----------
    private fun playEnter(fromRight: Boolean, targetX: Int) {
        val dm = resources.displayMetrics
        val w = params.width
        val peekX = if (fromRight) dm.widthPixels - w * 55 / 100 else w * 45 / 100
        val startX = params.x
        val a1 = ValueAnimator.ofInt(startX, peekX).apply {
            duration = 420
            addUpdateListener { params.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(root, params) } }
        }
        a1.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) {
                h.postDelayed({
                    val over = if (fromRight) targetX + dp(6) else targetX - dp(6)
                    val a2 = ValueAnimator.ofInt(peekX, over).apply {
                        duration = 300
                        addUpdateListener { params.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(root, params) } }
                    }
                    val a3 = ValueAnimator.ofInt(over, targetX).apply {
                        duration = 220
                        addUpdateListener { params.x = it.animatedValue as Int; runCatching { wm.updateViewLayout(root, params) } }
                    }
                    a2.addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(a: android.animation.Animator) = a3.start()
                    })
                    a2.start()
                }, 320)
            }
        })
        a1.start()
    }

    // ---------- 位置 ----------
    private fun clamp() {
        val dm = resources.displayMetrics
        params.x = params.x.coerceIn(0, (dm.widthPixels - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (dm.heightPixels - params.height).coerceAtLeast(0))
    }

    private fun snapToEdge() {
        val dm = resources.displayMetrics
        params.x = if (params.x + params.width / 2 < dm.widthPixels / 2) 0 else (dm.widthPixels - params.width).coerceAtLeast(0)
        clamp(); runCatching { wm.updateViewLayout(root, params) }
    }

    // ---------- 动画 ----------
    private fun startBreathing() {
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                val v = it.animatedValue as Float
                pet.scaleX = 1f + 0.03f * v; pet.scaleY = 1f + 0.03f * v; pet.translationY = -dp(3) * v
            }
            start()
        }
    }

    private fun startBlink() {
        val task = object : Runnable {
            override fun run() {
                blinkUntil = System.currentTimeMillis() + 250
                PetSkin.face(this@PetService, skinId, "blink")?.let { pet.setImageDrawable(it); applyPixel() }
                h.postDelayed({
                    if (System.currentTimeMillis() - lastInteractTs > 15_000) setFace("calm")
                    else applyMoodFace()
                }, 240)
                h.postDelayed(this, 3000L + Random.nextLong(0, 4000))
            }
        }
        h.postDelayed(task, 2500)
    }

    /** 待机轮询：一段时间没互动就回中性表情（呼吸交给 startBreathing） */
    private fun startMoodPoll() {
        h.post(object : Runnable {
            override fun run() {
                runCatching {
                    if (System.currentTimeMillis() - lastInteractTs > 15_000) {
                        if (curFace != "calm" && System.currentTimeMillis() >= blinkUntil) setFace("calm")
                    } else {
                        applyMoodFace()
                    }
                }
                h.postDelayed(this, 4_000)
            }
        })
    }

    private fun applyMoodFace() {
        if (System.currentTimeMillis() < blinkUntil) return
        pid = currentPid()
        if (pid.isBlank()) { setFace("calm"); return }
        val p = pid
        scope.launch {
            val mood = withContext(Dispatchers.IO) {
                runCatching { Db.get(this@PetService).get("mood_$p", "").toDoubleOrNull() }.getOrNull()
            }
            if (System.currentTimeMillis() < blinkUntil) return@launch
            setFace(when {
                mood == null -> "calm"; mood < 0.30 -> "sad"; mood < 0.45 -> "sleepy"
                mood < 0.62 -> "calm"; mood < 0.80 -> "happy"; else -> "shy"
            })
        }
    }

    // ---------- 前台通知 ----------
    private fun startForegroundCompat(withProjection: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "桌宠", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "桌宠常驻"; setShowBadge(false)
                })
        }
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle("知忆 · 桌宠").setContentText("她在桌面上陪着你")
            .setPriority(NotificationCompat.PRIORITY_MIN).setSilent(true).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (withProjection && ScreenCapture.isReady())
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NID, n, type)
        } else startForeground(NID, n)
    }

    /** 旋转/分屏后重算窗口位置，别让桌宠飘到屏幕外 */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::params.isInitialized) return
        runCatching {
            clamp()                    // 先按新尺寸夹回屏内
            runCatching { wm.updateViewLayout(root, params) }
            updateBubblePos()
            relayoutExit()
        }
    }

    override fun onDestroy() {
        runCatching { MimoTts.stop() }                    // 否则关掉桌宠后她还在念（worker 是 daemon）
        runCatching { closeRing() }                       // 否则环窗口会永久留在屏幕上
        runCatching { stopVoice() }                       // cancel + destroy + 清超时，否则麦克风会话泄漏
        videoMode = false; runCatching { videoJob?.cancel() }; videoJob = null
        runCatching { dlg?.dismiss() }; dlg = null
        bubbleRoot?.let { runCatching { wm.removeView(it) } }; bubbleRoot = null
        hideExit.run()
        runCatching { inputWin?.let { wm.removeView(it) } }; inputWin = null
        runCatching { ScreenCapture.cleanTemp(this) }   // 退出只清临时截图，投影留着（否则每次重启都要重新授权）
        h.removeCallbacksAndMessages(null); scope.cancel(); anim?.cancel()
        if (::root.isInitialized) runCatching { wm.removeView(root) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val CH = "zhiyi_pet"
        private const val NID = 9100
        const val ACTION_STOP = "app.zhiyi.pet.STOP"
        const val ACTION_RELOAD = "app.zhiyi.pet.RELOAD"
        const val ACTION_STATE = "app.zhiyi.pet.STATE"      // 桌宠开关状态变化
        fun start(ctx: Context) = runCatching {
            val i = Intent(ctx, PetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, PetService::class.java)) }
        /** 换皮肤/改尺寸后重建界面（不重启服务） */
        fun reload(ctx: Context) = runCatching {
            ctx.startService(Intent(ctx, PetService::class.java).setAction(ACTION_RELOAD))
        }
    }
}
