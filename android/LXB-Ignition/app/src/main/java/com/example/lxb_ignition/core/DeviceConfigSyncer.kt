package com.example.lxb_ignition.core

import android.app.Application
import android.util.Base64
import com.example.lxb_ignition.service.CoreClientGateway
import com.example.lxb_ignition.storage.AppStatePaths
import com.lxb.server.protocol.CommandIds
import java.io.File

data class DeviceLlmProviderSettings(
    val providerId: String,
    val name: String,
    val apiBaseUrl: String,
    val apiKey: String,
    val model: String,
    val requestType: String,
    val updatedAt: Long
)

data class DeviceLlmSettings(
    val apiBaseUrl: String,
    val apiKey: String,
    val model: String,
    val requestType: String,
    val autoUnlockBeforeRoute: Boolean,
    val autoLockAfterTask: Boolean,
    val unlockPin: String,
    val useMap: Boolean,
    val mapSource: String,
    val taskDndMode: String,
    val maxTaskSteps: Int,
    val providers: List<DeviceLlmProviderSettings> = emptyList(),
    val activeProviderId: String = "",
    val routingMode: String = "unified",
    val unifiedProviderId: String = "",
    val semanticLocatorProviderId: String = "",
    val visionActProviderId: String = ""
)

class DeviceConfigSyncer(
    private val app: Application,
    private val coreClientGateway: CoreClientGateway,
    private val defaultConfigPath: String
) {
    fun buildConfigJson(settings: DeviceLlmSettings): String {
        return buildConfigJsonObject(settings).toString()
    }

    companion object {
        fun buildConfigJsonObject(settings: DeviceLlmSettings): org.json.JSONObject {
            val providers = org.json.JSONArray()
            settings.providers.forEach { provider ->
                val id = provider.providerId.trim()
                if (id.isNotEmpty()) {
                    providers.put(
                        org.json.JSONObject()
                            .put("provider_id", id)
                            .put("name", provider.name.trim())
                            .put("api_base_url", provider.apiBaseUrl.trim())
                            .put("api_key", provider.apiKey)
                            .put("model", provider.model.trim())
                            .put("request_type", provider.requestType.trim())
                            .put("updated_at", provider.updatedAt)
                    )
                }
            }
            val routingMode = if (settings.routingMode.trim() == "split") "split" else "unified"
            val routing = org.json.JSONObject()
                .put("mode", routingMode)
                .put(
                    "script_action",
                    org.json.JSONObject()
                        .put("unified_provider_id", settings.unifiedProviderId.trim())
                        .put("semantic_locator_provider_id", settings.semanticLocatorProviderId.trim())
                        .put("vision_act_provider_id", settings.visionActProviderId.trim())
                )
            return org.json.JSONObject()
                .put("api_base_url", settings.apiBaseUrl.trim())
                .put("api_key", settings.apiKey)
                .put("model", settings.model.trim())
                .put("request_type", settings.requestType.trim())
                .put("providers", providers)
                .put("active_provider_id", settings.activeProviderId.trim())
                .put("model_routing", routing)
                .put("auto_unlock_before_route", settings.autoUnlockBeforeRoute)
                .put("auto_lock_after_task", settings.autoLockAfterTask)
                .put("unlock_pin", settings.unlockPin.trim())
                .put("use_map", settings.useMap)
                .put("map_source", settings.mapSource)
                .put("task_dnd_mode", settings.taskDndMode)
                .put("max_task_steps", settings.maxTaskSteps)
        }
    }

    suspend fun sync(settings: DeviceLlmSettings, port: Int?): Result<String> {
        val cfgBytes = buildConfigJson(settings).toByteArray(Charsets.UTF_8)
        val appConfigPath = AppStatePaths.getLlmConfigPath(app)
        return runCatching {
            val appCfg = File(appConfigPath)
            appCfg.parentFile?.mkdirs()
            appCfg.writeBytes(cfgBytes)

            if (port != null && coreClientGateway.probeHandshakeReady(port, 1200)) {
                val shellSync = writeViaCoreShell(port, cfgBytes)
                if (shellSync.isFailure) {
                    throw shellSync.exceptionOrNull() ?: Exception("core shell sync failed")
                }
                val shellDetail = shellSync.getOrNull().orEmpty()
                return@runCatching "app=$appConfigPath; core=$defaultConfigPath; $shellDetail"
            }
            "app=$appConfigPath; core_offline_skip=true"
        }
    }

    private fun writeViaCoreShell(port: Int, cfgBytes: ByteArray): Result<String> {
        return runCatching {
            val b64 = Base64.encodeToString(cfgBytes, Base64.NO_WRAP)
            val tmpB64 = "$defaultConfigPath.b64"
            val cmd = buildString {
                append("mkdir -p /data/local/tmp; ")
                append("echo '").append(b64).append("' > ").append(tmpB64).append("; ")
                append("(base64 -d ").append(tmpB64).append(" > ").append(defaultConfigPath)
                append(" || toybox base64 -d ").append(tmpB64).append(" > ").append(defaultConfigPath).append("); ")
                append("rm -f ").append(tmpB64).append("; ")
                append("ls -l ").append(defaultConfigPath)
            }

            val req = org.json.JSONObject()
                .put("action", "shell_exec")
                .put("command", cmd)
                .put("timeout_ms", 6000)
                .toString()
                .toByteArray(Charsets.UTF_8)

            coreClientGateway.withClient(
                port = port,
                connectTimeoutMs = 4000,
                handshakeTimeoutMs = 2000,
                tolerateHandshakeFailure = true
            ) { client ->
                val payload = client.sendCommand(
                    CommandIds.CMD_SYSTEM_CONTROL,
                    req,
                    timeoutMs = 8_000
                )
                val parsed = CoreApiParser.parseSystemControl(payload)
                if (!parsed.ok) {
                    throw IllegalStateException(parsed.detail)
                }
                parsed.detail
            }
        }
    }
}
