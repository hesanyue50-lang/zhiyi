package app.zhiyi

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import app.zhiyi.proactive.ProactiveAlarm
import app.zhiyi.ui.ZhiYiApp

class MainActivity : ComponentActivity() {

    /** 从别的 App 分享进来的内容，等 Compose 起来再交给 ViewModel */
    private var shareText: String? = null
    private var shareUri: android.net.Uri? = null
    private var shareIsImage = false
    private var pendingOpen: String? = null
    private val openTick = androidx.compose.runtime.mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        if (proactiveOn) {
            ProactiveAlarm.schedule(this)
            app.zhiyi.proactive.ProactiveService.start(this)
        }
        val petOn = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("pet_on", false)
        if (petOn && android.provider.Settings.canDrawOverlays(this)) {
            app.zhiyi.pet.PetService.start(this)
        }
        readShare(intent)
        pendingOpen = intent.getStringExtra("open")
        if (pendingOpen == "mic") {
            runCatching { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
            pendingOpen = null
        }

        // 只有「共享屏幕」模式才需要录屏授权；ADB/自动模式不要弹
        val shotMode = getSharedPreferences("cfg", MODE_PRIVATE).getString("shot_mode", "auto") ?: "auto"
        if (petOn && shotMode == "projection" && android.provider.Settings.canDrawOverlays(this)) {
            window.decorView.postDelayed({
                if (!isFinishing && !isDestroyed) runCatching { ensureScreenCapture() }
            }, 800)
        }

        setContent {
            val vm: AppVm = viewModel()
            // 通知点击 / 语音授权 带过来的 open
            androidx.compose.runtime.LaunchedEffect(openTick.value) {
                val op = pendingOpen
                if (op != null && op.startsWith("chat:")) {
                    val pid = op.removePrefix("chat:")
                    if (pid.isNotBlank()) {
                        runCatching { vm.switchPersona(pid) }
                        vm.pendingShare = null
                    }
                    pendingOpen = null
                }
            }
            LaunchedEffect(Unit) {
                val uri = shareUri
                if (uri != null) {
                    val bytes = runCatching {
                        contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    }.getOrNull()
                    if (bytes != null) {
                        if (shareIsImage) vm.importCard(bytes) else vm.importAny(String(bytes, Charsets.UTF_8))
                    }
                    shareUri = null
                }
                shareText?.let { t ->
                    // JSON（卡片/世界观）→ 导入；普通文字/链接 → 直接发给她
                    val isJson = runCatching { org.json.JSONObject(t.trim()) }.isSuccess
                    if (isJson) vm.importAny(t) else vm.pendingShare = t
                    shareText = null
                }
            }
            // 回到前台 / 收到桌宠状态广播 -> 重读配置，设置页开关跟着桌宠真实状态走
            val lc = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lc) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshCfg()
                }
                lc.lifecycle.addObserver(obs)
                val rx = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { vm.refreshCfg() }
                }
                val flt = android.content.IntentFilter(app.zhiyi.pet.PetService.ACTION_STATE)
                androidx.core.content.ContextCompat.registerReceiver(
                    this@MainActivity, rx, flt, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
                onDispose {
                    lc.lifecycle.removeObserver(obs)
                    runCatching { unregisterReceiver(rx) }
                }
            }
            ZhiYiApp(vm)
        }
    }

    private val micLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val ok = runCatching {
                val mpm = getSystemService(MediaProjectionManager::class.java)
                val p = mpm.getMediaProjection(result.resultCode, result.data!!)
                    ?: throw IllegalStateException("getMediaProjection 返回 null")
                app.zhiyi.pet.ScreenCapture.setProjection(p, this)
                app.zhiyi.pet.ScreenCapture.isReady()
            }.getOrDefault(false)
            if (!ok) android.widget.Toast.makeText(this, "截屏授权没成功，识屏暂不可用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 桌面宠需要截屏时调用：未授权就弹系统授权框 */
    fun ensureScreenCapture() {
        if (app.zhiyi.pet.ScreenCapture.isReady()) return
        runCatching {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            projLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }

    /** 分享目标常驻不重启，第二次分享也要能收到 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readShare(intent)
        val op = intent.getStringExtra("open")
        if (op == "mic") {
            runCatching { micLauncher.launch(android.Manifest.permission.RECORD_AUDIO) }
            pendingOpen = null
        } else if (!op.isNullOrBlank()) {
            pendingOpen = op          // 交给 setContent 里的 LaunchedEffect 消费
            openTick.value++          // 触发重组
        }

        // 桌宠开启时先要一次截屏授权（识屏/自动识屏的地基）
        if (getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("pet_on", false) && android.provider.Settings.canDrawOverlays(this)) {
            window.decorView.postDelayed({ runCatching { ensureScreenCapture() } }, 800)
        }
    }

    private fun readShare(i: Intent?) {
        if (i == null) return
        if (i.action != Intent.ACTION_SEND && i.action != Intent.ACTION_VIEW) return
        val type = i.type ?: ""
        shareIsImage = type.startsWith("image/")
        @Suppress("DEPRECATION")
        shareUri = i.getParcelableExtra(Intent.EXTRA_STREAM) ?: i.data
        shareText = i.getStringExtra(Intent.EXTRA_TEXT)
    }

    private val proactiveOn: Boolean
        get() = getSharedPreferences("cfg", MODE_PRIVATE).getBoolean("pro_on", false)

}
