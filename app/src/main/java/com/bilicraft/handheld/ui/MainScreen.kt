package com.bilicraft.handheld.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilicraft.handheld.appicon.AppIcon
import com.bilicraft.handheld.config.QuickToolLink
import com.bilicraft.handheld.config.ServerConfig
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.update.DownloadSource
import com.bilicraft.handheld.update.ReleaseInfo
import com.bilicraft.handheld.update.UpdateState
import java.io.File
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository

private enum class MainTab(val title: String, val icon: ImageVector) {
    Sessions("服务器会话", Icons.Default.SportsEsports),
    Tools("快捷工具", Icons.Default.Web),
    Settings("设置", Icons.Default.Settings)
}

/**
 * Material 3 主界面。
 * 依赖现有逻辑：连接、聊天、插件、账号操作只通过 MainViewModel 转发到底层模块。
 * 纯 UI 补足：服务器配置和快捷链接列表来自 UiConfigRepository，不影响协议或登录状态机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel) {
    val uiMessage by vm.uiMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by remember { mutableStateOf(MainTab.Sessions) }

    LaunchedEffect(uiMessage) {
        val message = uiMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeUiMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            when (selectedTab) {
                MainTab.Sessions -> ServerSessionsScreen(vm)
                MainTab.Tools -> QuickToolsScreen(vm)
                MainTab.Settings -> SettingsScreen(vm)
            }
        }
    }
}

/**
 * 统一的紧凑标题栏。
 * 相比 M3 TopAppBar 的固定 64dp + 自带状态栏 inset，这里只占约 48dp，
 * 且不重复消费状态栏 padding（外层 Scaffold 已处理），避免标题栏视觉过高。
 */
@Composable
private fun ScreenHeader(
    title: String,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ServerSessionsScreen(vm: MainViewModel) {
    val runtime by vm.serverRuntime.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val selectedVersion by vm.selectedVersion.collectAsStateWithLifecycle()
    val forceSigning by vm.forceSigning.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()

    var selectedIndex by remember { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var menuServer by remember { mutableStateOf<ServerConfig?>(null) }

    LaunchedEffect(servers.size) {
        selectedIndex = selectedIndex.coerceIn(0, (servers.size - 1).coerceAtLeast(0))
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "服务器会话",
            actions = {
                IconButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = "新增服务器")
                }
            }
        )

        if (servers.isEmpty()) {
            EmptyState(
                title = "还没有服务器配置",
                message = "可新增服务器。默认配置来自 UI 配置仓库，用户删除后不会强制恢复。",
                actionText = "新增服务器",
                onAction = { showCreateDialog = true }
            )
        } else {
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                edgePadding = 12.dp
            ) {
                servers.forEachIndexed { index, server ->
                    Tab(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        text = {
                            Text(
                                server.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.combinedClickable(
                                    onClick = { selectedIndex = index },
                                    onLongClick = { menuServer = server }
                                )
                            )
                        }
                    )
                }
            }

            val selectedServer = servers.getOrNull(selectedIndex)
            if (selectedServer != null) {
                val selectedConn = runtime.connectionStates[selectedServer.id] ?: ConnectionState.Disconnected
                val selectedLog = runtime.chatLogs[selectedServer.id].orEmpty()
                val isActiveServer = runtime.activeServerId == selectedServer.id
                ServerSessionPage(
                    server = selectedServer,
                    conn = selectedConn,
                    log = selectedLog,
                    isActiveServer = isActiveServer,
                    chatAutoScroll = preferences.chatAutoScroll,
                    onConnect = { vm.connect(selectedServer) },
                    onStop = vm::stopConnection,
                    onSend = { vm.sendChat(selectedServer.id, it) },
                    onEdit = { editingServer = selectedServer }
                )
            }
        }
    }

    if (showCreateDialog) {
        ServerEditorDialog(
            title = "新增服务器",
            initial = null,
            versions = versions,
            selectedVersion = selectedVersion,
            forceSigning = forceSigning,
            onSelectVersion = vm::selectVersion,
            onForceSigning = vm::setForceSigning,
            onDismiss = { showCreateDialog = false },
            onSave = { name, host, port, version, signing ->
                vm.createServer(name, host, port, version, signing)
                showCreateDialog = false
            }
        )
    }

    editingServer?.let { server ->
        ServerEditorDialog(
            title = "编辑服务器",
            initial = server,
            versions = versions,
            selectedVersion = server.toMcVersion(),
            forceSigning = server.signingRequired,
            onSelectVersion = {},
            onForceSigning = {},
            onDismiss = { editingServer = null },
            onSave = { name, host, port, version, signing ->
                vm.saveServer(
                    server.copy(
                        name = name.ifBlank { host },
                        host = host.trim(),
                        port = port.takeIf { it in 1..65535 } ?: 25565,
                        versionId = version.id,
                        protocolNumber = version.protocolNumber,
                        signingRequired = signing
                    )
                )
                editingServer = null
            }
        )
    }

    menuServer?.let { server ->
        AlertDialog(
            onDismissRequest = { menuServer = null },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            title = { Text(server.name) },
            text = { Text("编辑或删除该服务器配置。删除只影响 UI 配置文件，不影响连接核心。") },
            confirmButton = {
                TextButton(onClick = { editingServer = server; menuServer = null }) { Text("编辑") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.deleteServer(server.id); menuServer = null }) { Text("删除") }
                    TextButton(onClick = { menuServer = null }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun ServerSessionPage(
    server: ServerConfig,
    conn: ConnectionState,
    log: List<ChatEvent>,
    isActiveServer: Boolean,
    chatAutoScroll: Boolean,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onEdit: () -> Unit
) {
    val connected = isActiveServer && conn is ConnectionState.Connected
    val connecting = isActiveServer && conn !is ConnectionState.Disconnected && conn !is ConnectionState.Failed
    var input by remember(server.id) { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = { Text(statusText(conn)) })
                    AssistChip(onClick = {}, label = { Text(server.versionId) })
                    if (server.signingRequired) AssistChip(onClick = {}, label = { Text("强制签名") })
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConnect, enabled = !connecting, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("连接")
                    }
                    OutlinedButton(onClick = onStop, enabled = isActiveServer, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("断开")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        key(server.id) {
            ChatLog(log = log, autoScroll = chatAutoScroll, modifier = Modifier.weight(1f).fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("发送聊天…") },
                singleLine = true,
                enabled = connected,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onSend(input); input = "" },
                enabled = connected && input.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("发送")
            }
        }
    }
}

