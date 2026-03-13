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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
    val tabs = listOf("Control", "Config", "Logs")

    Scaffold(
        topBar = { TopAppBar(title = { Text("LXB Ignition") }) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Text(label.take(1), fontSize = 18.sp) },
                        label = { Text(label) }
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

// Tab 1: Control

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
        // Shizuku / lxb-core status
        ShizukuStatusCard(state = state, message = statusMessage)

        // Start / stop lxb-core via Shizuku
        ServerControlRow(
            state = state,
            onRequestPermission = { viewModel.requestShizukuPermission() },
            onStart = { viewModel.startServer() },
            onStop = { viewModel.stopServer() }
        )

        // TCP mock helper
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("TCP Mock Service", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (tcpMockRunning) {
                        "Status: running (port $tcpMockPort)"
                    } else {
                        "Status: stopped"
                    },
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startTcpMockService() },
                        modifier = Modifier.weight(1f),
                        enabled = !tcpMockRunning
                    ) {
                        Text("Start TCP Mock")
                    }
                    OutlinedButton(
                        onClick = { viewModel.stopTcpMockService() },
                        modifier = Modifier.weight(1f),
                        enabled = tcpMockRunning
                    ) {
                        Text("Stop TCP Mock")
                    }
                }
            }
        }

        // Requirement input + PC / on-device execution
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Requirement", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = requirement,
                    onValueChange = { viewModel.requirement.value = it },
                    label = { Text("Enter user task / requirement") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.sendRequirement() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Send to PC web_console")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.runRequirementOnDevice() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Run FSM on device")
                    }
                }
                if (sendResult.isNotEmpty()) {
                    Text(
                        text = sendResult,
                        fontSize = 12.sp,
                        color = if (sendResult.startsWith("HTTP 2") || sendResult.contains("成功")) {
                            Color(0xFF4CAF50)
                        } else {
                            Color(0xFFFF9800)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ShizukuStatusCard(state: ShizukuManager.State, message: String) {
    val (bgColor, label) = when (state) {
        ShizukuManager.State.UNAVAILABLE -> Color(0xFF9E9E9E) to "Shizuku unavailable"
        ShizukuManager.State.PERMISSION_DENIED -> Color(0xFFFF9800) to "Permission required"
        ShizukuManager.State.READY -> Color(0xFF2196F3) to "Ready"
        ShizukuManager.State.STARTING -> Color(0xFF9C27B0) to "Starting..."
        ShizukuManager.State.RUNNING -> Color(0xFF4CAF50) to "Running"
        ShizukuManager.State.ERROR -> Color(0xFFF44336) to "Error"
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
                Text("Grant Shizuku")
            }
        }
        Button(
            onClick = onStart,
            enabled = state == ShizukuManager.State.READY || state == ShizukuManager.State.ERROR,
            modifier = Modifier.weight(1f)
        ) {
            Text("Start server")
        }
        OutlinedButton(
            onClick = onStop,
            enabled = state == ShizukuManager.State.RUNNING,
            modifier = Modifier.weight(1f)
        ) {
            Text("Stop server")
        }
    }
}

@Composable
fun LogPanel(logLines: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxSize()
        ) {
            Text("Logs", style = MaterialTheme.typography.titleSmall)
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

// Tab 2: Config

@Composable
fun ConfigTab(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val lxbPort by viewModel.lxbPort.collectAsState()
    val tcpMockPort by viewModel.tcpMockPort.collectAsState()
    val serverIp by viewModel.serverIp.collectAsState()
    val serverPort by viewModel.serverPort.collectAsState()
    val llmBaseUrl by viewModel.llmBaseUrl.collectAsState()
    val llmApiKey by viewModel.llmApiKey.collectAsState()
    val llmModel by viewModel.llmModel.collectAsState()
    val llmTestResult by viewModel.llmTestResult.collectAsState()

    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // lxb-core server
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("lxb-core server", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = lxbPort,
                    onValueChange = { viewModel.lxbPort.value = it },
                    label = { Text("UDP port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text("UDP port listened by lxb-core on device (default 12345)")
                    }
                )
            }
        }

        // PC web_console
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("PC web_console", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = { viewModel.serverIp.value = it },
                    label = { Text("Server IP") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("IP address of the PC running web_console") }
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = { viewModel.serverPort.value = it },
                    label = { Text("Server port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Flask port of web_console (default 5000)") }
                )
            }
        }

        // TCP mock
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("TCP mock", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = tcpMockPort,
                    onValueChange = { viewModel.tcpMockPort.value = it },
                    label = { Text("TCP mock port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("Port for benchmark_comm TCP mock (default 22345)") }
                )
            }
        }

        // LLM config (device-side direct call)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("LLM config (device-side)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = llmBaseUrl,
                    onValueChange = { viewModel.llmBaseUrl.value = it },
                    label = { Text("API Base URL") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = {
                        Text("e.g. https://api.openai.com/v1/chat/completions")
                    }
                )
                OutlinedTextField(
                    value = llmApiKey,
                    onValueChange = { viewModel.llmApiKey.value = it },
                    label = { Text("API Key") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = llmModel,
                    onValueChange = { viewModel.llmModel.value = it },
                    label = { Text("Model") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    supportingText = { Text("e.g. gpt-4o-mini, qwen-plus") }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.testLlmAndSyncConfig() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Test LLM & sync to device")
                    }
                    OutlinedButton(
                        onClick = { viewModel.saveConfig() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save only")
                    }
                }
                if (llmTestResult.isNotEmpty()) {
                    Text(
                        text = llmTestResult,
                        fontSize = 12.sp,
                        color = if (llmTestResult.startsWith("LLM ")) {
                            Color(0xFF4CAF50)
                        } else {
                            Color(0xFFFF9800)
                        }
                    )
                }
            }
        }
    }
}

// Tab 3: Logs

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

