package app.zhiyi.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object ProactiveAlarm {

    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 9001, Intent(ctx, TickReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 每日固定时间点的心跳（早上 8 点 + 晚上 10 点），比 15 分钟 inexact 心跳更准时 */
    private fun dailyPi(ctx: Context, code: Int, hour: Int, minute: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, TickReceiver::class.java).putExtra("daily", hour * 100 + minute),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 心跳：精确闹钟（Doze/冻结下也会唤醒），每次触发后自己重排下一次 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        // 每 15 分钟一次（Doze 下系统会放宽到 ~1 小时，够用）
        setExactSafely(am, System.currentTimeMillis() + 90_000, pi(ctx))
        // 每日固定时间点：早上 8 点、晚上 10 点
        setExactSafely(am, nextDailyAt(8, 0), dailyPi(ctx, 9002, 8, 0))
        setExactSafely(am, nextDailyAt(22, 0), dailyPi(ctx, 9003, 22, 0))
    }

    /** 单次心跳触发后重排（setExactAndAllowWhileIdle 不自动重复） */
    fun scheduleNextHeartbeat(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        setExactSafely(am, System.currentTimeMillis() + 15 * 60_000L, pi(ctx))
    }

    private fun setExactSafely(am: AlarmManager, at: Long, p: PendingIntent) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没授权精确闹钟 -> 退回普通闹钟（至少还能跑）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            }
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) }
        }
    }

    /** 每日闹钟触发后重排明天同一时刻 */
    fun scheduleDailyNext(ctx: Context, hour: Int, minute: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val code = if (hour == 8) 9002 else 9003
        setExactSafely(am, nextDailyAt(hour, minute), dailyPi(ctx, code, hour, minute))
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi(ctx))
        am.cancel(dailyPi(ctx, 9002, 8, 0))
        am.cancel(dailyPi(ctx, 9003, 22, 0))
    }

    private fun nextDailyAt(hour: Int, minute: Int): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }
}
