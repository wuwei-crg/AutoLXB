package com.example.lxb_ignition

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lxb_ignition.service.TcpMockService
import com.example.lxb_ignition.shizuku.ShizukuManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val KEY_TCP_MOCK_PORT = "tcp_mock_port"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val shizukuManager = ShizukuManager(application)

    // ─── 状态 ──────────────────────────────────────────────────────────

    private val _state = MutableStateFlow(ShizukuManager.State.UNAVAILABLE)
    val state: StateFlow<ShizukuManager.State> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("初始化中...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    // ─── 配置：lxb-core 服务 ────────────────────────────────────────────

    val lxbPort = MutableStateFlow(prefs.getString(KEY_LXB_PORT, "12345") ?: "12345")
    val tcpMockPort = MutableStateFlow(prefs.getString(KEY_TCP_MOCK_PORT, "22345") ?: "22345")
    val tcpMockRunning = MutableStateFlow(false)

    // ─── 配置：远端服务器 ────────────────────────────────────────────────

    val serverIp = MutableStateFlow(prefs.getString(KEY_SERVER_IP, "") ?: "")
    val serverPort = MutableStateFlow(prefs.getString(KEY_SERVER_PORT, "5000") ?: "5000")

    // ─── 控制 Tab ───────────────────────────────────────────────────────

    val requirement = MutableStateFlow("")
    val sendResult = MutableStateFlow("")

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    init {
        shizukuManager.setListener(object : ShizukuManager.Listener {
            override fun onStateChanged(state: ShizukuManager.State, message: String) {
                _state.value = state
                _statusMessage.value = message
            }

            override fun onLogLine(line: String) {
                appendLog(line)
            }
        })
        shizukuManager.attach()
    }

    // ─── 操作 ──────────────────────────────────────────────────────────

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun startServer() {
        val port = lxbPort.value.toIntOrNull() ?: run {
            appendLog("错误：lxb-core 端口无效")
            return
        }
        saveConfig()
        viewModelScope.launch {
            shizukuManager.startServer(port)
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            shizukuManager.stopServer()
        }
    }

    fun startTcpMockService() {
        val port = tcpMockPort.value.toIntOrNull() ?: run {
            appendLog("错误：TCP mock 端口无效")
            return
        }
        saveConfig()
        val app = getApplication<Application>()
        val intent = Intent(app, TcpMockService::class.java).apply {
            action = TcpMockService.ACTION_START
            putExtra(TcpMockService.EXTRA_PORT, port)
        }
        app.startForegroundService(intent)
        tcpMockRunning.value = true
        appendLog("[TCP-MOCK] 已启动，端口=$port")
    }

    fun stopTcpMockService() {
        val app = getApplication<Application>()
        val intent = Intent(app, TcpMockService::class.java).apply {
            action = TcpMockService.ACTION_STOP
        }
        app.startService(intent)
        tcpMockRunning.value = false
        appendLog("[TCP-MOCK] 已停止")
    }

    fun sendRequirement() {
        val req = requirement.value.trim()
        if (req.isEmpty()) {
            sendResult.value = "请先输入需求内容"
            return
        }
        val ip = serverIp.value.trim()
        val port = serverPort.value.trim()
        if (ip.isEmpty()) {
            sendResult.value = "请先在「配置」中填写远端服务器 IP"
            return
        }
        saveConfig()
        viewModelScope.launch {
            sendResult.value = "发送中..."
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val url = "http://$ip:$port/api/cortex/fsm/start"
                    val json = org.json.JSONObject()
                        .put("user_task", req)
                        .put("lxb_port", lxbPort.value.toIntOrNull() ?: 12345)
                        .toString()
                    val body = json.toRequestBody("application/json".toMediaType())
                    val request = Request.Builder().url(url).post(body).build()
                    val response = httpClient.newCall(request).execute()
                    "HTTP ${response.code}: ${response.body?.string()?.take(200) ?: "(无响应体)"}"
                }.getOrElse { e -> "发送失败: ${e.message}" }
            }
            sendResult.value = result
            appendLog("需求发送结果: $result")
        }
    }

    fun saveConfig() {
        prefs.edit()
            .putString(KEY_LXB_PORT, lxbPort.value)
            .putString(KEY_TCP_MOCK_PORT, tcpMockPort.value)
            .putString(KEY_SERVER_IP, serverIp.value)
            .putString(KEY_SERVER_PORT, serverPort.value)
            .apply()
    }

    private fun appendLog(line: String) {
        val current = _logLines.value.toMutableList()
        current.add(line)
        if (current.size > 500) current.subList(0, current.size - 500).clear()
        _logLines.value = current
    }

    override fun onCleared() {
        super.onCleared()
        shizukuManager.detach()
        httpClient.dispatcher.executorService.shutdown()
    }
}
