package app.zhiyi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.zhiyi.AppVm
import app.zhiyi.core.ChatCore
import app.zhiyi.qq.QqAccount
import app.zhiyi.qq.QqAccounts
import app.zhiyi.qq.QqBot
import app.zhiyi.qq.QqService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * QQ 接入：多机器人。
 *
 * 一个 AppID = 一个机器人 = 一个人格。想要几个人格同时在线，就配几个机器人。
 * 这样做的原因是腾讯的 openid 按机器人分配、也无法换算成 QQ 号，
 * 所以"账号"是最自然、最不会串的隔离单位。
 *
 * 白名单是必须的：不在名单里的 openid 一律不回复，只在 App 里记一条"待批准"。
 * 陌生人加上机器人并不能直接跟她聊，也不会污染记忆。
 */
@Composable
fun QqScreen(vm: AppVm, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val db = remember { ChatCore.db(ctx) }

    var accounts by remember { mutableStateOf(QqAccounts.all(db)) }
    var editing by remember { mutableStateOf<QqAccount?>(null) }
    var tick by remember { mutableStateOf(0) }

    fun reload() { accounts = QqAccounts.all(db); tick++ }

    // 状态是后台线程写的，这里轻量轮询
    var states by remember { mutableStateOf(mapOf<String, String>()) }
    LaunchedEffect(tick) {
        while (true) {
            states = accounts.associate { it.appId to QqBot.state(it.appId) }
            delay(1200)
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        GlassTopBar(
            title = "QQ",
            subtitle = if (accounts.isEmpty()) "还没有机器人" else QqBot.summary(ctx),
            onBack = onBack,
        )

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = D.s5, end = D.s5, bottom = D.s6),
            verticalArrangement = Arrangement.spacedBy(D.s4),
        ) {
            item("intro") {
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(D.s4)) {
                        Text("一个机器人 = 一个人格", style = Ty.title3, color = T.text)
                        Spacer(Modifier.height(D.s2))
                        Text(
                            "想要两个人格同时在线，就建两个机器人，各绑一个人格。" +
                                "每个机器人有自己的白名单，名单里的人各记各的记忆——她记得 A 说过什么，不会跟 B 混在一起。",
                            style = Ty.caption, color = T.textDim
                        )
                        Spacer(Modifier.height(D.s2))
                        Text(
                            "个人主体的机器人不能公开使用，只有你在管理端填过「内部体验号码」的号能搜到它。",
                            style = Ty.caption, color = T.textFaint
                        )
                    }
                }
            }

            if (accounts.isEmpty()) {
                item("empty") {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(D.s4)) {
                            Text("还没有配置机器人", style = Ty.body, color = T.textDim)
                            Spacer(Modifier.height(D.s2))
                            Text(
                                "去 q.qq.com 用 QQ 扫码登录、实名认证、创建机器人，" +
                                    "拿到 AppID 和 AppSecret 后回来点下面那个按钮。",
                                style = Ty.caption, color = T.textFaint
                            )
                        }
                    }
                }
            }

            items(accounts.size) { i ->
                val a = accounts[i]
                val st = states[a.appId] ?: "未启动"
                val pending = QqAccounts.pending(db, a.appId)
                GlassCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(D.s4)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("机器人 ${a.appId}", style = Ty.title3, color = T.text)
                                Text(
                                    "人格：${db.persona(a.personaId)?.name ?: "未绑定"} · 白名单 ${a.whitelist.size} 人",
                                    style = Ty.caption, color = T.textDim
                                )
                            }
                            Switch(checked = a.enabled, onCheckedChange = { v ->
                                if (v && !a.ready) {
                                    vm.toast = "先填 AppSecret"
                                } else {
                                    QqAccounts.upsert(ctx, a.copy(enabled = v))
                                    if (v) {
                                        QqBot.startOne(ctx, a.copy(enabled = true))
                                        QqService.start(ctx)
                                    } else {
                                        QqBot.stop(a.appId)
                                    }
                                    reload()
                                }
                            })
                        }
                        Spacer(Modifier.height(D.s2))
                        Row(horizontalArrangement = Arrangement.spacedBy(D.s2)) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (QqBot.isRunning(a.appId)) T.ok.copy(alpha = 0.16f) else T.track,
                            ) {
                                Text(
                                    st, style = Ty.caption,
                                    color = if (QqBot.isRunning(a.appId)) T.ok else T.textDim,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                            if (pending.isNotEmpty()) {
                                Surface(shape = RoundedCornerShape(4.dp), color = T.warn.copy(alpha = 0.16f)) {
                                    Text(
                                        "${pending.size} 人待批准", style = Ty.caption, color = T.warn,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(D.s3))
                        Row(horizontalArrangement = Arrangement.spacedBy(D.s3)) {
                            Pill("编辑") { editing = a }
                            Pill("删除") {
                                QqBot.stop(a.appId)
                                QqAccounts.remove(ctx, a.appId)
                                reload()
                                vm.toast = "已删除"
                            }
                        }
                    }
                }
            }

            item("add") {
                Surface(
                    onClick = {
                        editing = QqAccount(appId = "", personaId = db.personas().firstOrNull()?.id ?: "")
                    },
                    shape = RoundedCornerShape(D.rMd),
                    color = T.accent.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "+  新建机器人",
                        style = Ty.bodySm, color = T.accent,
                        modifier = Modifier.padding(vertical = D.s4),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }

    editing?.let { acc ->
        AccountEditor(
            vm = vm,
            acc = acc,
            onDismiss = { editing = null },
            onSaved = { reload(); editing = null },
        )
    }
}

@Composable
private fun AccountEditor(
    vm: AppVm,
    acc: QqAccount,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val db = remember { ChatCore.db(ctx) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val isNew = acc.appId.isBlank()

    var appId by remember { mutableStateOf(acc.appId) }
    var secret by remember { mutableStateOf(acc.secret) }
    var personaId by remember { mutableStateOf(acc.personaId) }
    var busy by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf("") }

    val personas = remember { db.personas() }
    val pending = remember { if (isNew) emptyList() else QqAccounts.pending(db, acc.appId) }
    var whitelist by remember { mutableStateOf(acc.whitelist) }
    var manualId by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新建机器人" else "机器人 ${acc.appId}", style = Ty.title3) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = appId,
                    onValueChange = { appId = it.trim() },
                    label = { Text("AppID") },
                    singleLine = true,
                    enabled = isNew,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(D.s2))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.trim() },
                    label = { Text("AppSecret") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(D.s3))
                Text("绑哪个人格", style = Ty.caption, color = T.textDim)
                Spacer(Modifier.height(D.s1))
                personas.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { personaId = p.id }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (personaId == p.id) "●" else "○",
                            style = Ty.caption,
                            color = if (personaId == p.id) T.accent else T.textFaint,
                            modifier = Modifier.padding(end = D.s2),
                        )
                        Text(p.name, style = Ty.bodySm, color = T.text)
                    }
                }

                Spacer(Modifier.height(D.s3))
                Text("白名单（${whitelist.size}）", style = Ty.caption, color = T.textDim)
                Text(
                    "私聊时腾讯的「内部体验号码」已经挡了一层，这里的价值在群：群被拉进去之后，" +
                        "群里任何人都能 @ 它，得靠这个名单决定回不回。",
                    style = Ty.caption, color = T.textFaint,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = manualId,
                        onValueChange = { manualId = it.trim() },
                        label = { Text("手动加 openid") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(D.s2))
                    Pill("添加") {
                        if (manualId.isNotBlank() && !whitelist.contains(manualId)) {
                            whitelist = whitelist + manualId
                            manualId = ""
                        }
                    }
                }
                if (whitelist.isNotEmpty()) {
                    whitelist.forEach { id ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tail(id), style = Ty.caption, color = T.textFaint, modifier = Modifier.weight(1f))
                            Text(
                                "移出", style = Ty.caption, color = T.danger,
                                modifier = Modifier.clickable { whitelist = whitelist - id }
                            )
                        }
                    }
                }

                if (pending.isNotEmpty()) {
                    Spacer(Modifier.height(D.s3))
                    Text("待批准（${pending.size}）", style = Ty.caption, color = T.warn)
                    Text(
                        "这些人在 QQ 上找过它，但不在白名单里，没有回复。",
                        style = Ty.caption, color = T.textFaint
                    )
                    // 账号必须已经存在（新账号还没有 openid 记录）
                    pending.forEach { (id, _) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tail(id), style = Ty.caption, color = T.text, modifier = Modifier.weight(1f))
                            Text(
                                "允许", style = Ty.caption, color = T.ok,
                                modifier = Modifier.clickable {
                                    QqAccounts.allow(ctx, acc.appId, id)
                                    whitelist = whitelist + id
                                    vm.toast = "已允许"
                                }
                            )
                        }
                    }
                }

                if (testResult.isNotBlank()) {
                    Spacer(Modifier.height(D.s2))
                    Text(testResult, style = Ty.caption, color = T.textDim)
                }

                Spacer(Modifier.height(D.s2))
                Row(horizontalArrangement = Arrangement.spacedBy(D.s3)) {
                    Pill(if (busy) "验证中…" else "验证凭据") {
                        if (busy) return@Pill
                        busy = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) {
                                runCatching {
                                    QqBot.test(ctx, QqAccount(appId = appId, secret = secret))
                                }.getOrElse { "失败：${it.message}" }
                            }
                            testResult = r
                            busy = false
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (appId.isBlank()) { vm.toast = "AppID 不能空"; return@TextButton }
                if (personaId.isBlank()) { vm.toast = "选一个人格"; return@TextButton }
                QqAccounts.upsert(
                    ctx,
                    QqAccount(
                        appId = appId, secret = secret, personaId = personaId,
                        whitelist = whitelist, enabled = acc.enabled,
                    )
                )
                vm.toast = if (isNew) "已添加" else "已保存"
                onSaved()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun tail(openId: String): String = when {
    openId.startsWith("g:") -> "群 …" + openId.takeLast(8)
    openId.length <= 12 -> openId
    else -> "…" + openId.takeLast(10)
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
