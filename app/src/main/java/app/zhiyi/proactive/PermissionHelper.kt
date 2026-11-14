package app.zhiyi.proactive

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 主动发话要能"后台醒来"，国产 ROM 上需要三样：
 *  1. 通知权限（Android 13+）
 *  2. 电池优化白名单（后台运行不被杀）
 *  3. 自启动（被系统杀了能自己爬起来）
 *
 * 每个都只能"跳转到系统设置让用户点一下"，App 无法自己改。
 */
object PermissionHelper {

    /** 是否已加入电池优化白名单（后台运行不杀） */
    fun ignoringBattery(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = ctx.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** 跳转电池优化白名单申请页（部分 ROM 会直接弹"允许"） */
    fun requestBattery(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            i.data = Uri.parse("package:" + ctx.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) {
            openAppDetails(ctx)
        }
    }

    /** 跳转自启动设置。厂商 intent 都很脆弱，逐个试，全失败就落到应用详情页 */
    fun openAutoStart(ctx: Context) {
        val intents = listOf(
            // 小米
            Intent().apply {
                action = "miui.intent.action.OP_AUTO_START"
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            // 华为
            Intent().apply {
                action = "huawei.intent.action.HSM_BOOTAPP_MANAGER"
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            // OPPO
            Intent().apply {
                setClassName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            },
            // vivo
            Intent().apply {
                setClassName("com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            },
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
                return
            } catch (_: Exception) {
                // 这个厂商不是这种写法，试下一个
            }
        }
        // 全部失败：跳应用详情页，让用户自己找"自启动"
        openAppDetails(ctx)
    }

    /** 应用详情页（通用兜底） */
    fun openAppDetails(ctx: Context) {
        try {
            val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            i.data = Uri.parse("package:" + ctx.packageName)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (_: Exception) {
        }
    }
}
