/*
 * Copyright (C) 2026 YGHFv
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.YGHFv.ReaPressExtend.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.YGHFv.ReaPressExtend.backup.BackupConfig
import io.github.YGHFv.ReaPressExtend.backup.BackupEntry
import io.github.YGHFv.ReaPressExtend.backup.BackupScheduler
import io.github.YGHFv.ReaPressExtend.backup.BackupSettings
import io.github.YGHFv.ReaPressExtend.backup.ExpressBackup
import io.github.YGHFv.ReaPressExtend.backup.SafBackupStore
import io.github.YGHFv.ReaPressExtend.core.BackupBundle
import io.github.YGHFv.ReaPressExtend.core.BackupCrypto
import io.github.YGHFv.ReaPressExtend.core.NoRootPlan
import io.github.YGHFv.ReaPressExtend.logging.ModuleAndroidLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「备份与恢复」二级页：当前状态、手动、自动、加密、目录、版本回退、本机快照。
 * 所有等待都在 IO 线程；miuix 的 OverlayDialog 必须放在最近一层 Scaffold 的 lambda 里，否则静默不显示。
 *
 * @param onDataRestored 恢复成功之后通知调用方重读各项 state
 */
@Composable
internal fun BackupPage(
    onDataRestored: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    BackHandler(onBack = onBack)

    // 重算计数：配置、目录内容、上次结果都可能在本页开着时被别处改动。
    var tick by remember { mutableIntStateOf(0) }

    val config = remember(tick) { BackupSettings.load(context) }

    // 这三个不按 tick 重建：读回前被清成空值会让页面先塌下去再长回来（真机「闪一下」）。
    var storeLabel by remember { mutableStateOf("") }
    var backups by remember { mutableStateOf<List<BackupEntry>>(emptyList()) }
    var snapshots by remember { mutableStateOf(ExpressBackup.localSnapshots(context)) }

    LaunchedEffect(tick) {
        val loaded = withContext(Dispatchers.IO) {
            val store = runCatching { BackupScheduler.storeOf(context, config) }.getOrNull()
            val label = store?.label ?: BackupScheduler.defaultDir(context).absolutePath
            Triple(
                label,
                runCatching { store?.list() }.getOrNull().orEmpty(),
                runCatching { ExpressBackup.localSnapshots(context) }.getOrNull().orEmpty(),
            )
        }
        storeLabel = loaded.first
        backups = loaded.second
        snapshots = loaded.third
    }

    // 本次操作的结果，只留在界面上。
    var note by remember { mutableStateOf<String?>(null) }
    var noteIsError by remember { mutableStateOf(false) }

    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    var confirmOpen by remember { mutableStateOf(false) }

    var passwordOpen by remember { mutableStateOf(false) }
    var passwordIsSet by remember { mutableStateOf(false) }
    var passwordDraft by remember { mutableStateOf("") }
    // 弹窗内错误不复用页面的 note：那是上一次操作的结果。
    var passwordError by remember { mutableStateOf<String?>(null) }
    // 「这次设密码是为了打开加密开关」；用户取消时清掉，开关与密码同进同退。
    var encryptAfterPassword by remember { mutableStateOf(false) }

    /** 打开密码弹窗的唯一入口，四个字段必须一起设。setting=设密码；forEnable=为打开开关，存成功才开。 */
    fun openPasswordDialog(setting: Boolean, forEnable: Boolean = false) {
        passwordIsSet = setting
        encryptAfterPassword = forEnable
        passwordDraft = if (setting) "" else config.password
        passwordError = null
        passwordOpen = true
    }

    /** 关闭密码弹窗：不留「为了打开开关」的半开状态。 */
    fun closePasswordDialog() {
        passwordOpen = false
        encryptAfterPassword = false
    }

    // 删除确认（备份文件 / 本机快照）。
    var deleteName by remember { mutableStateOf<String?>(null) }
    var deleteIsSnapshot by remember { mutableStateOf(false) }

    fun report(message: String, isError: Boolean) {
        note = message
        noteIsError = isError
    }

    fun saveConfig(next: BackupConfig): Boolean {
        val saved = runCatching { BackupSettings.saveOptions(context, next) }
        saved.onFailure { report("备份设置未能写入磁盘，请重试。", true) }
        tick++
        return saved.isSuccess
    }

    /** 恢复流程统一入口（三个来源收敛到这里，恢复逻辑只写一遍）：先确认，必要时再要密码。 */
    fun beginRestore(text: String, label: String) {
        pending = PendingRestore(text, label, BackupCrypto.isEncrypted(text))
        // 手上正好有密码就先填上（同一次会话里刚设过时最省事），填错也能改。
        passwordDraft = config.password
        passwordError = null
        confirmOpen = true
    }

    fun submitRestore(password: String?) {
        val p = pending ?: return
        confirmOpen = false
        passwordOpen = false
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                ExpressBackup.restore(context, p.text, System.currentTimeMillis(), password)
            }
            if (outcome.needsPassword) {
                report(outcome.message, true)
                passwordError = outcome.message
                passwordOpen = true
            } else {
                pending = null
                report(outcome.message, !outcome.ok || outcome.runtimeWarnings.isNotEmpty())
                if (outcome.dataChanged) onDataRestored()
                tick++
            }
        }
    }

    // ---------------------------------------------------------------- 文件选择器

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupBundle.MIME_TYPE),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val password = config.password.takeIf { config.encrypt }
                val text = withContext(Dispatchers.IO) {
                    ExpressBackup.export(context, System.currentTimeMillis(), password)
                }
                withContext(Dispatchers.IO) {
                    val stream = context.contentResolver.openOutputStream(uri)
                        ?: error("无法写入所选位置")
                    stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }
                text.length
            }.onSuccess { size ->
                report("已导出（约 ${size / 1024} KB）${if (config.encrypt) "，已加密" else ""}。", false)
            }.onFailure { e ->
                ModuleAndroidLog.error(TAG, "backup export failed", e)
                report("导出失败：${e.message ?: e::class.java.simpleName}", true)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: error("读不到所选文件")
                }
            }.getOrElse { e ->
                ModuleAndroidLog.error(TAG, "backup read failed", e)
                report("读不到所选文件：${e.message ?: e::class.java.simpleName}", true)
                return@launch
            }
            beginRestore(text, "所选文件")
        }
    }

    val dirLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val persisted = SafBackupStore.takePersistable(context, uri)
        saveConfig(config.copy(dirUri = uri.toString()))
        report(
            if (persisted) {
                "备份目录已改为所选文件夹。"
            } else {
                "已选择文件夹但系统未给持久授权：手动备份可用，自动备份不生效。"
            },
            !persisted,
        )
    }

    // ---------------------------------------------------------------- 页面

    Scaffold(
        topBar = {
            TopAppBar(
                title = "备份与恢复",
                navigationIcon = {
                    IconButton(onClick = onBack, backgroundColor = Color.Transparent) {
                        Icon(imageVector = MiuixIcons.Back, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
                .padding(top = padding.calculateTopPadding())
                .padding(vertical = 4.dp),
        ) {
            GroupTitle("当前状态")
            SettingsCard {
                InfoRow("备份位置", storeLabel.ifEmpty { "读取中…" })
                InfoRow("上次备份", lastBackupLabel(config.lastBackupAt))
                if (config.intervalMs > BackupSettings.INTERVAL_OFF) {
                    InfoRow("下次定时", nextDueLabel(config.nextDueAt))
                }
                (note ?: config.lastResult.takeIf { it.isNotEmpty() })?.let {
                    HintText(it, if (note != null && noteIsError) MiuixTheme.colorScheme.error else null)
                }
                if (config.automaticOn && !config.encryptionReady) {
                    // 自动备份在缺密码时会拒绝执行（不静默存明文），提前说免得用户以为它在工作。
                    HintText("开了加密备份但没设密码，自动备份不会执行。", MiuixTheme.colorScheme.error)
                }
            }

            GroupTitle("手动")
            SettingsCard {
                TextButton(
                    text = "立即备份",
                    onClick = {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                BackupScheduler.backupNow(context, "手动备份", System.currentTimeMillis())
                            }
                            report(result.message, !result.ok)
                            tick++
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
                ArrowPreference(
                    title = "导出到指定文件…",
                    summary = "自己选一个位置存一份（网盘、SD 卡都行）",
                    onClick = {
                        exportLauncher.launch(ExpressBackup.suggestFileName(System.currentTimeMillis()))
                    },
                )
                ArrowPreference(
                    title = "从文件恢复…",
                    summary = "会用备份里的内容覆盖当前数据；恢复前自动另存一份",
                    // 不限定后缀：部分文件管理器不给 .json 分类，限定后会出现「文件在、列表里选不到」。
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
                HintText("备份文件里包含淘宝登录态，请只放在自己信得过的地方。")
            }

            GroupTitle("自动备份")
            SettingsCard {
                SwitchPreference(
                    title = "有变更时自动备份",
                    summary = "数据一变就备一份（十分钟内最多一份）",
                    checked = config.onDataChange,
                    onCheckedChange = { on -> saveConfig(config.copy(onDataChange = on)) },
                )
                OverlayDropdownPreference(
                    title = "定时备份",
                    items = BackupSettings.INTERVAL_OPTIONS.map { BackupSettings.intervalLabel(it) },
                    selectedIndex = BackupSettings.INTERVAL_OPTIONS
                        .indexOf(config.intervalMs).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val ms = BackupSettings.INTERVAL_OPTIONS.getOrNull(index) ?: return@OverlayDropdownPreference
                        // 改间隔就把到期时刻清零重排。
                        if (!saveConfig(config.copy(intervalMs = ms, nextDueAt = 0L))) return@OverlayDropdownPreference
                        BackupScheduler.reschedule(context)
                        BackupScheduler.maybeRunDue(context, "设置变更")
                        tick++
                    },
                )
                OverlayDropdownPreference(
                    title = "保留份数上限",
                    items = BackupSettings.RETENTION_OPTIONS.map { "$it 份" },
                    selectedIndex = BackupSettings.RETENTION_OPTIONS
                        .indexOf(config.retention).coerceAtLeast(0),
                    onSelectedIndexChange = { index ->
                        val n = BackupSettings.RETENTION_OPTIONS.getOrNull(index)
                            ?: return@OverlayDropdownPreference
                        saveConfig(config.copy(retention = n))
                    },
                )
                HintText("超出上限时从最旧的开始删，只动本模块的备份文件。")
                HintText("定时备份用不精确闹钟，可能稍晚；打开模块时会补一次到期判定。")
            }

            GroupTitle("加密")
            SettingsCard {
                SwitchPreference(
                    title = "加密备份",
                    summary = "备份文件用密码加密后再写出去",
                    checked = config.encrypt,
                    onCheckedChange = { on ->
                        when {
                            // 关掉只改开关，密码留着（用户可能只是暂时不想加密）。
                            !on -> saveConfig(config.copy(encrypt = false))
                            config.password.isNotEmpty() -> saveConfig(config.copy(encrypt = true))
                            else -> {
                                // 没密码先要密码，存下来之前不开开关，避免「开了却没用」的状态。
                                openPasswordDialog(setting = true, forEnable = true)
                            }
                        }
                    },
                )
                ArrowPreference(
                    title = if (config.password.isEmpty()) "设置密码" else "修改密码",
                    summary = if (config.password.isEmpty()) "还没有设置" else "已设置（只存在本机）",
                    onClick = { openPasswordDialog(setting = true) },
                )
                if (config.password.isNotEmpty()) {
                    ArrowPreference(
                        title = "清除密码",
                        summary = "关掉加密并删掉本机存的密码；已加密的旧备份仍要旧密码",
                        onClick = { saveConfig(config.copy(password = "", encrypt = false)) },
                    )
                }
                HintText(
                    "密码明文存在本机（自动备份无法输入密码）。它保护的是备份文件" +
                        "（网盘、SD 卡、外发），不是本机数据。",
                )
                if (config.password.isNotEmpty()) {
                    HintText("改密码不会重新加密已有的备份文件：旧文件仍要旧密码才能恢复。")
                }
            }

            GroupTitle("备份目录")
            SettingsCard {
                ArrowPreference(
                    title = "选择目录…",
                    summary = "挑一个文件夹存备份（可放在模块管不着的地方）",
                    onClick = { dirLauncher.launch(null) },
                )
                if (config.dirUri != null) {
                    ArrowPreference(
                        title = "恢复默认目录",
                        summary = "回到模块专属目录，不需要任何权限",
                        onClick = { saveConfig(config.copy(dirUri = null)) },
                    )
                }
                HintText("默认目录是模块专属外部目录，连电脑可见；无需存储权限，自动备份开箱可用。")
            }

            GroupTitle("版本回退（目录里的备份）")
            if (backups.isEmpty()) {
                SettingsCard {
                    HintText("目录里还没有备份，点上方「立即备份」生成一份。")
                }
            } else {
                backups.forEach { entry ->
                    RecordCard(
                        title = entry.name,
                        description = "${sizeLabel(entry.sizeBytes)} · ${stampLabel(entry.modifiedAt)}",
                        actions = {
                            TextButton(
                                text = "恢复",
                                onClick = {
                                    scope.launch {
                                        val text = runCatching {
                                            withContext(Dispatchers.IO) {
                                                BackupScheduler.storeOf(context, config).read(entry.name)
                                            }
                                        }.getOrElse { e ->
                                            report(
                                                "读不到「${entry.name}」：${e.message ?: e::class.java.simpleName}",
                                                true,
                                            )
                                            return@launch
                                        }
                                        beginRestore(text, entry.name)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                            TextButton(
                                text = "删除",
                                onClick = {
                                    deleteName = entry.name
                                    deleteIsSnapshot = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        },
                    )
                }
            }

            GroupTitle("本机快照（每次恢复前自动留）")
            if (snapshots.isEmpty()) {
                SettingsCard {
                    HintText("暂无快照。每次恢复前会先把当前数据存一份到这里。")
                }
            } else {
                snapshots.forEach { entry ->
                    RecordCard(
                        title = entry.name,
                        description = "${sizeLabel(entry.sizeBytes)} · ${stampLabel(entry.modifiedAt)}",
                        actions = {
                            TextButton(
                                text = "恢复",
                                onClick = {
                                    val text = runCatching {
                                        ExpressBackup.readLocalSnapshot(context, entry.name)
                                    }.getOrElse { e ->
                                        report(
                                            "读不到「${entry.name}」：${e.message ?: e::class.java.simpleName}",
                                            true,
                                        )
                                        return@TextButton
                                    }
                                    beginRestore(text, entry.name)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                            TextButton(
                                text = "删除",
                                onClick = {
                                    deleteName = entry.name
                                    deleteIsSnapshot = true
                                },
                                modifier = Modifier.weight(1f),
                            )
                        },
                    )
                }
                HintText("快照只占几十 KB，是恢复出错后的退路，别随意删。")
            }

            Spacer(Modifier.height(padding.calculateBottomPadding()))
            Spacer(Modifier.height(4.dp))
        }

        // ------------------------------------------------------------ 弹窗（必须在 Scaffold 内）

        OverlayDialog(
            show = confirmOpen,
            title = "从备份恢复？",
            summary = pending?.let { "「${it.label}」的内容会整份替换当前数据" } ?: "",
            onDismissRequest = {
                confirmOpen = false
                pending = null
            },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HintText(
                    if (pending?.encrypted == true) {
                        DIALOG_TIP_ENCRYPTED
                    } else {
                        DIALOG_TIP_RESTORE
                    },
                    horizontalPadding = 0.dp,
                )
                Spacer(Modifier.height(DIALOG_ACTION_GAP))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "恢复",
                        onClick = {
                            val p = pending ?: return@TextButton
                            if (p.encrypted) {
                                // 加密的先要密码：这一步不写任何数据。
                                confirmOpen = false
                                openPasswordDialog(setting = false)
                            } else {
                                submitRestore(null)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "取消",
                        onClick = {
                            confirmOpen = false
                            pending = null
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 用 WindowDialog 而非 OverlayDialog：miuix 的两层键盘避让叠加会让弹窗飞到屏幕上半部分（2026-09-27 真机）。
        WindowDialog(
            show = passwordOpen,
            title = if (passwordIsSet) "设置备份密码" else "输入备份密码",
            onDismissRequest = { closePasswordDialog() },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = passwordDraft,
                    onValueChange = {
                        passwordDraft = it
                        passwordError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    // 出错原因写在输入框标签位上（红字），不另占一行。
                    label = passwordError ?: "密码",
                    colors = if (passwordError != null) {
                        TextFieldDefaults.textFieldColors(labelColor = MiuixTheme.colorScheme.error)
                    } else {
                        TextFieldDefaults.textFieldColors()
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(DIALOG_ACTION_GAP))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "确定",
                        onClick = {
                            if (passwordDraft.isEmpty()) {
                                passwordError = "密码不能为空。"
                                return@TextButton
                            }
                            if (passwordIsSet) {
                                val next = BackupSettings.load(context).copy(
                                    password = passwordDraft,
                                    // 开关只在「这次是为了打开它」时打开。
                                    encrypt = config.encrypt || encryptAfterPassword,
                                )
                                saveConfig(next)
                                closePasswordDialog()
                                report("密码已保存。", false)
                            } else {
                                submitRestore(passwordDraft)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "取消",
                        onClick = { closePasswordDialog() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        OverlayDialog(
            show = deleteName != null,
            title = "删除这份备份？",
            summary = deleteName ?: "",
            onDismissRequest = { deleteName = null },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                HintText(
                    if (deleteIsSnapshot) {
                        DIALOG_TIP_DELETE_SNAPSHOT
                    } else {
                        DIALOG_TIP_DELETE
                    },
                    horizontalPadding = 0.dp,
                )
                Spacer(Modifier.height(DIALOG_ACTION_GAP))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(
                        text = "删除",
                        onClick = {
                            val name = deleteName ?: return@TextButton
                            deleteName = null
                            if (deleteIsSnapshot) {
                                ExpressBackup.deleteLocalSnapshot(context, name)
                            } else {
                                BackupScheduler.storeOf(context, config).delete(name)
                            }
                            report("已删除「$name」。", false)
                            tick++
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                    TextButton(
                        text = "取消",
                        onClick = { deleteName = null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 一次待确认的恢复：备份内容 + 给用户看的来源说明 + 是否加密。 */
private class PendingRestore(
    val text: String,
    val label: String,
    val encrypted: Boolean,
)

private const val TAG = "ReaPress"

/** 弹窗里动作行之前的留白：HintText 自带 6dp 上下边距，再叠 Spacer 会堆出大空白。 */
private val DIALOG_ACTION_GAP = 12.dp

private const val DIALOG_TIP_ENCRYPTED = "这是加密备份，下一步要输入它的密码。"

private const val DIALOG_TIP_RESTORE = "恢复会整份替换当前数据，且无法撤销。"

private const val DIALOG_TIP_DELETE_SNAPSHOT = "删掉之后就没法再回退到「那一刻」了。"
private const val DIALOG_TIP_DELETE = "只删这一个文件，目录里的别的东西不动。"

private fun lastBackupLabel(at: Long): String {
    if (at <= 0L) return "从未"
    val now = System.currentTimeMillis()
    return "${NoRootPlan.describeAge((now - at).coerceAtLeast(0L))} · ${stampLabel(at)}"
}

private fun nextDueLabel(at: Long): String {
    if (at <= 0L) return "还没排"
    val remain = at - System.currentTimeMillis()
    return when {
        remain <= 0L -> "就该这一会儿"
        remain < 60_000L -> "不到一分钟"
        else -> "约 ${NoRootPlan.describeAge(remain)}后"
    }
}

private fun stampLabel(at: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(at))

private fun sizeLabel(bytes: Long): String =
    if (bytes < 1024L) "$bytes B" else "${bytes / 1024L} KB"
