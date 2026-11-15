package app.zhiyi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm
import app.zhiyi.pet.PetService
import app.zhiyi.qq.QqAccounts
import app.zhiyi.qq.QqBot
import app.zhiyi.qq.QqService
import kotlinx.coroutines.delay

/**
 * 陪伴。
 *
 * 桌宠、QQ、主动搭话这三件事不是"设置项"——它们是**她此刻在不在、能不能找到你**。
 * 塞在设置页里等于把"她现在在干什么"当成一个参数，所以单独提出来做成一页：
 * 一眼看见状态，一键开关，详细配置再进去。
 */
@Composable
fun CompanionScreen(
    vm: AppVm,
    onOpenPetSettings: () -> Unit = {},
    onOpenQq: () -> Unit = {},
    onOpenProactive: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val cfg = vm.cfg
    var qqState by remember { mutableStateOf(QqBot.summary(ctx)) }
    val qqAccounts = remember { QqAccounts.all(app.zhiyi.core.ChatCore.db(ctx)) }
    val qqOn = qqAccounts.any { it.enabled && it.ready }

    LaunchedEffect(Unit) {
        while (true) {
            qqState = QqBot.summary(ctx)
            delay(1500)
        }
    }

    val overlayOk = android.provider.Settings.canDrawOverlays(ctx)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.padding(start = D.s5, end = D.s5, top = D.s2, bottom = D.s3)) {
            Text("陪伴", style = Ty.display, color = T.text)
            Text("她此刻在不在你身边", style = Ty.caption, color = T.textFaint)
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.s5, end = D.s5, bottom = D.s6),
            verticalArrangement = Arrangement.spacedBy(D.s4),
        ) {
            // ---------- 总览 ----------
            item("overview") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(D.s4)) {
                        Text(summary(cfg.petEnabled, QqBot.anyRunning(), cfg.proactiveEnabled),
                            style = Ty.title3, color = T.text)
                        Spacer(Modifier.height(D.s1))
                        Text(
                            when {
                                cfg.petEnabled && qqOn -> "桌面上看得见她，QQ 里也找得到"
                                cfg.petEnabled -> "她在桌面上陪着你"
                                QqBot.anyRunning() -> "你在 QQ 里就能找到她"
                                cfg.proactiveEnabled -> "她会自己来找你"
                                else -> "现在只能你主动打开 App 找她"
                            },
                            style = Ty.caption, color = T.textDim
                        )
                    }
                }
            }

            // ---------- 桌宠 ----------
            item("pet") {
                FeatureCard(
                    title = "桌宠",
                    status = when {
                        !cfg.petEnabled -> "关闭"
                        !overlayOk -> "缺少悬浮窗权限"
                        else -> "在桌面上"
                    },
                    statusOk = cfg.petEnabled && overlayOk,
                    desc = "她会以像素形象坐在桌面上，能拖动，会主动搭话",
                    checked = cfg.petEnabled,
                    onToggle = { v ->
                        if (v && !overlayOk) {
                            runCatching {
                                ctx.startActivity(
                                    android.content.Intent(
                                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        android.net.Uri.parse("package:${ctx.packageName}")
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                            vm.toast = "请先允许「显示在其他应用上层」，再回来打开"
                        } else {
                            vm.saveCfg(cfg.copy(petEnabled = v))
                        }
                    },
                    onMore = onOpenPetSettings,
                )
            }

            // ---------- QQ ----------
            item("qq") {
                FeatureCard(
                    title = "QQ",
                    status = if (qqOn) qqState else "未接入",
                    statusOk = QqBot.anyRunning(),
                    desc = if (qqOn) {
                        "${qqAccounts.count { it.enabled && it.ready }} 个机器人 · ${qqAccounts.sumOf { it.whitelist.size }} 人在白名单"
                    } else "接上以后，不用打开 App 也能和她聊",
                    checked = qqOn,
                    onToggle = { v ->
                        val db = app.zhiyi.core.ChatCore.db(ctx)
                        if (v && !qqOn) {
                            vm.toast = "先去配置机器人"
                            onOpenQq()
                        } else if (!v && qqOn) {
                            val list = QqAccounts.all(db).map { it.copy(enabled = false) }
                            QqAccounts.save(db, list)
                            QqBot.stopAll()
                            QqService.stop(ctx)
                            vm.toast = "已断开"
                            qqState = "未接入"
                        }
                    },
                    onMore = onOpenQq,
                )
            }

            // ---------- 主动搭话 ----------
            item("pro") {
                FeatureCard(
                    title = "主动搭话",
                    status = if (cfg.proactiveEnabled) "会自己找你" else "关闭",
                    statusOk = cfg.proactiveEnabled,
                    desc = if (cfg.proactiveEnabled)
                        "累的时候少说两句，心情好话多——不是定时问候"
                    else "关掉以后她只在你开口时说话",
                    checked = cfg.proactiveEnabled,
                    onToggle = { v -> vm.saveCfg(cfg.copy(proactiveEnabled = v)) },
                    onMore = onOpenProactive,
                )
            }

            item("note") {
                Text(
                    "详细参数（立绘、气泡、频率、免打扰时段）在设置里",
                    style = Ty.caption, color = T.textFaint,
                    modifier = Modifier.padding(start = D.s2)
                )
            }
        }
    }
}

private fun summary(pet: Boolean, qq: Boolean, pro: Boolean): String {
    val n = listOf(pet, qq, pro).count { it }
    return when (n) {
        3 -> "三处都在"
        2 -> "她在两个地方等你"
        1 -> "她在一个地方等你"
        else -> "她还在等你叫她出来"
    }
}

@Composable
private fun FeatureCard(
    title: String,
    status: String,
    statusOk: Boolean,
    desc: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onMore: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(D.s4)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = Ty.title3, color = T.text)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (statusOk) T.ok.copy(alpha = 0.16f) else T.track,
                            modifier = Modifier.padding(end = D.s2),
                        ) {
                            Text(
                                status, style = Ty.caption,
                                color = if (statusOk) T.ok else T.textDim,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                Toggle(checked, onToggle)
            }
            Spacer(Modifier.height(D.s2))
            Text(desc, style = Ty.caption, color = T.textFaint)
            Spacer(Modifier.height(D.s2))
            Row {
                Pill("详细设置", onMore)
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(D.rMd), color = T.accent.copy(alpha = 0.12f)) {
        Text(
            text, style = Ty.caption, color = T.accent,
            modifier = Modifier.padding(horizontal = D.s3, vertical = 5.dp)
        )
    }
}