@Composable
private fun ChatLog(log: List<ChatEvent>, autoScroll: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(log.size, autoScroll) {
        if (autoScroll && log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }
    LazyColumn(
        state = listState,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CHAT_BACKGROUND)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (log.isEmpty()) {
            item { Text("聊天记录为空", color = CHAT_DEFAULT_TEXT, style = MaterialTheme.typography.bodyMedium) }
        }
        items(log) { ev ->
            Text(
                text = ev.toAnnotated(),
                style = MaterialTheme.typography.bodyMedium,
                color = CHAT_DEFAULT_TEXT,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun ServerEditorDialog(
    title: String,
    initial: ServerConfig?,
    versions: VersionRepository.Grouped,
    selectedVersion: McVersion,
    forceSigning: Boolean,
    onSelectVersion: (McVersion) -> Unit,
    onForceSigning: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, McVersion, Boolean) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 25565).toString()) }
    var version by remember(initial) { mutableStateOf(initial?.toMcVersion() ?: selectedVersion) }
    var signing by remember(initial) { mutableStateOf(initial?.signingRequired ?: forceSigning) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "配置保存到 UI 仓库；真正连接仍调用现有 ConnectionService。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("服务器地址") }, singleLine = true)
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit) },
                    label = { Text("端口") },
                    singleLine = true
                )
                VersionDropdown(
                    grouped = versions,
                    selected = version,
                    onSelect = {
                        version = it
                        onSelectVersion(it)
                    }
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("强制签名聊天")
                        Text("仅切换现有连接参数，不修改签名算法。", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = signing,
                        onCheckedChange = {
                            signing = it
                            onForceSigning(it)
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, host, port.toIntOrNull() ?: 25565, version, signing) }) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickToolsScreen(vm: MainViewModel) {
    val tools by vm.quickTools.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showEditor by remember { mutableStateOf(false) }
    var editingTool by remember { mutableStateOf<QuickToolLink?>(null) }

    Scaffold(
        topBar = {
            ScreenHeader(
                title = "快捷工具",
                actions = {
                    IconButton(onClick = { showEditor = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新增快捷工具")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showEditor = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("添加") }
            )
        }
    ) { padding ->
        if (tools.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "还没有快捷工具",
                    message = "可添加 Web 链接。首次默认项由 UI 仓库写入，用户删除后不会强制恢复。",
                    actionText = "添加链接",
                    onAction = { showEditor = true }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(tools, key = { it.id }) { link ->
                    QuickToolItem(
                        link = link,
                        onOpen = {
                            context.startActivity(WebViewActivity.intent(context, link.title, link.url))
                        },
                        onEdit = { editingTool = link },
                        onDelete = { vm.deleteTool(link.id) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showEditor) {
        ToolEditorDialog(
            initial = null,
            onDismiss = { showEditor = false },
            onSave = { title, url, desc ->
                vm.createTool(title, url, desc)
                showEditor = false
            }
        )
    }

    editingTool?.let { link ->
        ToolEditorDialog(
            initial = link,
            onDismiss = { editingTool = null },
            onSave = { title, url, desc ->
                vm.saveTool(link.copy(title = title, url = url, description = desc))
                editingTool = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun QuickToolItem(
    link: QuickToolLink,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    ListItem(
        headlineContent = { Text(link.title, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (link.description.isNotBlank()) Text(link.description, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "拖拽排序依赖更完整的手势适配；当前保留基础展示、打开、编辑、删除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        leadingContent = { Icon(Icons.Default.Link, contentDescription = null) },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
            }
        },
        modifier = Modifier.combinedClickable(onClick = onOpen, onLongClick = onEdit)
    )
}

@Composable
private fun ToolEditorDialog(
    initial: QuickToolLink?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var title by remember(initial) { mutableStateOf(initial?.title.orEmpty()) }
    var url by remember(initial) { mutableStateOf(initial?.url.orEmpty()) }
    var desc by remember(initial) { mutableStateOf(initial?.description.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增快捷工具" else "编辑快捷工具") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("快捷工具只保存标题和 URL；WebView 配置在独立 Activity 中完成。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("名称") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("说明") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(title.trim(), url.trim(), desc.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SettingsScreen(vm: MainViewModel) {
    val pluginNames = vm.pluginNames
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val accountList by vm.accounts.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    var removingAccountUuid by remember { mutableStateOf<String?>(null) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    val currentAppIcon by vm.currentAppIcon.collectAsStateWithLifecycle()

    if (showIconPicker) {
        AppIconPickerScreen(
            icons = vm.appIcons,
            current = currentAppIcon,
            onSelect = vm::selectAppIcon,
            onBack = { showIconPicker = false }
        )
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader(title = "设置") }

        item { SectionTitle("账号管理") }
        val accounts = accountList
        if (accounts.isEmpty()) {
            item {
                ListItem(
                    headlineContent = { Text("当前账号") },
                    supportingContent = { Text(vm.currentAccountName) },
                    leadingContent = { Icon(Icons.Default.AccountCircle, contentDescription = null) }
                )
            }
        } else {
            items(accounts, key = { it.uuid }) { account ->
                AccountRow(
                    account = account,
                    onSwitch = { vm.switchAccount(account.uuid) },
                    onRemove = { removingAccountUuid = account.uuid }
                )
                HorizontalDivider()
            }
        }
        item {
            SettingActions(
                actions = listOf(
                    SettingAction("添加账号", Icons.Default.Add, vm::addAccount),
                    SettingAction("刷新 Token", Icons.Default.Refresh, vm::refreshToken),
                    SettingAction("退出全部", Icons.Default.Delete, vm::logout)
                )
            )
        }

        item { SectionTitle("聊天显示") }
        item {
            ListItem(
                headlineContent = { Text("自动滚动到最新聊天") },
                supportingContent = { Text("关闭后，新消息不会打断你查看历史聊天。") },
                trailingContent = {
                    Switch(
                        checked = preferences.chatAutoScroll,
                        onCheckedChange = vm::setChatAutoScroll
                    )
                }
            )
        }

        item { SectionTitle("个性化") }
        item {
            ListItem(
                headlineContent = { Text("替换启动图标") },
                supportingContent = { Text("当前：${currentAppIcon.displayName}") },
                leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { showIconPicker = true }
            )
        }

        item { SectionTitle("插件功能") }
        if (pluginNames.isEmpty()) {
            item {
                Text(
                    "插件功能暂未开启",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(pluginNames) { name ->
                var enabled by remember(name) { mutableStateOf(true) }
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text("内置插件生命周期由现有 PluginManager 管理。") },
                    leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                vm.setPluginEnabled(name, it)
                            }
                        )
                    },
                    modifier = Modifier.combinedClickable(
                        onClick = { vm.openPluginLog(name) },
                        onLongClick = { vm.openPluginLog(name) }
                    )
                )
                HorizontalDivider()
            }
        }

        item { SectionTitle("版本数据") }
        item {
            SettingActions(
                actions = listOf(
                    SettingAction("刷新版本列表", Icons.Default.Refresh) { vm.refreshVersions() },
                    SettingAction("清除缓存", Icons.Default.Delete, vm::clearVersionCache)
                )
            )
        }

        item { SectionTitle("关于") }
        item {
            ListItem(
                headlineContent = { Text("掌上碧玺") },
                supportingContent = { Text("版本 ${vm.versionNameText}\n包名 ${vm.packageNameText}") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) }
            )
        }
        item {
            ListItem(
                headlineContent = { Text("更新下载源") },
                supportingContent = { Text("${preferences.downloadSource.displayName} · ${preferences.downloadSource.description}") },
                leadingContent = { Icon(Icons.Default.Link, contentDescription = null) },
                modifier = Modifier.clickable { showSourcePicker = true }
            )
        }
        item {
            SettingActions(
                actions = listOf(
                    SettingAction("检查更新", Icons.Default.Refresh, vm::checkForUpdate)
                )
            )
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showSourcePicker) {
        DownloadSourceDialog(
            current = preferences.downloadSource,
            onSelect = {
                vm.setDownloadSource(it)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false }
        )
    }

    UpdateDialog(
        state = updateState,
        onDownload = vm::downloadUpdate,
        onInstall = vm::installUpdate,
        onDismiss = vm::dismissUpdate
    )

    removingAccountUuid?.let { uuid ->
        val target = accountList.firstOrNull { it.uuid == uuid }
        AlertDialog(
            onDismissRequest = { removingAccountUuid = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("移除账号") },
            text = { Text("确定移除账号「${target?.username ?: uuid}」？该账号的登录凭据将从本机抹除，需要时可重新登录。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAccount(uuid)
                    removingAccountUuid = null
                }) { Text("移除") }
            },
            dismissButton = {
                TextButton(onClick = { removingAccountUuid = null }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(
    account: com.bilicraft.handheld.auth.AccountSummary,
    onSwitch: () -> Unit,
    onRemove: () -> Unit
) {
    ListItem(
        headlineContent = { Text(account.username, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(if (account.isActive) "当前使用中" else "点击切换到该账号") },
        leadingContent = {
            Icon(
                Icons.Default.AccountCircle,
                contentDescription = null,
                tint = if (account.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = {
            Row {
                if (!account.isActive) {
                    IconButton(onClick = onSwitch) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "切换到该账号")
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "移除该账号")
                }
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = { if (!account.isActive) onSwitch() },
            onLongClick = onRemove
        )
    )
}

/**
 * 更新对话框：按 UpdateState 渲染每个阶段。
 * Idle 不弹窗（含启动静默自检无新版的情况）；其余状态各自呈现，操作回传给 ViewModel。
 */
@Composable
private fun UpdateDialog(
    state: UpdateState,
    onDownload: (ReleaseInfo) -> Unit,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit
) {
    if (state is UpdateState.Idle) return

    when (state) {
        is UpdateState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("检查更新") },
            text = { Text("正在查询最新版本…") },
            confirmButton = {}
        )

        is UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("已是最新版本") },
            text = { Text("当前已是最新版本，无需更新。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("好") } }
        )

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Refresh, contentDescription = null) },
            title = { Text("发现新版本 ${state.info.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.info.releaseNotes.isNotBlank()) {
                        Text(state.info.releaseNotes, style = MaterialTheme.typography.bodyMedium, maxLines = 12, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        "更新前请确认：应用内更新要求新旧包签名一致，否则系统会拒绝覆盖安装。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { onDownload(state.info) }) { Text("下载") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 ${state.info.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.progress >= 0f) {
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(state.progress * 100).toInt()}%")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("下载中…")
                    }
                }
            },
            confirmButton = {}
        )

        is UpdateState.Downloaded -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("下载完成") },
            text = { Text("${state.info.versionName} 已下载完成，点击安装继续。若系统提示，请允许安装未知来源应用。") },
            confirmButton = { TextButton(onClick = { onInstall(state.apkFile) }) { Text("安装") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
        )

        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("更新失败") },
            text = { Text(state.reason) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("好") } }
        )

        is UpdateState.Idle -> Unit
    }
}

/**
 * 更新下载源选择：单选列表，选中即回传并落盘。
 * 顺序即 DownloadSource 声明顺序（镜像在前、直连在后），呼应「优先国内镜像」。
 */
@Composable
private fun DownloadSourceDialog(
    current: DownloadSource,
    onSelect: (DownloadSource) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text("更新下载源") },
        text = {
            Column {
                DownloadSource.entries.forEach { source ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(source) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = source == current, onClick = { onSelect(source) })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(source.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                source.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/**
 * 启动图标选择页（全屏子页面）。
 * 卡片列表，每张卡预览图标 + 名称 + 描述，选中项右侧打勾。
 * 图标切换的平台副作用（桌面图标短暂消失、可能被移出最近任务）在页顶提示，避免用户误以为出错。
 */
@Composable
private fun AppIconPickerScreen(
    icons: List<AppIcon>,
    current: AppIcon,
    onSelect: (AppIcon) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "替换启动图标",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(
                    "切换后，桌面图标可能短暂消失再重现，部分系统会把应用从最近任务中清除，这是系统机制，属正常现象。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            items(icons, key = { it.id }) { icon ->
                AppIconOption(
                    icon = icon,
                    selected = icon.id == current.id,
                    onClick = { onSelect(icon) }
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppIconOption(
    icon: AppIcon,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(icon.previewResId),
                contentDescription = icon.displayName,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(icon.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    icon.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "已选用",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private data class SettingAction(
    val text: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)

@Composable
private fun SettingActions(actions: List<SettingAction>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { action ->
                    FilledTonalButton(onClick = action.onClick, modifier = Modifier.weight(1f)) {
                        Icon(action.icon, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(action.text)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionText) }
    }
}

private val CHAT_BACKGROUND = Color(0xFF1E1E1E)
private val CHAT_DEFAULT_TEXT = Color(0xFFE0E0E0)

private fun ChatEvent.toAnnotated(): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(plainText)
    return buildAnnotatedString {
        spans.forEach { span ->
            withStyle(
                SpanStyle(
                    color = span.color?.let { Color(0xFF000000.toInt() or it) } ?: Color.Unspecified,
                    fontWeight = if (span.bold) FontWeight.Bold else null,
                    fontStyle = if (span.italic) FontStyle.Italic else null,
                    textDecoration = when {
                        span.underline && span.strikethrough -> TextDecoration.combine(
                            listOf(TextDecoration.Underline, TextDecoration.LineThrough)
                        )
                        span.underline -> TextDecoration.Underline
                        span.strikethrough -> TextDecoration.LineThrough
                        else -> null
                    }
                )
            ) { append(span.text) }
        }
    }
}

private fun statusText(state: ConnectionState): String = when (state) {
    is ConnectionState.Connected -> "已连接"
    is ConnectionState.Connecting -> "连接中…"
    is ConnectionState.LoggingIn -> "登录中…"
    is ConnectionState.Reconnecting -> "重连中（第 ${state.attempt} 次）"
    is ConnectionState.Failed -> "失败：${state.reason}"
    is ConnectionState.Disconnected -> "未连接"
}