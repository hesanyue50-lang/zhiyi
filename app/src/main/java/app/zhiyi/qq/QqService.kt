package app.zhiyi.qq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.zhiyi.MainActivity

/**
 * QQ 机器人常驻服务。
 *
 * 为什么必须是前台服务：WebSocket 长连接在后台会被 Doze / 各家 ROM 掐掉，
 * 只有前台服务（带常驻通知）才有稳定的网络和 CPU 配额。
 */
class QqService : Service() {

    companion object {
        private const val CH = "qq_gateway"
        private const val ID = 7201

        fun start(ctx: Context) {
            val i = Intent(ctx, QqService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, QqService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = buildNotification("正在连接 QQ…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
        // onState 变化时刷新通知文案，用户能直接看到"已上线 / 重连中"
        QqBot.startAll(applicationContext)
        notifier = NotificationManager::class.java.let { getSystemService(it) }
        // 用轻量轮询更新通知（网关状态是 volatile 字段，避免再加回调层）
        Thread {
            var last = ""
            while (QqBot.anyRunning() || last.isEmpty()) {
                val s = QqBot.summary(applicationContext)
                if (s != last) {
                    last = s
                    runCatching { notifier?.notify(ID, buildNotification(s)) }
                }
                Thread.sleep(1500)
            }
        }.apply { isDaemon = true }.start()
        return START_STICKY
    }

    private var notifier: NotificationManager? = null

    override fun onDestroy() {
        QqBot.stopAll()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CH, "QQ 机器人", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                    description = "保持与 QQ 的连接"
                }
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CH)
            .setContentTitle("QQ 机器人")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
