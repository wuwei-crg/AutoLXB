package com.example.lxb_ignition.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.example.lxb_ignition.R
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class TcpMockService : Service() {

    companion object {
        const val ACTION_START = "com.example.lxb_ignition.action.START_TCP_MOCK"
        const val ACTION_STOP = "com.example.lxb_ignition.action.STOP_TCP_MOCK"
        const val EXTRA_PORT = "port"

        private const val DEFAULT_PORT = 22345
        private const val NOTIF_CHANNEL_ID = "tcp_mock_channel"
        private const val NOTIF_CHANNEL_NAME = "TCP Mock Service"
        private const val NOTIF_ID = 32045

        // ~700KB payload for screenshot simulation.
        private const val SCREENSHOT_BYTES = 700 * 1024
    }

    private val running = AtomicBoolean(false)
    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> {
                val port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
                startForegroundCompat(port)
                startServer(port)
                return START_STICKY
            }
            else -> return START_STICKY
        }
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startForegroundCompat(port: Int) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            )
            mgr.createNotificationChannel(channel)
        }
        val notif: Notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("TCP Mock 已运行")
            .setContentText("监听端口: $port")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun startServer(port: Int) {
        if (running.get()) return
        running.set(true)
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(port)
                while (running.get()) {
                    val client = serverSocket?.accept() ?: break
                    Thread { handleClient(client) }.start()
                }
            } catch (_: Exception) {
                // service stays alive; stop action can restart cleanly
            } finally {
                running.set(false)
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }
                serverSocket = null
            }
        }.apply {
            name = "tcp-mock-server"
            start()
        }
    }

    private fun stopServer() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))
                while (running.get()) {
                    val line = reader.readLine() ?: break
                    val cmd = parseCmd(line)
                    when (cmd) {
                        "handshake" -> writeResp(writer, ok = true, payload = "pong".toByteArray(StandardCharsets.UTF_8), error = "")
                        "dump" -> {
                            val payload = "<hierarchy><node text='mock'/></hierarchy>".toByteArray(StandardCharsets.UTF_8)
                            writeResp(writer, ok = true, payload = payload, error = "")
                        }
                        "screenshot" -> {
                            val payload = ByteArray(SCREENSHOT_BYTES) { i -> ((i * 131) and 0xFF).toByte() }
                            writeResp(writer, ok = true, payload = payload, error = "")
                        }
                        else -> writeResp(writer, ok = false, payload = ByteArray(0), error = "unsupported_command")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun parseCmd(line: String): String? {
        // Minimal JSONL parse: {"cmd":"..."}.
        val keyPos = line.indexOf("\"cmd\"")
        if (keyPos < 0) return null
        val colon = line.indexOf(':', keyPos)
        if (colon < 0) return null
        val q1 = line.indexOf('"', colon + 1)
        if (q1 < 0) return null
        val q2 = line.indexOf('"', q1 + 1)
        if (q2 < 0) return null
        return line.substring(q1 + 1, q2)
    }

    private fun writeResp(writer: BufferedWriter, ok: Boolean, payload: ByteArray, error: String) {
        val b64 = if (payload.isNotEmpty()) Base64.encodeToString(payload, Base64.NO_WRAP) else ""
        val safeError = error.replace("\\", "\\\\").replace("\"", "\\\"")
        val json = "{\"ok\":${if (ok) "true" else "false"},\"payload_b64\":\"$b64\",\"error\":\"$safeError\"}"
        writer.write(json)
        writer.write("\n")
        writer.flush()
    }
}

