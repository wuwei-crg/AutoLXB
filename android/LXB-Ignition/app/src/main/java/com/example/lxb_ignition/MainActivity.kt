package com.example.lxb_ignition

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lxb_ignition.shizuku.ShizukuManager
import com.example.lxb_ignition.ui.theme.LXBIgnitionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LXBIgnitionTheme {
                LXBIgnitionApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LXBIgnitionApp(viewModel: MainViewModel = viewModel()) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("🚀 控制", "⚙️ 配置", "📜 日志")

    Scaffold(
        topBar = { TopAppBar(title = { Text("LXB Ignition") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(label.take(2), fontSize = 18.sp) },
                        label = { Text(label.drop(2).trim()) }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> ControlTab(viewModel, Modifier.padding(innerPadding))
            1 -> ConfigTab(viewModel, Modifier.padding(innerPadding))
            2 -> LogsTab(viewModel, Modifier.padding(innerPadding))
        }
    }
}

// ─── Tab 1: 控制 ──────────────────────────────────────────────────────────────

@Composable
fun ControlTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val requirement by viewModel.requirement.collectAsState()
    val sendResult by viewModel.sendResult.collectAsState()
    val tcpMockPort by viewModel.tcpMockPort.collectAsState()
    val tcpMockRunning by viewModel.tcpMockRunning.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 状态卡
        ShizukuStatusCard(state = state, message = statusMessage)

        // 控制按钮
        ServerControlRow(
            state = state,
            onRequestPermission = { viewModel.requestShizukuPermission() },
            onStart = { viewModel.startServer() },
            onStop = { viewModel.stopServer() }
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("TCP Mock Service", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (tcpMockRunning) "当前状态：运行中（端口 $tcpMockPort）" else "当前状态：未启动",
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startTcpMockService() },
                        modifier = Modifier.weight(1f),
                        enabled = !tcpMockRunning
                    ) {
                        Text("启动 TCP Mock")
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopTcpMockService() },
                        modifier = Modifier.weight(1f),
                        enabled = tcpMockRunning
                    ) {
                        Text("停止 TCP Mock")
                    }
                }
            }
        }

        // 发送需求
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("发送需求", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = requirement,
                    onValueChange = { viewModel.requirement.value = it },
                    label = { Text("输入需求内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.sendRequirement() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("发送到服务器")
                    }
                }
                if (sendResult.isNotEmpty()) {
                    Text(
                        text = sendResult,
                        fontSize = 12.sp,
                        color = if (sendResult.startsWith("HTTP 2")) Color(0xFF4CAF50)
                        else Color(0xFFFF9800)
                    )
                }
            }
        }

    }
}

@Composable
fun ShizukuStatusCard(state: ShizukuManager.State, message: String) {
    val (bgColor, label) = when (state) {
        ShizukuManager.State.UNAVAILABLE -> Color(0xFF9E9E9E) to "Shizuku 未就绪"
        ShizukuManager.State.PERMISSION_DENIED -> Color(0xFFFF9800) to "需要授权"
        ShizukuManager.State.READY -> Color(0xFF2196F3) to "就绪"
        ShizukuManager.State.STARTING -> Color(0xFF9C27B0) to "启动中..."
        ShizukuManager.State.RUNNING -> Color(0xFF4CAF50) to "运行中 ✓"
        ShizukuManager.State.ERROR -> Color(0xFFF44336) to "错误"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
            if (message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(message, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun ServerControlRow(
    state: ShizukuManager.State,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (state == ShizukuManager.State.PERMISSION_DENIED) {
            Button(
                onClick = onRequestPermission,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
            ) {
                Text("授权 Shizuku")
            }
        }
        Button(
            onClick = onStart,
            enabled = state == ShizukuManager.State.READY || state == ShizukuManager.State.ERROR,
            modifier = Modifier.weight(1f)
        ) {
            Text("启动服务")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = state == ShizukuManager.State.RUNNING,
            modifier = Modifier.weight(1f)
        ) {
            Text("停止服务")
        }
    }
}

@Composable
fun LogPanel(logLines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) listState.animateScrollToItem(logLines.size - 1)
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp).fillMaxSize()) {
            Text("日志", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.05f))
            ) {
                items(logLines) { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            line.startsWith("[ERR]") -> Color(0xFFF44336)
                            line.startsWith("[LXB]") -> Color(0xFF2196F3)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

// ─── Tab 2: 配置 ──────────────────────────────────────────────────────────────

@Composable
fun ConfigTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val lxbPort by viewModel.lxbPort.collectAsState()
    val tcpMockPort by viewModel.tcpMockPort.collectAsState()
    val serverIp by viewModel.serverIp.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // lxb-core 服务配置
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("lxb-core 服务", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = lxbPort,
                    onValueChange = { viewModel.lxbPort.value = it },
                    label = { Text("UDP 监听端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("lxb-core 在手机上监听的 UDP 端口，默认 12345") }
                )
            }
        }

        // 远端服务器配置
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("远端服务器", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = { viewModel.serverIp.value = it },
                    label = { Text("服务器 IP 地址") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("运行 web_console 的 PC 的 IP 地址") }
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { viewModel.serverPort.value = it },
                    label = { Text("服务器端口") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("web_console Flask 端口，默认 5000") }
                )
            }
        }

        OutlinedTextField(
            value = tcpMockPort,
            onValueChange = { viewModel.tcpMockPort.value = it },
            label = { Text("TCP Mock 端口") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            supportingText = { Text("用于 benchmark_comm 的 TCP mock 监听端口，默认 22345") }
        )

        Button(
            onClick = { viewModel.saveConfig() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存配置")
        }
    }
}

@Composable
fun LogsTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val logLines by viewModel.logLines.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        LogPanel(logLines = logLines, modifier = Modifier.fillMaxSize())
    }
}
