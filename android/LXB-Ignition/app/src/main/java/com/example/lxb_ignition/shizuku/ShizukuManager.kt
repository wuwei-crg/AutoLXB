package com.example.lxb_ignition.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.example.lxb_ignition.BuildConfig
import com.example.lxb_ignition.IShizukuService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Shizuku 管理器（API 13 UserService 版）
 *
 * 通过 Shizuku.bindUserService() 在 shell 进程中运行 ShizukuServiceImpl，
 * 再通过 AIDL 调用来部署/启动/停止 lxb-core。
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        private const val ASSET_JAR = "lxb-core-dex.jar"
        private const val TMP_JAR = "/data/local/tmp/lxb-core.jar"
        private const val SERVER_CLASS = "com.lxb.server.Main"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val LOG_POLL_INTERVAL_MS = 2000L
    }

    enum class State {
        UNAVAILABLE,        // Shizuku binder 未收到
        PERMISSION_DENIED,  // 权限未授予
        READY,              // UserService 已绑定，可启动
        STARTING,           // 部署+启动中
        RUNNING,            // 服务进程存活
        ERROR
    }

    interface Listener {
        fun onStateChanged(state: State, message: String)
        fun onLogLine(line: String)
    }

    @Volatile
    var currentState: State = State.UNAVAILABLE
        private set

    private var listener: Listener? = null
    private var service: IShizukuService? = null
    private var isBound = false
    private var logBytesRead = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logPollJob: Job? = null

    fun setListener(l: Listener) { listener = l }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        listener?.onLogLine(msg)
    }

    private fun setState(state: State, msg: String) {
        currentState = state
        listener?.onStateChanged(state, msg)
    }

    // ─── Shizuku 生命周期 ─────────────────────────────────────────────

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuServiceImpl::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IShizukuService.Stub.asInterface(binder)
            isBound = true
            log("Shizuku UserService 已连接")
            if (currentState != State.RUNNING && currentState != State.STARTING) {
                setState(State.READY, "Shizuku 就绪，可以启动服务")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            log("Shizuku UserService 已断开")
            if (currentState == State.RUNNING) {
                setState(State.ERROR, "UserService 意外断开，请重新启动")
            }
        }
    }

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        log("Shizuku binder 已接收")
        refreshState()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        log("Shizuku binder 已断开")
        service = null
        isBound = false
        logPollJob?.cancel()
        setState(State.UNAVAILABLE, "Shizuku 已停止")
    }

    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            log("Shizuku 权限已授予")
            bindServiceIfNeeded()
        } else {
            setState(State.PERMISSION_DENIED, "Shizuku 权限被拒绝")
        }
    }

    /** 在 ViewModel.init 中调用 */
    fun attach() {
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        refreshState()
    }

    /** 在 ViewModel.onCleared 中调用 */
    fun detach() {
        logPollJob?.cancel()
        scope.cancel()
        if (isBound) {
            runCatching { Shizuku.unbindUserService(userServiceArgs, serviceConnection, false) }
            isBound = false
        }
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermissionResult)
    }

    fun refreshState() {
        when {
            !Shizuku.pingBinder() -> {
                setState(State.UNAVAILABLE, "Shizuku 未运行，请先在 Shizuku App 中启动服务")
            }
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> {
                setState(State.PERMISSION_DENIED, "请点击「授权」允许本 App 使用 Shizuku")
            }
            else -> {
                bindServiceIfNeeded()
            }
        }
    }

    fun requestPermission() {
        if (Shizuku.pingBinder()) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    /**
     * Write a small config file to a shell-accessible path using the Shizuku
     * user service (reuses deployJar under the hood).
     */
    suspend fun writeConfigFile(destPath: String, content: ByteArray): Result<Unit> =
        withContext(Dispatchers.IO) {
            val svc = service ?: return@withContext Result.failure(
                Exception("Shizuku UserService 未连接，无法写入配置")
            )
            return@withContext try {
                val ok = svc.deployJar(content, destPath)
                if (ok) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("deployJar 返回 false"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun bindServiceIfNeeded() {
        if (!isBound) {
            log("绑定 Shizuku UserService...")
            Shizuku.bindUserService(userServiceArgs, serviceConnection)
        } else if (currentState != State.RUNNING && currentState != State.STARTING) {
            setState(State.READY, "Shizuku 就绪")
        }
    }

    // ─── 服务管理 ────────────────────────────────────────────────────

    suspend fun startServer(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val svc = service ?: return@withContext Result.failure(
            Exception("Shizuku UserService 未连接，请稍候重试")
        )
        try {
            setState(State.STARTING, "正在读取 JAR...")
            val jarBytes = readAssetBytes(ASSET_JAR)
            log("JAR 已从 assets 读取 (${jarBytes.size} bytes)")

            setState(State.STARTING, "部署 JAR 到 $TMP_JAR...")
            val deployed = svc.deployJar(jarBytes, TMP_JAR)
            if (!deployed) {
                val msg = "JAR 写入 $TMP_JAR 失败"
                setState(State.ERROR, msg)
                return@withContext Result.failure(Exception(msg))
            }
            log("JAR 已写入 $TMP_JAR")

            setState(State.STARTING, "启动 lxb-core (UDP :$port)...")
            val result = svc.startServer(TMP_JAR, SERVER_CLASS, port)

            if (result.startsWith("OK")) {
                logBytesRead = 0L
                setState(State.RUNNING, "服务运行中 (UDP :$port)")
                result.removePrefix("OK\n").lines().filter { it.isNotEmpty() }.forEach { log(it) }
                startLogPolling(svc)
                Result.success(Unit)
            } else {
                val body = result.removePrefix("ERROR\n")
                // StatusCard 只显示第一行摘要，完整日志逐行写入 LogPanel
                val summary = body.lines().firstOrNull { it.isNotEmpty() } ?: "启动失败"
                setState(State.ERROR, summary)
                body.lines().filter { it.isNotEmpty() }.forEach { log(it) }
                Result.failure(Exception(summary))
            }
        } catch (e: Exception) {
            val msg = "启动失败: ${e.message}"
            log(msg)
            setState(State.ERROR, msg)
            Result.failure(e)
        }
    }

    suspend fun stopServer(): Result<Unit> = withContext(Dispatchers.IO) {
        logPollJob?.cancel()
        try {
            service?.stopServer(SERVER_CLASS)
            setState(State.READY, "服务已停止")
            Result.success(Unit)
        } catch (e: Exception) {
            val msg = "停止失败: ${e.message}"
            log(msg)
            Result.failure(e)
        }
    }

    suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        runCatching { service?.isRunning(SERVER_CLASS) ?: false }.getOrDefault(false)
    }

    // ─── 日志轮询 ────────────────────────────────────────────────────

    private fun startLogPolling(svc: IShizukuService) {
        logPollJob?.cancel()
        logPollJob = scope.launch {
            while (isActive) {
                delay(LOG_POLL_INTERVAL_MS)
                try {
                    val chunk = svc.readLogPart(logBytesRead, 4096)
                    if (chunk.isNotEmpty()) {
                        logBytesRead += chunk.toByteArray(Charsets.UTF_8).size
                        chunk.lines().filter { it.isNotEmpty() }.forEach { line ->
                            listener?.onLogLine(line)
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    // ─── 私有辅助 ────────────────────────────────────────────────────

    private fun readAssetBytes(name: String): ByteArray {
        return try {
            context.assets.open(name).use { it.readBytes() }
        } catch (e: Exception) {
            throw Exception("无法从 assets 读取 $name，请先运行 ./gradlew :lxb-core:buildDex: ${e.message}")
        }
    }
}
