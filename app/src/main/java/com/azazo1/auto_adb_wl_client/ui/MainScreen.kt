package com.azazo1.auto_adb_wl_client.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.azazo1.auto_adb_wl_client.data.DiscoveredService
import com.azazo1.auto_adb_wl_client.data.ScrcpyLaunchMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 权限请求
    var hasPermissions by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
        if (hasPermissions) {
            viewModel.startDiscovery()
        }
    }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val allGranted = perms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            hasPermissions = true
            viewModel.startDiscovery()
        } else {
            permissionLauncher.launch(perms)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text("Auto ADB Client")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 服务发现状态卡片
            ServiceDiscoveryCard(
                isDiscovering = uiState.isDiscovering,
                discoveredServices = uiState.discoveredServices,
                selectedService = uiState.selectedService,
                discoveryError = uiState.discoveryError,
                onServiceSelect = { viewModel.selectService(it) },
                onStartDiscovery = { viewModel.startDiscovery() },
                onStopDiscovery = { viewModel.stopDiscovery() },
                modifier = Modifier.padding(16.dp)
            )

            // 手动输入地址
            ManualAddressCard(
                manualAddress = uiState.manualAddress,
                manualPort = uiState.manualPort,
                onAddressChange = { viewModel.updateManualAddress(it) },
                onPortChange = { viewModel.updateManualPort(it) },
                selectedService = uiState.selectedService,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            // 操作按钮
            OperationButtons(
                hasService = uiState.selectedService != null || uiState.manualAddress.isNotBlank(),
                isConnecting = uiState.isConnecting,
                isPairing = uiState.isPairing,
                isLaunchingScrcpy = uiState.isLaunchingScrcpy,
                onConnect = { viewModel.adbConnect(it) },
                onShowPairDialog = { viewModel.showPairDialog() },
                onShowScrcpyDialog = { viewModel.showScrcpyDialog() },
                modifier = Modifier.padding(16.dp)
            )

            // 操作结果
            uiState.lastOperationResult?.let { result ->
                OperationResultCard(
                    result = result,
                    onDismiss = { viewModel.clearOperationResult() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }

    // 配对对话框
    if (uiState.showPairDialog) {
        PairDialog(
            pairCode = uiState.pairCode,
            onPairCodeChange = { viewModel.updatePairCode(it) },
            onDismiss = { viewModel.hidePairDialog() },
            onConfirm = { address, code -> viewModel.adbPair(address, code) }
        )
    }

    // Scrcpy 模式选择对话框
    if (uiState.showScrcpyDialog) {
        ScrcpyModeDialog(
            onDismiss = { viewModel.hideScrcpyDialog() },
            onConfirm = { mode -> viewModel.launchScrcpy(mode) }
        )
    }
}

/**
 * 服务发现卡片
 */
@Composable
fun ServiceDiscoveryCard(
    isDiscovering: Boolean,
    discoveredServices: List<DiscoveredService>,
    selectedService: Int?,
    discoveryError: String?,
    onServiceSelect: (Int) -> Unit,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题和发现按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 发现动画指示器
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDiscovering) Color.Green else Color.Gray
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "服务发现",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 发现按钮
                Button(
                    onClick = { if (isDiscovering) onStopDiscovery() else onStartDiscovery() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDiscovering)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (isDiscovering) Icons.Default.Stop else Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isDiscovering) "停止" else "发现")
                }
            }

            // 错误提示
            discoveryError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // 服务列表
            Spacer(modifier = Modifier.height(12.dp))
            if (discoveredServices.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (isDiscovering) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "正在搜索服务...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击\"发现\"按钮搜索服务",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(discoveredServices.size) { service ->
                        ServiceItem(
                            service = discoveredServices[service],
                            isSelected = selectedService == service,
                            onClick = { onServiceSelect(service) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 服务列表项
 */
@Composable
fun ServiceItem(
    service: DiscoveredService,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Computer,
                contentDescription = null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = service.displayAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * 手动地址输入卡片
 */
@Composable
fun ManualAddressCard(
    manualAddress: String,
    manualPort: String,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    selectedService: Int?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "手动输入地址",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = onAddressChange,
                    label = { Text("IP 地址") },
                    placeholder = { Text("例如: 192.168.1.100") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(2f),
                    enabled = selectedService == null,
                    leadingIcon = {
                        Icon(Icons.Default.Router, contentDescription = null)
                    }
                )

                OutlinedTextField(
                    value = manualPort,
                    onValueChange = onPortChange,
                    label = { Text("端口") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    enabled = selectedService == null
                )
            }

            if (selectedService != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "已选择自动发现的服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

/**
 * 操作按钮区域
 */
@Composable
fun OperationButtons(
    hasService: Boolean,
    isConnecting: Boolean,
    isPairing: Boolean,
    isLaunchingScrcpy: Boolean,
    onConnect: (String) -> Unit,
    onShowPairDialog: () -> Unit,
    onShowScrcpyDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var connectAddress by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "操作",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 连接地址输入
        OutlinedTextField(
            value = connectAddress,
            onValueChange = { connectAddress = it },
            label = { Text("ADB 设备地址") },
            placeholder = { Text("例如: 192.168.1.50:5555") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = {
                Icon(Icons.Default.DevicesOther, contentDescription = null)
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 连接按钮
            OperationButton(
                text = "ADB 连接",
                icon = Icons.Default.Link,
                isLoading = isConnecting,
                enabled = hasService && connectAddress.isNotBlank() && !isConnecting && !isPairing && !isLaunchingScrcpy,
                onClick = { onConnect(connectAddress) },
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.primary
            )

            // 配对按钮
            OperationButton(
                text = "ADB 配对",
                icon = Icons.Default.ConnectingAirports, // Pair
                isLoading = isPairing,
                enabled = hasService && !isConnecting && !isPairing && !isLaunchingScrcpy,
                onClick = onShowPairDialog,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Scrcpy 按钮
        OperationButton(
            text = "启动 Scrcpy",
            icon = Icons.Default.PlayArrow,
            isLoading = isLaunchingScrcpy,
            enabled = hasService && !isConnecting && !isPairing && !isLaunchingScrcpy,
            onClick = onShowScrcpyDialog,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.tertiary
        )
    }
}

/**
 * 操作按钮
 */
@Composable
fun OperationButton(
    text: String,
    icon: ImageVector,
    isLoading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            disabledContainerColor = containerColor.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * 操作结果卡片
 */
@Composable
fun OperationResultCard(
    result: OperationResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (result.success)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.errorContainer

    val contentColor = if (result.success)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onErrorContainer

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (result.type) {
                        OperationType.CONNECT -> "ADB 连接"
                        OperationType.PAIR -> "ADB 配对"
                        OperationType.SCRCPY -> "Scrcpy 启动"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = result.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "关闭",
                    tint = contentColor
                )
            }
        }
    }
}

/**
 * 配对对话框
 */
@Composable
fun PairDialog(
    pairCode: String,
    onPairCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var address by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ConnectingAirports /* Pair */, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ADB 配对")
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("设备地址") },
                    placeholder = { Text("例如: 192.168.1.50:37123") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = pairCode,
                    onValueChange = onPairCodeChange,
                    label = { Text("配对码") },
                    placeholder = { Text("6位数字配对码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(address, pairCode) },
                enabled = address.isNotBlank() && pairCode.isNotBlank()
            ) {
                Text("配对")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * Scrcpy 模式选择对话框
 */
@Composable
fun ScrcpyModeDialog(
    onDismiss: () -> Unit,
    onConfirm: (ScrcpyLaunchMode) -> Unit
) {
    var selectedMode by remember { mutableStateOf(ScrcpyMode.USB) }
    var serialNumber by remember { mutableStateOf("") }
    var tcpAddress by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("启动 Scrcpy")
            }
        },
        text = {
            Column {
                Text("选择启动模式:", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                // 模式选项
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ScrcpyMode.values().forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            RadioButton(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = when (mode) {
                                    ScrcpyMode.USB -> "USB 设备 (-d)"
                                    ScrcpyMode.TCP_IP -> "TCP/IP 设备 (-e)"
                                    ScrcpyMode.SERIAL -> "指定序列号"
                                    ScrcpyMode.TCP_IP_CONNECT -> "TCP/IP 连接"
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                // 额外参数输入
                AnimatedVisibility(visible = selectedMode == ScrcpyMode.SERIAL) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = serialNumber,
                            onValueChange = { serialNumber = it },
                            label = { Text("设备序列号") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                AnimatedVisibility(visible = selectedMode == ScrcpyMode.TCP_IP_CONNECT) {
                    Column {
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = tcpAddress,
                            onValueChange = { tcpAddress = it },
                            label = { Text("设备地址") },
                            placeholder = { Text("例如: 192.168.1.50:5555") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mode = when (selectedMode) {
                        ScrcpyMode.USB -> ScrcpyLaunchMode.usb()
                        ScrcpyMode.TCP_IP -> ScrcpyLaunchMode.tcpIp()
                        ScrcpyMode.SERIAL -> ScrcpyLaunchMode.serial(serialNumber)
                        ScrcpyMode.TCP_IP_CONNECT -> ScrcpyLaunchMode.tcpIpConnect(tcpAddress)
                    }
                    onConfirm(mode)
                },
                enabled = when (selectedMode) {
                    ScrcpyMode.USB, ScrcpyMode.TCP_IP -> true
                    ScrcpyMode.SERIAL -> serialNumber.isNotBlank()
                    ScrcpyMode.TCP_IP_CONNECT -> tcpAddress.isNotBlank()
                }
            ) {
                Text("启动")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

enum class ScrcpyMode {
    USB, TCP_IP, SERIAL, TCP_IP_CONNECT
}
