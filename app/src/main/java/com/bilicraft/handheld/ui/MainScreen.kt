package com.bilicraft.handheld.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bilicraft.handheld.announcement.AnnouncementEntry
import com.bilicraft.handheld.announcement.AnnouncementState
import com.bilicraft.handheld.appicon.AppIcon
import com.bilicraft.handheld.cdk.CdkEntry
import com.bilicraft.handheld.cdk.CdkState
import com.bilicraft.handheld.config.QuickCommandConfig
import com.bilicraft.handheld.config.QuickToolLink
import com.bilicraft.handheld.config.ServerConfig
import com.bilicraft.handheld.config.ThemeMode
import com.bilicraft.handheld.externalplugin.ExternalPluginEntry
import com.bilicraft.handheld.externalplugin.ExternalPluginEntrypoint
import com.bilicraft.handheld.externalplugin.ExternalPluginPanelHandle
import com.bilicraft.handheld.pluginmarket.OfficialPluginMarketEntry
import com.bilicraft.handheld.protocol.ChatEvent
import com.bilicraft.handheld.protocol.CommandSuggestion
import com.bilicraft.handheld.protocol.CommandSuggestionState
import com.bilicraft.handheld.protocol.CommandSuggestions
import com.bilicraft.handheld.protocol.ConnectionState
import com.bilicraft.handheld.update.DownloadSource
import com.bilicraft.handheld.update.ReleaseInfo
import com.bilicraft.handheld.update.UpdateState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import com.bilicraft.handheld.version.McVersion
import com.bilicraft.handheld.version.VersionRepository

private enum class MainTab(val title: String, val icon: ImageVector) {
    Sessions("服务器会话", Icons.Default.SportsEsports),
    Tools("快捷工具", Icons.Default.Web),
    Plugins("插件管理", Icons.Default.Build),
    Settings("设置", Icons.Default.Settings)
}

