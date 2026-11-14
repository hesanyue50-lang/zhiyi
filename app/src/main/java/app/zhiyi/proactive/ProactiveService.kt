package app.zhiyi.proactive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台常驻服务：主动发话的心跳。
 *
 * 为什么必须要它：AlarmManager 的闹钟在 App 进后台后被系统「冻结」（Cached Apps Freezer，
 * dumpsys alarm 里是 Reason=frozen），即使加了电池白名单也不触发，导致挂一天一条消息都没有。
 * 只有前台服务能不被冻结地长期跑。这里用协程 15 分钟循环。
 */
class ProactiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (job?.isActive != true) {
            job = scope.launch {
                // 服务被拉起（开机/重装/被杀重启）时先等 3 分钟，避免"刚装好就弹一条"
                delay(3 * 60_000L)
                while (isActive) {
                    // 心跳：让九维情绪按时间回落（纯规则，不发请求）。
                    // 没有这一步的话，情绪只会在聊天时更新，放着不动就永远卡在最后一次的状态。
                    runCatching {
                        val db = app.zhiyi.data.Db.get(applicationContext)
                        db.personas().filter { it.id.isNotBlank() }
                            .forEach { app.zhiyi.memory.Heart.tick(db, it.id) }
                    }
                    runCatching { Proactive.maybeFireAll(applicationContext) }
                    runCatching { Proactive.dailyRoutine(applicationContext) }
                    delay(15 * 60_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CH) == null) {
                val c = NotificationChannel(CH, "后台陪伴", NotificationManager.IMPORTANCE_MIN).apply {
                    description = "让她在你退出 App 后也能主动找你"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(c)
            }
        }
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("知忆")
            .setContentText("她在后台陪着你")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CH = "zhiyi_foreground"
        private const val NOTIF_ID = 9000

        fun start(ctx: Context) {
            runCatching {
                val i = Intent(ctx, ProactiveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            runCatching { ctx.stopService(Intent(ctx, ProactiveService::class.java)) }
        }
    }
}
