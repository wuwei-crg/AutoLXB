package com.example.lxb_ignition

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lxb_ignition.service.LocalLinkClient
import com.example.lxb_ignition.service.TcpMockService
import com.example.lxb_ignition.shizuku.ShizukuManager
import com.lxb.server.protocol.CommandIds
import com.lxb.server.cortex.LlmClient
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
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "lxb_config"
        private const val KEY_LXB_PORT = "lxb_port"
        private const val KEY_TCP_MOCK_PORT = "tcp_mock_port"
        private const val KEY_SERVER_IP = "server_ip"
        private const val KEY_SERVER_PORT = "server_port"

        private const val KEY_LLM_BASE_URL = "llm_base_url"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_LLM_MODEL = "llm_model"

        // Keep in sync with LlmConfig.DEFAULT_CONFIG_PATH in lxb-core
        private const val LLM_CONFIG_PATH = "/data/local/tmp/lxb-llm-config.json"
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val shizukuManager = ShizukuManager(application)

    // 状态
    private val _state = MutableStateFlow(ShizukuManager.State.UNAVAILABLE)
    val state: StateFlow<ShizukuManager.State> = _state.asStateFlow()

    private val _statusMessage = MutableStateFlow("初始化中...")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    // 配置：lxb-core 服务 / TCP mock
    val lxbPort = MutableStateFlow(prefs.getString(KEY_LXB_PORT, "12345") ?: "12345")
    val tcpMockPort = MutableStateFlow(prefs.getString(KEY_TCP_MOCK_PORT, "22345") ?: "22345")
    val tcpMockRunning = MutableStateFlow(false)

    // 配置：PC 端 web_console
    val serverIp = MutableStateFlow(prefs.getString(KEY_SERVER_IP, "") ?: "")
    val serverPort = MutableStateFlow(prefs.getString(KEY_SERVER_PORT, "5000") ?: "5000")

    // 配置：LLM（端侧直连云端）
    val llmBaseUrl = MutableStateFlow(prefs.getString(KEY_LLM_BASE_URL, "") ?: "")
    val llmApiKey = MutableStateFlow(prefs.getString(KEY_LLM_API_KEY, "") ?: "")
    val llmModel = MutableStateFlow(prefs.getString(KEY_LLM_MODEL, "gpt-4o-mini") ?: "gpt-4o-mini")
    val llmTestResult = MutableStateFlow("")

    // 控制 Tab
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

    // 操作：Shizuku / lxb-core

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

    // 控制 Tab: 把 requirement 发到 PC 端 web_console

    fun sendRequirement() {
        val req = requirement.value.trim()
        if (req.isEmpty()) {
            sendResult.value = "请先输入需求内容"
            return
        }
        val ip = serverIp.value.trim()
        val port = serverPort.value.trim()
        if (ip.isEmpty()) {
            sendResult.value = "请先在【配置】中填写 web_console 所在 PC 的 IP"
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
                    httpClient.newCall(request).execute().use { resp ->
                        "HTTP ${resp.code}: ${resp.body?.string()?.take(200) ?: "(无响应体)"}"
                    }
                }.getOrElse { e -> "发送失败: ${e.message}" }
            }
            sendResult.value = result
            appendLog("[REQ] $result")
        }
    }

    /**
     * 端侧直连 lxb-core，运行 Cortex FSM。
     *
     * 步骤：
     *  1) 检查 lxb-core 是否已运行（Shizuku）。
     *  2) 通过本机 UDP 向 127.0.0.1:lxbPort 发送 CMD_CORTEX_FSM_RUN。
     *  3) 将简要结果显示在 sendResult，并写入日志。
     */
    fun runRequirementOnDevice() {
        val req = requirement.value.trim()
        if (req.isEmpty()) {
            sendResult.value = "请先输入需求内容"
            return
        }
        val port = lxbPort.value.toIntOrNull() ?: run {
            sendResult.value = "lxb-core 端口无效"
            return
        }
        saveConfig()

        viewModelScope.launch {
            sendResult.value = "端侧 FSM 运行中..."

            val msg = withContext(Dispatchers.IO) {
                val running = runCatching { shizukuManager.isServerRunning() }.getOrDefault(false)
                if (!running) {
                    return@withContext "lxb-core 未运行，请先在上方启动服务"
                }

                return@withContext runCatching {
                    LocalLinkClient("127.0.0.1", port).use { client ->
                        // 可选握手，仅做连通性检查（失败时不中断）
                        runCatching { client.handshake() }

                        val json = org.json.JSONObject()
                            .put("user_task", req)
                            .toString()
                        val payload = json.toByteArray(Charsets.UTF_8)

                        val respBytes = client.sendCommand(
                            CommandIds.CMD_CORTEX_FSM_RUN,
                            payload,
                            timeoutMs = 20_000
                        )
                        val text = respBytes.toString(Charsets.UTF_8)

                        val obj = runCatching { org.json.JSONObject(text) }.getOrNull()
                        if (obj == null) {
                            "FSM 返回非 JSON 响应: ${text.take(160)}"
                        } else {
                            val status = obj.optString("status", "")
                            val state = obj.optString("state", "")
                            val pkg = obj.optString("package_name", "")
                            val target = obj.optString("target_page", "")
                            val reason = obj.optString("reason", "")
                            if (status == "success") {
                                "端侧 FSM 成功: package=${pkg.ifEmpty { "<auto>" }}, target_page=${target.ifEmpty { "<unknown>" }}, state=${if (state.isEmpty()) "FINISH" else state}"
                            } else {
                                "端侧 FSM 失败: state=${if (state.isEmpty()) "UNKNOWN" else state}, reason=${reason.ifEmpty { "unknown" }}"
                            }
                        }
                    }
                }.getOrElse { e ->
                    "端侧 FSM 调用失败: ${e.message ?: e.toString()}"
                }
            }

            sendResult.value = msg
            appendLog("[FSM] $msg")
        }
    }

    /**
     * 写入端侧 LLM 配置并直接测试云端 LLM 是否可用。
     */
    fun testLlmAndSyncConfig() {
        val baseUrl = llmBaseUrl.value.trim()
        val model = llmModel.value.trim()
        if (baseUrl.isEmpty() || model.isEmpty()) {
            llmTestResult.value = "请先填写 LLM 的 API Base URL 和 Model"
            return
        }

        saveConfig()

        viewModelScope.launch {
            llmTestResult.value = "测试中..."

            // 1) 写入端侧配置文件（shell 可读）
            val cfgJson = org.json.JSONObject()
                .put("api_base_url", baseUrl)
                .put("api_key", llmApiKey.value)
                .put("model", model)
                .toString()
            val cfgBytes = cfgJson.toByteArray(Charset.forName("UTF-8"))

            val syncResult = withContext(Dispatchers.IO) {
                shizukuManager.writeConfigFile(LLM_CONFIG_PATH, cfgBytes)
            }
            if (syncResult.isFailure) {
                val msg = "写入端侧配置失败: ${syncResult.exceptionOrNull()?.message}"
                llmTestResult.value = msg
                appendLog("[LLM] $msg")
                return@launch
            }

            // 2) 直接从 APK 侧调用云端 LLM，验证配置可用
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = org.json.JSONObject()
                        .put("model", model)
                        .put("max_tokens", 16)
                        .put(
                            "messages",
                            org.json.JSONArray().put(
                                org.json.JSONObject()
                                    .put("role", "user")
                                    .put("content", "请仅回复 ok")
                            )
                        )
                        .toString()
                    val body = json.toRequestBody("application/json".toMediaType())
                    val endpoint = LlmClient.buildEndpointUrl(baseUrl)
                    val builder = Request.Builder().url(endpoint).post(body)
                    val key = llmApiKey.value.trim()
                    if (key.isNotEmpty()) {
                        builder.addHeader("Authorization", "Bearer $key")
                    }
                    val request = builder.build()
                    httpClient.newCall(request).execute().use { resp ->
                        val code = resp.code
                        val text = resp.body?.string() ?: ""
                        if (code !in 200..299) {
                            "HTTP $code: ${text.take(200)}"
                        } else {
                            val ok = text.contains("ok", ignoreCase = true)
                            if (ok) {
                                "LLM 调用成功: 收到包含 ok 的响应 (HTTP $code)"
                            } else {
                                "LLM 调用成功 (HTTP $code)，但响应未明显包含 ok: ${text.take(120)}"
                            }
                        }
                    }
                }.getOrElse { e -> "LLM 调用失败: ${e.message}" }
            }

            llmTestResult.value = result
            appendLog("[LLM] $result")
        }
    }

    fun saveConfig() {
        prefs.edit()
            .putString(KEY_LXB_PORT, lxbPort.value)
            .putString(KEY_TCP_MOCK_PORT, tcpMockPort.value)
            .putString(KEY_SERVER_IP, serverIp.value)
            .putString(KEY_SERVER_PORT, serverPort.value)
            .putString(KEY_LLM_BASE_URL, llmBaseUrl.value)
            .putString(KEY_LLM_API_KEY, llmApiKey.value)
            .putString(KEY_LLM_MODEL, llmModel.value)
            .apply()
    }

    private fun appendLog(line: String) {
        val current = _logLines.value.toMutableList()
        current.add(line)
        if (current.size > 500) {
            current.subList(0, current.size - 500).clear()
        }
        _logLines.value = current
    }

    override fun onCleared() {
        super.onCleared()
        shizukuManager.detach()
        httpClient.dispatcher.executorService.shutdown()
    }
}
