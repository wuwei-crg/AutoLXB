package com.example.lxb_ignition.logging

import android.content.Context
import com.example.lxb_ignition.model.UnifiedLogEntry
import com.example.lxb_ignition.storage.AppStatePaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AppLogStore {
    const val MAX_ENTRIES = 1000

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val persistLock = Any()
    private val nextSeq = AtomicLong(1L)
    private val persistGeneration = AtomicLong(0L)
    private var loaded = false

    private val _entries = MutableStateFlow<List<UnifiedLogEntry>>(emptyList())
    val entries: StateFlow<List<UnifiedLogEntry>> = _entries.asStateFlow()

    fun load(context: Context): List<UnifiedLogEntry> {
        val appContext = context.applicationContext
        synchronized(lock) {
            if (loaded) return _entries.value
            val rows: List<UnifiedLogEntry> = runCatching {
                val file = AppStatePaths.getAppLogFile(appContext)
                if (!file.exists() || !file.isFile) {
                    emptyList<UnifiedLogEntry>()
                } else {
                    file.readLines(Charsets.UTF_8)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull(::parseEntry)
                        .takeLast(MAX_ENTRIES)
                }
            }.getOrDefault(emptyList())
            val maxSeq = rows.maxOfOrNull { it.seq } ?: 0L
            nextSeq.set(maxSeq + 1L)
            _entries.value = rows
            loaded = true
            return rows
        }
    }

    fun write(
        context: Context,
        level: String = "info",
        logger: String,
        message: String,
        attrs: Map<String, Any?> = emptyMap()
    ): UnifiedLogEntry {
        val appContext = context.applicationContext
        val cleanAttrs = sanitizeAttrs(attrs)
        val entry = UnifiedLogEntry(
            source = "app",
            seq = nextSeq.getAndIncrement(),
            timestamp = nowTimestamp(),
            level = normalizeLevel(level),
            logger = logger.ifBlank { "App" },
            message = redact(message),
            attrs = cleanAttrs
        )
        val snapshot: List<UnifiedLogEntry>
        synchronized(lock) {
            if (!loaded) load(appContext)
            val current = _entries.value.toMutableList()
            current.add(entry)
            if (current.size > MAX_ENTRIES) {
                current.subList(0, current.size - MAX_ENTRIES).clear()
            }
            snapshot = current.toList()
            _entries.value = snapshot
        }
        persistAsync(appContext, snapshot, persistGeneration.incrementAndGet())
        return entry
    }

    fun toJsonLine(entry: UnifiedLogEntry): String {
        val obj = JSONObject()
            .put("seq", entry.seq)
            .put("ts", entry.timestamp)
            .put("level", normalizeLevel(entry.level))
            .put("logger", entry.logger)
            .put("message", redact(entry.message))
        if (entry.source.isNotBlank()) {
            obj.put("source", entry.source)
        }
        val attrs = JSONObject()
        entry.attrs.forEach { (key, value) ->
            attrs.put(key, redactAttr(key, value))
        }
        if (attrs.length() > 0) {
            obj.put("attrs", attrs)
        }
        return obj.toString()
    }

    fun redact(text: String): String {
        var out = text
        out = JSON_STRING_SECRET_REGEX.replace(out) { match ->
            val key = match.groupValues[2]
            if (isSensitiveKey(key)) {
                "${match.groupValues[1]}$key${match.groupValues[1]}${match.groupValues[3]}****${match.groupValues[5]}"
            } else {
                match.value
            }
        }
        out = JSON_NUMBER_SECRET_REGEX.replace(out) { match ->
            val key = match.groupValues[2]
            if (isSensitiveKey(key)) {
                "${match.groupValues[1]}$key${match.groupValues[1]}${match.groupValues[3]}****"
            } else {
                match.value
            }
        }
        out = API_KEY_REGEX.replace(out) { "${it.groupValues[1]}****" }
        out = AUTHORIZATION_REGEX.replace(out) { "${it.groupValues[1]}****" }
        out = BEARER_REGEX.replace(out, "Bearer ****")
        out = PIN_REGEX.replace(out) { "${it.groupValues[1]}****" }
        out = PAIRING_CODE_REGEX.replace(out) { "${it.groupValues[1]}****" }
        return out
    }

    fun redactValue(key: String, value: String): String {
        return redactAttr(key, value)
    }

    fun normalizeLevel(level: String): String {
        return when (level.trim().lowercase(Locale.US)) {
            "debug" -> "debug"
            "warn", "warning" -> "warn"
            "error", "err", "fatal" -> "error"
            else -> "info"
        }
    }

    fun inferLevel(message: String): String {
        val lower = message.lowercase(Locale.US)
        return when {
            lower.contains("failed") || lower.contains("failure") || lower.contains("error") ||
                lower.contains("invalid") || lower.contains("exception") -> "error"
            lower.contains("warn") || lower.contains("retry") || lower.contains("unavailable") ||
                lower.contains("not reachable") || lower.contains("not ready") -> "warn"
            else -> "info"
        }
    }

    private fun persistAsync(context: Context, snapshot: List<UnifiedLogEntry>, generation: Long) {
        val appContext = context.applicationContext
        ioScope.launch {
            runCatching {
                synchronized(persistLock) {
                    if (generation < persistGeneration.get()) return@runCatching
                    val file = AppStatePaths.getAppLogFile(appContext)
                    file.parentFile?.mkdirs()
                    file.bufferedWriter(Charsets.UTF_8).use { writer ->
                        snapshot.forEach { entry ->
                            writer.append(toJsonLine(entry))
                            writer.append('\n')
                        }
                    }
                }
            }
        }
    }

    private fun parseEntry(line: String): UnifiedLogEntry? {
        val obj = runCatching { JSONObject(line) }.getOrNull() ?: return null
        val attrsObj = obj.optJSONObject("attrs")
        val attrs = LinkedHashMap<String, String>()
        if (attrsObj != null) {
            val keys = attrsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                attrs[key] = redactAttr(key, attrsObj.optString(key, ""))
            }
        }
        return UnifiedLogEntry(
            source = obj.optString("source", "app"),
            seq = obj.optLong("seq", 0L).let { if (it > 0L) it else nextSeq.getAndIncrement() },
            timestamp = obj.optString("ts", ""),
            level = normalizeLevel(obj.optString("level", "info")),
            logger = obj.optString("logger", "App"),
            message = redact(obj.optString("message", "")),
            attrs = attrs,
            rawLine = line
        )
    }

    private fun sanitizeAttrs(attrs: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        attrs.forEach { (key, rawValue) ->
            if (key.isBlank() || rawValue == null) return@forEach
            out[key] = redactAttr(key, rawValue.toString())
        }
        return out
    }

    private fun redactAttr(key: String, value: String): String {
        return if (isSensitiveKey(key) && value.isNotBlank()) "****" else redact(value)
    }

    private fun isSensitiveKey(key: String): Boolean {
        val lower = key.lowercase(Locale.US)
        val normalized = lower.replace(Regex("[^a-z0-9]"), "")
        val lengthLike = normalized.endsWith("length") ||
            normalized.endsWith("len") ||
            normalized.endsWith("count")
        return normalized.contains("apikey") ||
            normalized.contains("authorization") ||
            normalized.contains("token") ||
            (!lengthLike && (
                normalized == "pin" ||
                    normalized.endsWith("pin") ||
                    normalized.contains("pincode") ||
                    normalized.contains("unlockpin") ||
                    normalized.contains("paircode") ||
                    normalized.contains("pairingcode")
                ))
    }

    private fun nowTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
    }

    private val API_KEY_REGEX = Regex(
        pattern = """(?i)(\bapi[-_ ]?key\s*[:=]\s*)[^\s,;&"']+"""
    )
    private val JSON_STRING_SECRET_REGEX = Regex(
        pattern = """(?i)(["'])([^"']*(?:api[-_ ]?key|apikey|authorization|token|pin|pair(?:ing)?[-_ ]?code)[^"']*)\1(\s*:\s*["'])([^"']*)(["'])"""
    )
    private val JSON_NUMBER_SECRET_REGEX = Regex(
        pattern = """(?i)(["'])([^"']*(?:api[-_ ]?key|apikey|authorization|token|pin|pair(?:ing)?[-_ ]?code)[^"']*)\1(\s*:\s*)\d+"""
    )
    private val AUTHORIZATION_REGEX = Regex(
        pattern = """(?i)(\bauthorization\s*[:=]\s*)[^,;&"'\r\n]+"""
    )
    private val BEARER_REGEX = Regex(
        pattern = """(?i)\bBearer\s+[^\s,;&"']+"""
    )
    private val PIN_REGEX = Regex(
        pattern = """(?i)(\b(?:unlock[-_ ]*)?pin\s*[:=]\s*)\d+"""
    )
    private val PAIRING_CODE_REGEX = Regex(
        pattern = """(?i)(\bpair(?:ing)?[-_ ]?code\s*[:=]\s*)\d+"""
    )
}
