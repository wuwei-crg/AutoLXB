package com.lxb.server.dispatcher;

import com.lxb.server.daemon.CircuitBreaker;
import com.lxb.server.daemon.SequenceTracker;
import com.lxb.server.execution.ExecutionEngine;
import com.lxb.server.perception.PerceptionEngine;
import com.lxb.server.protocol.FrameCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command dispatcher.
 *
 * Design note:
 * - No session/connection state is kept here.
 * - UDP duplicate handling is done with a short-lived frame fingerprint window.
 */
public class CommandDispatcher {

    private static final String TAG = "[LXB][Dispatcher]";

    private final PerceptionEngine perceptionEngine;
    private final ExecutionEngine executionEngine;
    private final SequenceTracker sequenceTracker;
    private final CircuitBreaker circuitBreaker;

    // ACK cache keyed by frame fingerprint (for UDP retry dedup only).
    private final Map<SequenceTracker.FrameKey, byte[]> ackCache =
            new LinkedHashMap<SequenceTracker.FrameKey, byte[]>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<SequenceTracker.FrameKey, byte[]> eldest) {
                    return size() > 128;
                }
            };

    public CommandDispatcher(
            PerceptionEngine perceptionEngine,
            ExecutionEngine executionEngine,
            SequenceTracker sequenceTracker,
            CircuitBreaker circuitBreaker
    ) {
        this.perceptionEngine = perceptionEngine;
        this.executionEngine = executionEngine;
        this.sequenceTracker = sequenceTracker;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Dispatch command and return ACK frame.
     */
    public byte[] dispatch(FrameCodec.FrameInfo frame, byte[] payload) {
        SequenceTracker.FrameKey frameKey = new SequenceTracker.FrameKey(frame.seq, frame.cmd, payload);

        // 1) Short-lived duplicate detection (same seq+cmd+payload fingerprint)
        if (sequenceTracker.isDuplicate(frame.seq, frame.cmd, payload)) {
            System.out.println(TAG + " Duplicate frame detected, returning cached ACK");
            byte[] cached = ackCache.get(frameKey);
            if (cached != null) {
                return cached;
            }
            // Defensive fallback for duplicate-without-cache:
            return buildAck(frame.seq, (byte) 0x02, new byte[0]);
        }

        // 2) Circuit breaker
        if (circuitBreaker.shouldReject()) {
            System.out.println(TAG + " Circuit breaker triggered, rejecting");
            return buildErrorAck(frame.seq, (byte) 0xFF);
        }

        // 3) Route command
        byte[] response;
        try {
            switch (frame.cmd) {
                // Link Layer
                case 0x01:  // CMD_HANDSHAKE
                    response = handleHandshake();
                    break;
                case 0x03:  // CMD_HEARTBEAT
                    response = new byte[]{0x01};
                    break;

                // Input Layer
                case 0x10:  // CMD_TAP
                    response = executionEngine.handleTap(payload);
                    break;
                case 0x11:  // CMD_SWIPE
                    response = executionEngine.handleSwipe(payload);
                    break;
                case 0x12:  // CMD_LONG_PRESS
                    response = executionEngine.handleLongPress(payload);
                    break;
                case 0x1B:  // CMD_UNLOCK
                    response = executionEngine.handleUnlock(payload);
                    break;

                // Input Extension
                case 0x20:  // CMD_INPUT_TEXT
                    response = executionEngine.handleInputText(payload);
                    break;
                case 0x21:  // CMD_KEY_EVENT
                    response = executionEngine.handleKeyEvent(payload);
                    break;

                // Sense Layer
                case 0x30:  // CMD_GET_ACTIVITY
                    response = perceptionEngine.handleGetActivity();
                    break;
                case 0x31:  // CMD_DUMP_HIERARCHY
                    response = perceptionEngine.handleDumpHierarchy(payload);
                    break;
                case 0x32:  // CMD_FIND_NODE
                    response = perceptionEngine.handleFindNode(payload);
                    break;
                case 0x33:  // CMD_DUMP_ACTIONS
                    response = perceptionEngine.handleDumpActions(payload);
                    break;
                case 0x36:  // CMD_GET_SCREEN_STATE
                    response = perceptionEngine.handleGetScreenState();
                    break;
                case 0x37:  // CMD_GET_SCREEN_SIZE
                    response = perceptionEngine.handleGetScreenSize();
                    break;
                case 0x39:  // CMD_FIND_NODE_COMPOUND
                    response = perceptionEngine.handleFindNodeCompound(payload);
                    break;

                // Lifecycle Layer
                case 0x43:  // CMD_LAUNCH_APP
                    response = executionEngine.handleLaunchApp(payload);
                    break;
                case 0x44:  // CMD_STOP_APP
                    response = executionEngine.handleStopApp(payload);
                    break;
                case 0x48:  // CMD_LIST_APPS
                    response = executionEngine.handleListApps(payload);
                    break;

                // Media Layer
                case 0x60:  // CMD_SCREENSHOT
                    response = perceptionEngine.handleScreenshot();
                    break;

                default:
                    System.out.println(TAG + " Unimplemented command: 0x" +
                            String.format("%02X", frame.cmd));
                    response = new byte[]{0x00};
            }

            // 4) Build ACK and cache by frame fingerprint
            byte[] ack = buildAck(frame.seq, (byte) 0x02, response);
            ackCache.put(frameKey, ack);
            return ack;

        } catch (Exception e) {
            circuitBreaker.recordException();
            System.err.println(TAG + " Error handling cmd=0x" +
                    String.format("%02X", frame.cmd) + ": " + e.getMessage());
            e.printStackTrace();
            return buildErrorAck(frame.seq, (byte) 0x00);
        }
    }

    private byte[] buildAck(int seq, byte cmd, byte[] payload) {
        return FrameCodec.encode(seq, cmd, payload);
    }

    private byte[] buildErrorAck(int seq, byte status) {
        return FrameCodec.encode(seq, (byte) 0x02, new byte[]{status});
    }

    private byte[] handleHandshake() {
        System.out.println(TAG + " Handshake received");
        return new byte[0];
    }
}