private const val ABOUT_EASTER_EGG_TAP_COUNT = 5

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
    var selectedTab by rememberSaveable { mutableStateOf(MainTab.Sessions) }
    val mainTabStateHolder = rememberSaveableStateHolder()
    val activeExternalPluginPanel by vm.activeExternalPluginPanel.collectAsStateWithLifecycle()
    val officialMarket by vm.officialMarket.collectAsStateWithLifecycle()
    val pluginUpdateCount = officialMarket.entries.count { it.updateAvailable }

    LaunchedEffect(uiMessage) {
        val message = uiMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        vm.consumeUiMessage()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == MainTab.Plugins) {
            vm.refreshOfficialPluginMarket(silent = true)
        }
    }

    activeExternalPluginPanel?.let { request ->
        val handle = remember(request.pluginId, request.entrypointId) {
            vm.externalPluginPanel(request.pluginId, request.entrypointId)
        }
        if (handle != null) {
            ExternalPluginPanelScreen(handle = handle, onClose = vm::closeExternalPlugin)
            return
        }
        LaunchedEffect(request) { vm.closeExternalPlugin() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            if (tab == MainTab.Plugins && pluginUpdateCount > 0) {
                                BadgedBox(
                                    badge = {
                                        Badge {
                                            Text(if (pluginUpdateCount > 9) "9+" else pluginUpdateCount.toString())
                                        }
                                    }
                                ) {
                                    Icon(tab.icon, contentDescription = tab.title)
                                }
                            } else {
                                Icon(tab.icon, contentDescription = tab.title)
                            }
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(Modifier.fillMaxSize().padding(innerPadding)) {
            mainTabStateHolder.SaveableStateProvider(selectedTab.name) {
                when (selectedTab) {
                    MainTab.Sessions -> ServerSessionsScreen(vm)
                    MainTab.Tools -> QuickToolsScreen(vm)
                    MainTab.Plugins -> PluginCenterScreen(vm)
                    MainTab.Settings -> SettingsScreen(vm)
                }
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

internal fun validSelectedIndex(selectedIndex: Int, itemCount: Int): Int =
    if (itemCount <= 0) 0 else selectedIndex.coerceIn(0, itemCount - 1)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ServerSessionsScreen(vm: MainViewModel) {
    val runtime by vm.serverRuntime.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val versions by vm.versions.collectAsStateWithLifecycle()
    val selectedVersion by vm.selectedVersion.collectAsStateWithLifecycle()
    val forceSigning by vm.forceSigning.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val commandSuggestions by vm.commandSuggestions.collectAsStateWithLifecycle()
    val allQuickCommands by vm.quickCommands.collectAsStateWithLifecycle()
    val pluginEntrypoints by vm.externalPluginEntrypoints.collectAsStateWithLifecycle()
    val pluginServerBindings by vm.pluginServerBindings.collectAsStateWithLifecycle()
    val serverSessionStateHolder = rememberSaveableStateHolder()

    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf<ServerConfig?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var menuServer by remember { mutableStateOf<ServerConfig?>(null) }
    var showPluginEntrypoints by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }
    var showQuickCommands by remember { mutableStateOf(false) }
    var showQuickCommandEditor by remember { mutableStateOf(false) }
    var editingQuickCommand by remember { mutableStateOf<QuickCommandConfig?>(null) }

    // StateFlow 会先发布缩短后的列表，LaunchedEffect 要到本次组合完成后才会运行。
    // 因此删除末尾服务器时，不能把旧的越界索引传给 ScrollableTabRow。
    val visibleSelectedIndex = validSelectedIndex(selectedIndex, servers.size)
    val selectedServer = servers.getOrNull(visibleSelectedIndex)
    val currentServerId = selectedServer?.id
    val currentConnected = currentServerId != null &&
        runtime.connectionStates[currentServerId] is ConnectionState.Connected
    val serverQuickCommands = allQuickCommands.filter { it.serverId == currentServerId }
    val serverPluginIds = pluginServerBindings
        .asSequence()
        .filter { it.serverId == currentServerId }
        .map { it.pluginId }
        .toSet()
    val serverPluginEntrypoints = pluginEntrypoints.filter { it.pluginId in serverPluginIds }

    LaunchedEffect(visibleSelectedIndex) {
        selectedIndex = visibleSelectedIndex
    }

    Column(Modifier.fillMaxSize()) {
        if (servers.isEmpty()) {
            EmptyState(
                title = "还没有服务器配置",
                message = "可新增服务器。默认配置来自 UI 配置仓库，用户删除后不会强制恢复。",
                actionText = "新增服务器",
                onAction = { showCreateDialog = true }
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ScrollableTabRow(
                    selectedTabIndex = visibleSelectedIndex,
                    edgePadding = 0.dp,
                    modifier = Modifier.weight(1f)
                ) {
                    servers.forEachIndexed { index, server ->
                        Tab(
                            selected = visibleSelectedIndex == index,
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
                Box {
                    IconButton(onClick = { showTopMenu = true }) {
                        Icon(Icons.Default.Menu, contentDescription = "菜单")
                    }
                    DropdownMenu(
                        expanded = showTopMenu,
                        onDismissRequest = { showTopMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("快捷指令") },
                            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
                            onClick = {
                                showTopMenu = false
                                showQuickCommands = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("复活") },
                            leadingIcon = { Icon(Icons.Default.Favorite, contentDescription = null) },
                            enabled = currentConnected,
                            onClick = {
                                showTopMenu = false
                                vm.respawn()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("插件入口") },
                            leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                            onClick = {
                                showTopMenu = false
                                showPluginEntrypoints = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("新增服务器") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                            onClick = {
                                showTopMenu = false
                                showCreateDialog = true
                            }
                        )
                    }
                }
            }

            if (selectedServer != null) {
                val selectedConn = runtime.connectionStates[selectedServer.id] ?: ConnectionState.Disconnected
                val selectedLog = runtime.chatLogs[selectedServer.id].orEmpty()
                val isActiveServer = runtime.activeServerId == selectedServer.id
                serverSessionStateHolder.SaveableStateProvider(selectedServer.id) {
                    ServerSessionPage(
                        server = selectedServer,
                        conn = selectedConn,
                        log = selectedLog,
                        isActiveServer = isActiveServer,
                        chatAutoScroll = preferences.chatAutoScroll,
                        commandCompletionEnabled = preferences.commandCompletionEnabled,
                        commandSuggestions = commandSuggestions,
                        onConnect = { vm.connect(selectedServer) },
                        onStop = vm::stopConnection,
                        onSend = { vm.sendChat(selectedServer.id, it) },
                        onRequestCommandSuggestions = { vm.requestCommandSuggestions(selectedServer.id, it) },
                        onEdit = { editingServer = selectedServer }
                    )
                }
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

    if (showQuickCommands && selectedServer != null) {
        QuickCommandsDialog(
            serverName = selectedServer.name,
            commands = serverQuickCommands,
            onAdd = {
                showQuickCommands = false
                editingQuickCommand = null
                showQuickCommandEditor = true
            },
            onEdit = { config ->
                showQuickCommands = false
                editingQuickCommand = config
                showQuickCommandEditor = true
            },
            onExecute = { config ->
                showQuickCommands = false
                vm.executeQuickCommand(selectedServer.id, config)
            },
            onDelete = vm::deleteQuickCommand,
            onDismiss = { showQuickCommands = false }
        )
    }

    if (showQuickCommandEditor && selectedServer != null) {
        QuickCommandEditorDialog(
            serverName = selectedServer.name,
            initial = editingQuickCommand,
            onDismiss = { showQuickCommandEditor = false },
            onSave = { name, content ->
                vm.saveQuickCommand(selectedServer.id, editingQuickCommand?.id, name, content)
                showQuickCommandEditor = false
                editingQuickCommand = null
                showQuickCommands = true
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

    if (showPluginEntrypoints) {
        PluginEntrypointPickerDialog(
            entrypoints = serverPluginEntrypoints,
            onOpen = { entry ->
                showPluginEntrypoints = false
                vm.openExternalPluginEntrypoint(entry.pluginId, entry.entrypointId)
            },
            onDismiss = { showPluginEntrypoints = false }
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
    commandCompletionEnabled: Boolean,
    commandSuggestions: CommandSuggestionState,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onRequestCommandSuggestions: (String) -> Unit,
    onEdit: () -> Unit
) {
    val connected = isActiveServer && conn is ConnectionState.Connected
    val connecting = isActiveServer && conn !is ConnectionState.Disconnected && conn !is ConnectionState.Failed
    var input by remember(server.id) { mutableStateOf(TextFieldValue("")) }
    var showAllSuggestions by rememberSaveable(server.id) { mutableStateOf(false) }

    LaunchedEffect(input.text, connected, commandCompletionEnabled) {
        if (!connected || !commandCompletionEnabled || !input.text.startsWith("/")) {
            onRequestCommandSuggestions("")
            return@LaunchedEffect
        }
        delay(COMMAND_COMPLETION_DEBOUNCE_MS)
        onRequestCommandSuggestions(input.text)
    }

    val visibleSuggestions = commandSuggestions.takeIf {
        connected && commandCompletionEnabled && it.requestInput == input.text && it.hasSuggestions
    }
    val expandedSuggestions = visibleSuggestions?.takeIf {
        it.suggestions.size > MAX_VISIBLE_COMMAND_SUGGESTIONS
    }
    LaunchedEffect(expandedSuggestions?.requestId, expandedSuggestions?.requestInput) {
        if (expandedSuggestions == null) showAllSuggestions = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        ServerInfoBar(
            server = server,
            conn = conn,
            connected = connected,
            connecting = connecting,
            isActiveServer = isActiveServer,
            onConnect = onConnect,
            onStop = onStop,
            onEdit = onEdit
        )

        Spacer(Modifier.height(12.dp))
        ChatLog(log = log, autoScroll = chatAutoScroll, modifier = Modifier.weight(1f).fillMaxWidth())
        if (visibleSuggestions != null) {
            Spacer(Modifier.height(8.dp))
            CommandSuggestionBar(
                state = visibleSuggestions,
                onSelect = { suggestion ->
                    val applied = CommandSuggestions.apply(input.text, visibleSuggestions.start, visibleSuggestions.length, suggestion.text)
                    input = TextFieldValue(applied, selection = TextRange(applied.length))
                    showAllSuggestions = false
                },
                onShowAll = { showAllSuggestions = true }
            )
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
                onClick = { onSend(input.text); input = TextFieldValue(""); onRequestCommandSuggestions("") },
                enabled = connected && input.text.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("发送")
            }
        }
    }
    if (showAllSuggestions && expandedSuggestions != null) {
        CommandSuggestionSheet(
            state = expandedSuggestions,
            onSelect = { suggestion ->
                val applied = CommandSuggestions.apply(input.text, expandedSuggestions.start, expandedSuggestions.length, suggestion.text)
                input = TextFieldValue(applied, selection = TextRange(applied.length))
                showAllSuggestions = false
            },
            onDismiss = { showAllSuggestions = false }
        )
    }
}

/**
 * 服务器信息条（瘦身版）。
 * 旧版是「标题+地址+三枚 chip+两个整宽按钮」竖排，占了约 5 行。
 * 现在压成单卡片一行：状态圆点 + 名称/地址两行小字 + 单个连接/断开切换按钮 + 编辑图标。
 * 连接态用一个按钮切换语义（未连时=连接，连接/连接中=断开），避免两个整宽按钮浪费纵向空间。
 * 版本号与「强制签名」降级为副标题里的小字，需要修改时进编辑弹窗，不再占主视觉。
 */
@Composable
private fun ServerInfoBar(
    server: ServerConfig,
    conn: ConnectionState,
    connected: Boolean,
    connecting: Boolean,
    isActiveServer: Boolean,
    onConnect: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit
) {
    val active = connected || connecting
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(conn)
            Column(Modifier.weight(1f)) {
                Text(
                    server.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        statusText(conn),
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(conn),
                        maxLines = 1
                    )
                    Text(
                        "· ${server.host}:${server.port} · ${server.versionId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (server.signingRequired) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "强制签名",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active) {
                FilledTonalButton(
                    onClick = onStop,
                    enabled = isActiveServer,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (connecting && !connected) "连接中" else "断开")
                }
            } else {
                Button(
                    onClick = onConnect,
                    contentPadding = PaddingValues(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("连接")
                }
            }
        }
    }
}

/** 连接状态色点：绿=已连接，黄=进行中，红=失败，灰=未连接。Reconnecting 时脉冲提示。 */
@Composable
private fun StatusDot(conn: ConnectionState) {
    Box(
        Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(statusColor(conn))
    )
}

private val StatusGreen = Color(0xFF2E7D32)
private val StatusAmber = Color(0xFFF9A825)
private val StatusRed = Color(0xFFC62828)
private val StatusGray = Color(0xFF9E9E9E)

private fun statusColor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Connected -> StatusGreen
    is ConnectionState.Connecting,
    is ConnectionState.LoggingIn,
    is ConnectionState.Reconnecting -> StatusAmber
    is ConnectionState.Failed -> StatusRed
    is ConnectionState.Disconnected -> StatusGray
}

@Composable
private fun PluginEntrypointPickerDialog(
    entrypoints: List<ExternalPluginEntrypoint>,
    onOpen: (ExternalPluginEntrypoint) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插件入口") },
        text = {
            if (entrypoints.isEmpty()) {
                Text("当前服务器没有已加载的可用插件。请前往“插件管理”，在目标插件旁点击“加载到服务器”。")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    entrypoints.forEach { entry ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Text(
                                text = entry.pluginName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun CommandSuggestionBar(
    state: CommandSuggestionState,
    onSelect: (CommandSuggestion) -> Unit,
    onShowAll: () -> Unit
) {
    if (state.suggestions.size <= MAX_VISIBLE_COMMAND_SUGGESTIONS) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.suggestions) { suggestion ->
                AssistChip(
                    onClick = { onSelect(suggestion) },
                    label = {
                        Text(
                            text = suggestion.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
    } else {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onShowAll),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "找到 ${state.suggestions.size} 个候选",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "点击查看全部命令补全",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                TextButton(onClick = onShowAll) { Text("查看全部") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommandSuggestionSheet(
    state: CommandSuggestionState,
    onSelect: (CommandSuggestion) -> Unit,
    onDismiss: () -> Unit
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenHeight * 0.5f)
        ) {
            Text(
                text = "命令补全（${state.suggestions.size} 个候选）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                items(state.suggestions) { suggestion ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = suggestion.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        supportingContent = suggestion.tooltip?.let { tooltip ->
                            { Text(tooltip, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        },
                        modifier = Modifier.clickable { onSelect(suggestion) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ChatLog(log: List<ChatEvent>, autoScroll: Boolean, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    LaunchedEffect(log.size, log.lastOrNull(), autoScroll) {
        if (!autoScroll || log.isEmpty()) return@LaunchedEffect

        // The effect can run immediately after the log state changes, before the
        // LazyColumn has measured the newly appended item. Wait until the target
        // item exists in layoutInfo; otherwise scrollToItem may clamp to the old
        // last index and never get another trigger.
        val targetIndex = log.lastIndex
        snapshotFlow { listState.layoutInfo.totalItemsCount }
            .first { totalItemsCount -> totalItemsCount >= log.size }

        // The setting is the sole switch: when enabled, every log update follows
        // the newest item even if the user had manually scrolled away earlier.
        // Do not animate each item in a burst: each new item would cancel the
        // previous animation and leave the list somewhere in the middle.
        listState.scrollToItem(targetIndex)
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        clipboardManager.setText(AnnotatedString(ev.plainText))
                        Toast.makeText(context, "已复制聊天内容", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun QuickCommandsDialog(
    serverName: String,
    commands: List<QuickCommandConfig>,
    onAdd: () -> Unit,
    onEdit: (QuickCommandConfig) -> Unit,
    onExecute: (QuickCommandConfig) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val widthFraction = if (configuration.screenWidthDp > configuration.screenHeightDp) 0.62f else 0.9f

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .widthIn(max = 340.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "快捷指令",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    FilledTonalIconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = "新增快捷指令")
                    }
                }

                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (commands.isEmpty()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "还没有快捷指令",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "点击右上角＋添加",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        commands.forEach { config ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onExecute(config) },
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = config.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = if (config.command.startsWith("/")) {
                                                "命令 · ${config.command}"
                                            } else {
                                                "聊天 · ${config.command}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(onClick = { onEdit(config) }) {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = "编辑${config.name}",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    IconButton(onClick = { onDelete(config.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "删除${config.name}",
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("关闭") }
            }
        }
    }
}

@Composable
private fun QuickCommandEditorDialog(
    serverName: String,
    initial: QuickCommandConfig?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var content by remember(initial?.id) { mutableStateOf(initial?.command.orEmpty()) }
    val canSave = name.trim().isNotEmpty() && content.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增快捷内容" else "编辑快捷内容") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "保存到「$serverName」，只会在该服务器下显示。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("快捷名称") },
                    placeholder = { Text("例如：返回主城或打招呼") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("发送内容") },
                    placeholder = { Text("例如：/spawn 或 大家好") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "以 / 开头会作为命令执行，其他内容会作为普通聊天发送。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim(), content.trim()) },
                enabled = canSave
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
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
        supportingContent = link.description.takeIf { it.isNotBlank() }?.let {
            { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) }
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
    val context = LocalContext.current
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val accountList by vm.accounts.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()
    val cdkState by vm.cdkState.collectAsStateWithLifecycle()
    val announcementState by vm.announcementState.collectAsStateWithLifecycle()
    var removingAccountUuid by remember { mutableStateOf<String?>(null) }
    var showSourcePicker by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showIconPicker by remember { mutableStateOf(false) }
    var showAnnouncementHistory by remember { mutableStateOf(false) }
    var aboutTapCount by remember { mutableIntStateOf(0) }
    val currentAppIcon by vm.currentAppIcon.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshCdkActiveWindow()
            delay(CDK_ACTIVE_WINDOW_REFRESH_MS)
        }
    }

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
                supportingContent = { Text("点击消息可复制；关闭后，新消息不会打断你查看历史聊天。") },
                trailingContent = {
                    Switch(
                        checked = preferences.chatAutoScroll,
                        onCheckedChange = vm::setChatAutoScroll
                    )
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text("命令补全") },
                supportingContent = { Text("输入 / 命令时向服务器请求候选项。关闭后不发补全请求。") },
                trailingContent = {
                    Switch(
                        checked = preferences.commandCompletionEnabled,
                        onCheckedChange = vm::setCommandCompletionEnabled
                    )
                }
            )
        }

        item { SectionTitle("外观") }
        item {
            ListItem(
                headlineContent = { Text("明暗主题") },
                supportingContent = { Text(preferences.themeMode.displayName) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
                modifier = Modifier.clickable { showThemePicker = true }
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

        item { SectionTitle("CDK") }
        item {
            CdkModuleCard(
                state = cdkState,
                onRefresh = vm::refreshCdk,
                onClaim = vm::claimCdk,
                onClaimCustom = vm::claimCustomCdk
            )
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
                supportingContent = { Text("版本 ${vm.versionNameText}") },
                leadingContent = { Icon(Icons.Default.Info, contentDescription = null) },
                modifier = Modifier.clickable {
                    aboutTapCount += 1
                    if (aboutTapCount >= ABOUT_EASTER_EGG_TAP_COUNT) {
                        aboutTapCount = 0
                        context.startActivity(EasterEggVideoActivity.intent(context))
                    }
                }
            )
        }
        item {
            ListItem(
                headlineContent = { Text("下载线路") },
                supportingContent = { Text(preferences.downloadSource.displayName) },
                leadingContent = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
                trailingContent = { Icon(Icons.Default.ChevronRight, contentDescription = null) },
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

        item { SectionTitle("公告") }
        item {
            AnnouncementModuleCard(
                state = announcementState,
                onRefresh = vm::refreshAnnouncements,
                onOpenHistory = { showAnnouncementHistory = true }
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

    if (showThemePicker) {
        AlertDialog(
            onDismissRequest = { showThemePicker = false },
            title = { Text("明暗主题") },
            text = {
                Column {
                    ThemeMode.entries.forEach { themeMode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setThemeMode(themeMode)
                                    showThemePicker = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = preferences.themeMode == themeMode,
                                onClick = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(themeMode.displayName)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemePicker = false }) { Text("取消") }
            }
        )
    }

    UpdateDialog(
        state = updateState,
        onDownload = vm::downloadUpdate,
        onInstall = vm::installUpdate,
        onDismiss = vm::dismissUpdate
    )

    if (showAnnouncementHistory) {
        AnnouncementHistoryDialog(
            entries = announcementState.entries,
            onDismiss = { showAnnouncementHistory = false }
        )
    }

    removingAccountUuid?.let { uuid ->
        val target = accountList.firstOrNull { it.uuid == uuid }
        AlertDialog(
            onDismissRequest = { removingAccountUuid = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("移除账号") },
            text = {
                Text(
                    if (target?.isOffline == true) {
                        "确定移除离线账号「${target.username}」？该账号将从本机账号列表中删除。"
                    } else {
                        "确定移除账号「${target?.username ?: uuid}」？该账号的登录凭据将从本机抹除，需要时可重新登录。"
                    }
                )
            },
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

@Composable
private fun AnnouncementModuleCard(
    state: AnnouncementState,
    onRefresh: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val latest = state.entries.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = latest?.title ?: "官方公告",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    latest?.let {
                        Text(
                            text = "${announcementTypeText(it)} · ${announcementDateText(it.publishedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新公告")
                }
            }

            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Text(
                text = latest?.content ?: if (state.loading) "正在加载公告…" else "暂时没有公告",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            state.errorMessage?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            OutlinedButton(
                onClick = onOpenHistory,
                enabled = state.entries.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.entries.isEmpty()) "暂无历史公告" else "查看历史公告（${state.entries.size}）")
            }
        }
    }
}

@Composable
private fun AnnouncementHistoryDialog(
    entries: List<AnnouncementEntry>,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f).widthIn(max = 520.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("历史公告", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "共 ${entries.size} 条，版本更新与独立公告均由 CDN 保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    entries.forEach { entry ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(entry.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${announcementTypeText(entry)} · ${announcementDateText(entry.publishedAt)}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(entry.content, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("关闭") }
            }
        }
    }
}

private fun announcementTypeText(entry: AnnouncementEntry): String =
    if (entry.type == AnnouncementEntry.TYPE_RELEASE) {
        entry.versionName?.takeIf { it.isNotBlank() }?.let { "版本更新 $it" } ?: "版本更新"
    } else {
        "官方公告"
    }

private fun announcementDateText(value: String): String =
    value.take(10).replace('-', '.')

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CdkModuleCard(
    state: CdkState,
    onRefresh: () -> Unit,
    onClaim: (String) -> Unit,
    onClaimCustom: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var showCustomCdkDialog by rememberSaveable { mutableStateOf(false) }
    var customCdk by rememberSaveable { mutableStateOf("") }
    var copiedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    val entryCount = state.entries.size
    val pagerState = rememberPagerState(pageCount = { entryCount.coerceAtLeast(1) })
    val selectedEntry = state.entries.getOrNull(pagerState.currentPage)

    LaunchedEffect(entryCount) {
        val lastAvailablePage = state.entries.lastIndex.coerceAtLeast(0)
        if (pagerState.currentPage > lastAvailablePage) {
            pagerState.scrollToPage(lastAvailablePage)
        }
    }

    LaunchedEffect(copiedEntryId) {
        if (copiedEntryId == null) return@LaunchedEffect
        delay(CDK_COPY_FEEDBACK_MS)
        copiedEntryId = null
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "福利兑换码",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        when {
                            state.loading && entryCount == 0 -> "正在获取可领取内容"
                            entryCount > 1 -> "共 $entryCount 个可领取内容，左右滑动切换"
                            entryCount == 1 -> "当前有 1 个可领取内容"
                            else -> "限时福利，记得及时兑换"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            state.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(244.dp),
                pageSpacing = 12.dp
            ) { page ->
                val entry = state.entries.getOrNull(page)
                if (entry == null) {
                    CdkEmptyPage(loading = state.loading)
                } else {
                    CdkEntryPage(entry)
                }
            }

            if (entryCount > 1) {
                CdkPageIndicator(
                    pageCount = entryCount,
                    currentPage = pagerState.currentPage.coerceIn(0, entryCount - 1)
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            selectedEntry?.let { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(entry.code))
                            copiedEntryId = entry.id
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (copiedEntryId == entry.id) "已复制" else "复制兑换码")
                    }
                    FilledTonalButton(
                        onClick = { onClaim(entry.code) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("立即领取")
                    }
                }
            }

            FilledTonalButton(
                onClick = { showCustomCdkDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("手动输入兑换码")
            }
        }
    }

    if (showCustomCdkDialog) {
        AlertDialog(
            onDismissRequest = { showCustomCdkDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text("手动领取兑换码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "输入兑换码后，确认即可在当前服务器中领取。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = customCdk,
                        onValueChange = { customCdk = it },
                        label = { Text("兑换码") },
                        placeholder = { Text("请输入兑换码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClaimCustom(customCdk)
                        customCdk = ""
                        showCustomCdkDialog = false
                    },
                    enabled = customCdk.trim().removePrefix("/").isNotBlank()
                ) { Text("领取") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomCdkDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CdkEntryPage(entry: CdkEntry) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    entry.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            entry.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "兑换码",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    entry.code,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            cdkWindowText(entry)?.let { windowText ->
                Text(
                    windowText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

        }
    }
}

@Composable
private fun CdkEmptyPage(loading: Boolean) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (loading) "正在获取兑换码" else "暂时没有可领取的兑换码",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (loading) "请稍候" else "你仍然可以手动输入兑换码",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CdkPageIndicator(pageCount: Int, currentPage: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(pageCount) { page ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (page == currentPage) 9.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (page == currentPage) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "${currentPage + 1} / $pageCount",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun cdkWindowText(entry: CdkEntry): String? = when {
    !entry.startsAt.isNullOrBlank() && !entry.endsAt.isNullOrBlank() ->
        "可领取时间：${formatCdkTime(entry.startsAt)} ~ ${formatCdkTime(entry.endsAt)}"
    !entry.startsAt.isNullOrBlank() -> "开始时间：${formatCdkTime(entry.startsAt)}"
    !entry.endsAt.isNullOrBlank() -> "截止时间：${formatCdkTime(entry.endsAt)}"
    else -> null
}

private fun formatCdkTime(value: String): String = runCatching {
    val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).parse(value)
        ?: return@runCatching value
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(parsed)
}.getOrDefault(value)

@Composable
private fun PluginCenterScreen(vm: MainViewModel) {
    val officialMarket by vm.officialMarket.collectAsStateWithLifecycle()
    val externalPlugins by vm.externalPlugins.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val pluginServerBindings by vm.pluginServerBindings.collectAsStateWithLifecycle()
    var pluginToLoad by remember { mutableStateOf<ExternalPluginEntry?>(null) }
    val pluginImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importExternalPlugin(uri)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item { SectionTitle("已安装插件") }
        item {
            ListItem(
                headlineContent = { Text("插件目录") },
                supportingContent = { Text(vm.pluginDropDirText) },
                leadingContent = { Icon(Icons.Default.Build, contentDescription = null) }
            )
        }
        item {
            SettingActions(
                actions = listOf(
                    SettingAction("导入插件包", Icons.Default.Add) {
                        pluginImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    SettingAction("重新扫描", Icons.Default.Refresh, vm::refreshExternalPlugins)
                )
            )
        }
        if (externalPlugins.isEmpty()) {
            item {
                Text(
                    "把 .bhplugin 文件放入上方目录，或点击“导入插件包”选择外部文件。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(externalPlugins, key = { "installed-${it.id}" }) { plugin ->
                ExternalPluginRow(
                    plugin = plugin,
                    loadedServerCount = pluginServerBindings.count { it.pluginId == plugin.id },
                    onLoadToServer = { pluginToLoad = plugin },
                    onEnabledChange = { enabled -> vm.setExternalPluginEnabled(plugin.id, enabled) },
                    onRemove = { vm.uninstallExternalPlugin(plugin.id) }
                )
                HorizontalDivider()
            }
        }

        item { SectionTitle("官方插件市场") }
        item {
            OfficialPluginMarketHeader(
                updatedAt = officialMarket.updatedAt,
                loading = officialMarket.loading,
                errorMessage = officialMarket.errorMessage,
                onRefresh = { vm.refreshOfficialPluginMarket() }
            )
        }
        if (officialMarket.entries.isEmpty() && !officialMarket.loading) {
            item {
                Text(
                    "官方源暂无可展示插件。刷新失败时会保留本地缓存。",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            items(officialMarket.entries, key = { "market-${it.id}" }) { entry ->
                OfficialPluginMarketRow(
                    entry = entry,
                    onInstall = { vm.installOfficialPlugin(entry.id) }
                )
                HorizontalDivider()
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    pluginToLoad?.let { plugin ->
        PluginServerLoadDialog(
            plugin = plugin,
            servers = servers,
            initiallyLoadedServerIds = pluginServerBindings
                .asSequence()
                .filter { it.pluginId == plugin.id }
                .map { it.serverId }
                .toSet(),
            onSave = { serverIds ->
                vm.setPluginServers(plugin.id, serverIds)
                pluginToLoad = null
            },
            onDismiss = { pluginToLoad = null }
        )
    }
}

@Composable
private fun OfficialPluginMarketHeader(
    updatedAt: String?,
    loading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("官方精选", fontWeight = FontWeight.SemiBold)
                    Text(
                        "经过官方审核的插件，安全可靠",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新官方插件源")
                }
            }
            updatedAt?.takeIf { it.isNotBlank() }?.let {
                Text("索引更新时间：$it", style = MaterialTheme.typography.bodySmall)
            }
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun OfficialPluginMarketRow(
    entry: OfficialPluginMarketEntry,
    onInstall: () -> Unit
) {
    val actionText = when {
        !entry.compatible -> "不兼容"
        entry.updateAvailable -> "更新"
        entry.installed -> "已安装"
        else -> "安装"
    }
    val detail = buildList {
        add("最新 ${entry.latestVersion}" + (entry.installedVersion?.let { " · 已装 $it" } ?: ""))
        entry.author.takeIf { it.isNotBlank() }?.let { add("作者：$it") }
        entry.summary.takeIf { it.isNotBlank() }?.let { add(it) }
        entry.description.takeIf { it.isNotBlank() && it != entry.summary }?.let { add(it) }
        entry.downloadSize?.let { add("大小：${formatPluginPackageSize(it)}") }
        if (entry.permissions.isNotEmpty()) add("权限：${entry.permissions.joinToString()}")
        entry.changelog.takeIf { it.isNotBlank() }?.let { add("更新：$it") }
    }.joinToString("\n")

    ListItem(
        headlineContent = { Text(entry.name, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(
                detail,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingContent = { Icon(Icons.Default.Build, contentDescription = null) },
        trailingContent = {
            Button(
                onClick = onInstall,
                enabled = entry.compatible && (!entry.installed || entry.updateAvailable)
            ) {
                Text(actionText)
            }
        }
    )
}

private fun formatPluginPackageSize(size: Long): String = when {
    size >= 1024L * 1024L -> "%.1f MB".format(size / 1024f / 1024f)
    size >= 1024L -> "%.1f KB".format(size / 1024f)
    else -> "$size B"
}
@Composable
private fun ExternalPluginPanelScreen(
    handle: ExternalPluginPanelHandle,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize()) {
        handle.panel.Content(host = handle.host, onClose = onClose)
    }
}

@Composable
private fun PluginServerLoadDialog(
    plugin: ExternalPluginEntry,
    servers: List<ServerConfig>,
    initiallyLoadedServerIds: Set<String>,
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedServerIds by remember(plugin.id, initiallyLoadedServerIds) {
        mutableStateOf(initiallyLoadedServerIds)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加载到服务器") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "选择允许显示「${plugin.name}」入口的服务器。",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (servers.isEmpty()) {
                    Text(
                        text = "还没有服务器配置。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    servers.forEach { server ->
                        val selected = server.id in selectedServerIds
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedServerIds = if (selected) {
                                        selectedServerIds - server.id
                                    } else {
                                        selectedServerIds + server.id
                                    }
                                },
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) {
                                    MaterialTheme.colorScheme.secondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selected,
                                    onCheckedChange = { checked ->
                                        selectedServerIds = if (checked) {
                                            selectedServerIds + server.id
                                        } else {
                                            selectedServerIds - server.id
                                        }
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = server.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${server.host}:${server.port}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selectedServerIds) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ExternalPluginRow(
    plugin: ExternalPluginEntry,
    loadedServerCount: Int,
    onLoadToServer: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val status = when {
        !plugin.enabled -> "已禁用"
        plugin.loaded -> "已启用"
        else -> "加载失败"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.Build,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = listOfNotNull(
                        "$status · ${plugin.version}",
                        loadedServerCount.takeIf { it > 0 }?.let { "已加载到 $it 个服务器" },
                        plugin.description.takeIf { it.isNotBlank() },
                        plugin.statusMessage
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onLoadToServer,
                enabled = plugin.enabled && plugin.loaded
            ) {
                Text("加载到服务器", maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = plugin.enabled,
                onCheckedChange = onEnabledChange
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "移除外部插件")
            }
        }
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
        supportingContent = {
            val type = if (account.isOffline) "离线账号" else "微软账号"
            Text(if (account.isActive) "$type · 当前使用中" else "$type · 点击切换")
        },
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
                    Text("更新内容", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        state.info.releaseNotes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                    )
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
                        val percent = (state.progress * 100).toInt().coerceIn(0, 100)
                        val totalBytes = state.info.apkSizeBytes
                        val downloadedBytes = if (totalBytes > 0L) {
                            (state.progress * totalBytes).toLong().coerceIn(0L, totalBytes)
                        } else {
                            0L
                        }
                        Text(
                            if (totalBytes > 0L) {
                                "$percent% · ${formatDownloadSize(downloadedBytes)} / ${formatDownloadSize(totalBytes)}"
                            } else {
                                "$percent%"
                            }
                        )
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

private fun formatDownloadSize(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

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
        icon = { Icon(Icons.Default.SwapHoriz, contentDescription = null) },
        title = { Text("下载线路") },
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
private const val COMMAND_COMPLETION_DEBOUNCE_MS = 200L
private const val CDK_ACTIVE_WINDOW_REFRESH_MS = 60_000L
private const val CDK_COPY_FEEDBACK_MS = 1_600L
private const val MAX_VISIBLE_COMMAND_SUGGESTIONS = 6

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
