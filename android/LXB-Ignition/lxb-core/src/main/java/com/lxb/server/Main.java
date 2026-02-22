package com.lxb.server;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.daemon.SequenceTracker;
import com.lxb.server.dispatcher.CommandDispatcher;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.network.UdpServer;
import com.lxb.server.perception.PerceptionEngine;
// import com.lxb.server.popup.PopupDetector;  // 暂时禁用，改用 Python 端 VLM 检测
import com.lxb.server.protocol.FrameCodec;
import com.lxb.server.system.HiddenApiBypass;
import com.lxb.server.system.UiAutomationWrapper;

import java.net.SocketTimeoutException;

/**
 * LXB Server 主入口
 *
 * 启动方式 (通过 app_process):
 *   app_process -Djava.class.path=/data/local/tmp/lxb-core.jar /system/bin com.lxb.server.Main
 *
 * 架构层次:
 *   Layer 5: System (UiAutomationWrapper, HiddenApiBypass)
 *   Layer 4: Business (ExecutionEngine, PerceptionEngine)
 *   Layer 3: Daemon (SequenceTracker, CircuitBreaker)
 *   Layer 2: Protocol (FrameCodec)
 *   Layer 1: Network (UdpServer)
 */
public class Main {

    private static final String TAG = "[LXB]";

    public static void main(String[] args) {
        System.out.println(TAG + " =============================================");
        System.out.println(TAG + " LXB Server v1.0 - Binary First Architecture");
        System.out.println(TAG + " =============================================");

        try {
            // =================================================================
            // Phase 1: Hidden API 绕过 (必须最先执行)
            // =================================================================
            System.out.println(TAG + " [1/5] Bypassing Hidden API restrictions...");
            boolean bypassSuccess = HiddenApiBypass.bypass();
            if (!bypassSuccess) {
                System.err.println(TAG + " WARNING: Hidden API bypass failed!");
                System.err.println(TAG + " Some features may not work correctly.");
            }

            // =================================================================
            // Phase 2: 系统层初始化 (UiAutomation)
            // =================================================================
            System.out.println(TAG + " [2/5] Initializing UiAutomation...");
            UiAutomationWrapper uiAutomation = new UiAutomationWrapper();
            try {
                uiAutomation.initialize();
            } catch (Exception e) {
                System.err.println(TAG + " WARNING: UiAutomation init failed: " + e.getMessage());
                System.err.println(TAG + " Input injection may not work.");
            }

            // =================================================================
            // Phase 3: 业务层初始化 (注入 UiAutomation)
            // =================================================================
            System.out.println(TAG + " [3/5] Initializing engines...");

            PerceptionEngine perceptionEngine = new PerceptionEngine();
            perceptionEngine.setUiAutomation(uiAutomation);
            perceptionEngine.initialize();

            ExecutionEngine executionEngine = new ExecutionEngine();
            executionEngine.setUiAutomation(uiAutomation);
            executionEngine.initialize();

            // 弹窗检测器暂时禁用，改用 Python 端 VLM 检测
            // PopupDetector popupDetector = new PopupDetector();
            // popupDetector.initialize(uiAutomation);

            // =================================================================
            // Phase 4: 守护层初始化
            // =================================================================
            System.out.println(TAG + " [4/5] Initializing daemon services...");
            SequenceTracker sequenceTracker = new SequenceTracker();
            CircuitBreaker circuitBreaker = new CircuitBreaker();

            // 初始化分派器
            CommandDispatcher dispatcher = new CommandDispatcher(
                perceptionEngine,
                executionEngine,
                // popupDetector,  // 暂时禁用
                sequenceTracker,
                circuitBreaker
            );

            // =================================================================
            // Phase 5: 网络层启动
            // =================================================================
            int port = 12345;
            if (args.length > 0) {
                try {
                    port = Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {}
            }

            System.out.println(TAG + " [5/5] Starting UDP server...");
            UdpServer server = new UdpServer();
            server.listen(port);
            System.out.println(TAG + " Server listening on UDP port " + port);
            System.out.println(TAG + " Ready to accept connections.");
            System.out.println(TAG + " =============================================");

            // =================================================================
            // 主循环
            // =================================================================
            while (true) {
                try {
                    // 接收帧（1 秒超时用于健康检查）
                    UdpServer.ReceivedFrame frame = server.receive(1000);

                    // 日志记录
                    System.out.println(TAG + " [UDP] Received " + frame.data.length +
                            " bytes from " + frame.address.getHostAddress() +
                            ":" + frame.port);

                    // 完整解码帧 (含 CRC32 验证)
                    FrameCodec.DecodedFrame decoded;
                    try {
                        decoded = FrameCodec.decode(frame.data);
                    } catch (FrameCodec.ProtocolException e) {
                        System.err.println(TAG + " [Frame] Protocol error: " + e.getMessage());
                        continue;
                    } catch (FrameCodec.CRCException e) {
                        System.err.println(TAG + " [Frame] CRC error: " + e.getMessage());
                        continue;
                    }

                    // 日志帧信息
                    System.out.println(TAG + " [Frame] " + decoded.toString());

                    // =========================================================
                    // 特殊处理: CMD_IMG_REQ (0x61) 需要分片传输
                    // =========================================================
                    if ((decoded.cmd & 0xFF) == (com.lxb.server.protocol.CommandIds.CMD_IMG_REQ & 0xFF)) {
                        System.out.println(TAG + " [Special] Handling fragmented screenshot...");
                        boolean success = perceptionEngine.handleFragmentedScreenshot(
                                server,
                                frame.address,
                                frame.port,
                                decoded.seq
                        );
                        System.out.println(TAG + " [Special] Fragmented transfer " +
                                (success ? "SUCCESS" : "FAILED"));
                        continue;  // 分片传输自行处理响应，不需要下面的普通流程
                    }

                    // 构建帧信息用于分派
                    FrameCodec.FrameInfo info = new FrameCodec.FrameInfo();
                    info.magic = FrameCodec.MAGIC;
                    info.version = decoded.version;
                    info.seq = decoded.seq;
                    info.cmd = decoded.cmd;
                    info.payloadLength = decoded.payload.length;

                    // 分派处理
                    byte[] response = dispatcher.dispatch(info, decoded.payload);

                    // 发送响应
                    server.send(frame.address, frame.port, response);

                    System.out.println(TAG + " [UDP] Sent " + response.length +
                            " bytes response");

                } catch (SocketTimeoutException e) {
                    // 正常超时，继续循环（可用于心跳检测）
                } catch (Exception e) {
                    System.err.println(TAG + " [Loop] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }

        } catch (Exception e) {
            System.err.println(TAG + " FATAL: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
