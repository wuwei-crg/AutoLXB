package com.lxb.server.execution;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import com.lxb.server.system.UiAutomationWrapper;

/**
 * 执行引擎 - 负责输入注入和系统操作
 *
 * 处理的命令:
 * - 0x10 TAP: 单击
 * - 0x11 SWIPE: 滑动
 * - 0x12 LONG_PRESS: 长按
 * - 0x1B UNLOCK: 解锁屏幕
 * - 0x20 INPUT_TEXT: 文本输入
 * - 0x21 KEY_EVENT: 按键事件
 * - 0x43 LAUNCH_APP: 启动应用
 * - 0x44 STOP_APP: 停止应用
 * - 0x48 LIST_APPS: 获取已安装应用列表
 */
public class ExecutionEngine {

    private static final String TAG = "[LXB][Execution]";

    // 系统层依赖
    private UiAutomationWrapper uiAutomation;
    private static final int INPUT_METHOD_ADB = 0;
    private static final int INPUT_METHOD_CLIPBOARD = 1;
    private static final int INPUT_METHOD_ACCESSIBILITY = 2;

    /**
     * 设置 UiAutomation 依赖
     *
     * @param wrapper UiAutomationWrapper 实例
     */
    public void setUiAutomation(UiAutomationWrapper wrapper) {
        this.uiAutomation = wrapper;
    }

    /**
     * 初始化执行引擎
     */
    public void initialize() {
        System.out.println(TAG + " Engine initialized");
        if (uiAutomation == null) {
            System.err.println(TAG + " WARNING: UiAutomation not set!");
        }
    }

    /**
     * 处理 TAP 命令 (0x10)
     *
     * Payload 格式: x[2B] + y[2B]
     *
     * @param payload 请求负载 (4 字节)
     * @return ACK 响应 (1 字节: success)
     */
    public byte[] handleTap(byte[] payload) {
        if (payload.length < 4) {
            System.err.println(TAG + " TAP payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int x = buffer.getShort() & 0xFFFF;
        int y = buffer.getShort() & 0xFFFF;

        System.out.println(TAG + " TAP at (" + x + ", " + y + ")");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.click(x, y);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 SWIPE 命令 (0x11)
     *
     * Payload 格式: x1[2B] + y1[2B] + x2[2B] + y2[2B] + duration[2B]
     *
     * @param payload 请求负载 (10 字节)
     * @return ACK 响应
     */
    public byte[] handleSwipe(byte[] payload) {
        if (payload.length < 10) {
            System.err.println(TAG + " SWIPE payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int x1 = buffer.getShort() & 0xFFFF;
        int y1 = buffer.getShort() & 0xFFFF;
        int x2 = buffer.getShort() & 0xFFFF;
        int y2 = buffer.getShort() & 0xFFFF;
        int duration = buffer.getShort() & 0xFFFF;

        System.out.println(TAG + " SWIPE from (" + x1 + ", " + y1 +
                ") to (" + x2 + ", " + y2 + "), duration=" + duration + "ms");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.swipe(x1, y1, x2, y2, duration);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 LONG_PRESS 命令 (0x12)
     *
     * Payload 格式: x[2B] + y[2B] + duration[2B]
     *
     * @param payload 请求负载 (6 字节)
     * @return ACK 响应
     */
    public byte[] handleLongPress(byte[] payload) {
        if (payload.length < 6) {
            System.err.println(TAG + " LONG_PRESS payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int x = buffer.getShort() & 0xFFFF;
        int y = buffer.getShort() & 0xFFFF;
        int duration = buffer.getShort() & 0xFFFF;

        System.out.println(TAG + " LONG_PRESS at (" + x + ", " + y +
                "), duration=" + duration + "ms");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.longPress(x, y, duration);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 INPUT_TEXT 命令 (0x20)
     *
     * Payload 格式:
     *   method[1B] + flags[1B] + target_x[2B] + target_y[2B] +
     *   delay_ms[2B] + text_len[2B] + text[UTF-8]
     *
     * Flags:
     *   bit 0: CLEAR_FIRST - 先清空输入框
     *   bit 1: PRESS_ENTER - 输入后按回车
     *   bit 2: HIDE_KEYBOARD - 输入后隐藏键盘
     *
     * @param payload 请求负载
     * @return ACK 响应 (2 字节: status + actual_method)
     */
    public byte[] handleInputText(byte[] payload) {
        if (payload.length < 10) {
            System.err.println(TAG + " INPUT_TEXT payload too short: " + payload.length);
            return new byte[]{0x00, 0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int method = buffer.get() & 0xFF;
        int flags = buffer.get() & 0xFF;
        int targetX = buffer.getShort() & 0xFFFF;
        int targetY = buffer.getShort() & 0xFFFF;
        int delayMs = buffer.getShort() & 0xFFFF;
        int textLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 10 + textLen) {
            System.err.println(TAG + " INPUT_TEXT text truncated");
            return new byte[]{0x00, 0x00};
        }

        byte[] textBytes = new byte[textLen];
        buffer.get(textBytes);
        String text = new String(textBytes, StandardCharsets.UTF_8);

        boolean clearFirst = (flags & 0x01) != 0;
        boolean pressEnter = (flags & 0x02) != 0;
        boolean hideKeyboard = (flags & 0x04) != 0;

        System.out.println(TAG + " INPUT_TEXT: \"" + text + "\" method=" + method +
                " clearFirst=" + clearFirst + " pressEnter=" + pressEnter);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00, 0x00};
        }

        // 如果指定了目标坐标，先点击
        if (targetX > 0 && targetY > 0) {
            uiAutomation.click(targetX, targetY);
            try {
                Thread.sleep(100);  // 等待键盘弹出
            } catch (InterruptedException ignored) {}
        }

        // 清空现有文本
        if (clearFirst) {
            uiAutomation.clearFocusedText();
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
        }

        int actualMethod = method;
        boolean success = false;

        // 优先按客户端指定 method 执行；失败后自动回退。
        if (method == INPUT_METHOD_CLIPBOARD) {
            // Prefer ACTION_SET_TEXT first for complex text (Chinese/emoji), then fallback.
            success = uiAutomation.setFocusedText(text);
            actualMethod = INPUT_METHOD_ACCESSIBILITY;
            if (!success) {
                success = uiAutomation.inputTextByClipboard(text);
                actualMethod = INPUT_METHOD_CLIPBOARD;
            }
            if (!success) {
                success = uiAutomation.inputTextDirect(text);
                actualMethod = INPUT_METHOD_ADB;
            }
        } else if (method == INPUT_METHOD_ADB) {
            success = uiAutomation.inputTextDirect(text);
            actualMethod = INPUT_METHOD_ADB;
            if (!success) {
                success = uiAutomation.setFocusedText(text);
                actualMethod = INPUT_METHOD_ACCESSIBILITY;
            }
        } else if (method == INPUT_METHOD_ACCESSIBILITY) {
            success = uiAutomation.setFocusedText(text);
            actualMethod = INPUT_METHOD_ACCESSIBILITY;
            if (!success) {
                success = uiAutomation.inputTextByClipboard(text);
                actualMethod = INPUT_METHOD_CLIPBOARD;
            }
            if (!success) {
                success = uiAutomation.inputTextDirect(text);
                actualMethod = INPUT_METHOD_ADB;
            }
        } else {
            success = uiAutomation.setFocusedText(text);
            actualMethod = INPUT_METHOD_ACCESSIBILITY;
            if (!success) {
                success = uiAutomation.inputTextByClipboard(text);
                actualMethod = INPUT_METHOD_CLIPBOARD;
            }
            if (!success) {
                success = uiAutomation.inputTextDirect(text);
                actualMethod = INPUT_METHOD_ADB;
            }
        }

        System.out.println(TAG + " INPUT_TEXT result: " + (success ? "OK" : "FAIL") + " method=" + actualMethod);

        // 按回车
        if (pressEnter) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {}
            uiAutomation.pressKey(uiAutomation.getKeycodeEnter());
        }

        // 隐藏键盘 (按返回键)
        if (hideKeyboard) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {}
            uiAutomation.pressKey(uiAutomation.getKeycodeBack());
        }

        return new byte[]{(byte)(success ? 0x01 : 0x00), (byte) actualMethod};
    }

    /**
     * 处理 KEY_EVENT 命令 (0x21)
     *
     * Payload 格式: keycode[1B] + action[1B] + meta_state[4B]
     *
     * Action:
     *   0 = DOWN
     *   1 = UP
     *   2 = CLICK (DOWN + UP)
     *
     * @param payload 请求负载 (6 字节)
     * @return ACK 响应
     */
    public byte[] handleKeyEvent(byte[] payload) {
        // 支持两种格式:
        // - 简化格式 (2 bytes): keycode[1B] + action[1B]
        // - 完整格式 (6 bytes): keycode[1B] + action[1B] + metaState[4B]
        if (payload.length < 2) {
            System.err.println(TAG + " KEY_EVENT payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int keycode = buffer.get() & 0xFF;
        int action = buffer.get() & 0xFF;
        int metaState = 0;

        // 如果有额外字节，读取 metaState
        if (payload.length >= 6) {
            metaState = buffer.getInt();
        }

        System.out.println(TAG + " KEY_EVENT keycode=" + keycode +
                " action=" + action + " metaState=" + metaState);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.injectKeyEvent(keycode, action, metaState);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 UNLOCK 命令 (0x1B)
     *
     * Payload 格式: 无
     *
     * @param payload 请求负载 (应为空)
     * @return ACK 响应
     */
    public byte[] handleUnlock(byte[] payload) {
        System.out.println(TAG + " UNLOCK");

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.unlock();
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * Handle touch mode switch (0x1C).
     *
     * Payload format: mode[1B]
     *   0 = UiAutomation first
     *   1 = shell(input) first
     *
     * Response: status[1B]
     */
    public byte[] handleSetTouchMode(byte[] payload) {
        if (payload.length < 1) {
            System.err.println(TAG + " SET_TOUCH_MODE payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        int mode = payload[0] & 0xFF;
        boolean shellFirst = mode != 0;
        uiAutomation.setPreferShellInputTouch(shellFirst);
        System.out.println(TAG + " SET_TOUCH_MODE: " + (shellFirst ? "shell_first" : "uiautomation_first"));
        return new byte[]{0x01};
    }

    /**
     * Handle screenshot quality switch (0x1D).
     *
     * Payload format: quality[1B], range 1..100.
     *
     * Response: status[1B]
     */
    public byte[] handleSetScreenshotQuality(byte[] payload) {
        if (payload.length < 1) {
            System.err.println(TAG + " SET_SCREENSHOT_QUALITY payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        int quality = payload[0] & 0xFF;
        if (quality < 1 || quality > 100) {
            System.err.println(TAG + " SET_SCREENSHOT_QUALITY invalid value: " + quality);
            return new byte[]{0x00};
        }

        uiAutomation.setScreenshotQuality(quality);
        System.out.println(TAG + " SET_SCREENSHOT_QUALITY: " + quality);
        return new byte[]{0x01};
    }


    /**
     * 处理 LAUNCH_APP 命令 (0x43)
     *
     * Payload 格式: flags[1B] + package_len[2B] + package_name[UTF-8]
     *
     * Flags:
     *   bit 0: CLEAR_TASK - 清除任务栈
     *   bit 1: WAIT - 等待 Activity 启动
     *
     * @param payload 请求负载
     * @return ACK 响应
     */
    public byte[] handleLaunchApp(byte[] payload) {
        if (payload.length < 3) {
            System.err.println(TAG + " LAUNCH_APP payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int flags = buffer.get() & 0xFF;
        int packageLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 3 + packageLen) {
            System.err.println(TAG + " LAUNCH_APP package name truncated");
            return new byte[]{0x00};
        }

        byte[] packageBytes = new byte[packageLen];
        buffer.get(packageBytes);
        String packageName = new String(packageBytes, StandardCharsets.UTF_8);

        System.out.println(TAG + " LAUNCH_APP: " + packageName + " flags=0x" +
                Integer.toHexString(flags));

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.launchApp(packageName, flags);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 STOP_APP 命令 (0x44)
     *
     * Payload 格式: package_len[2B] + package_name[UTF-8]
     *
     * @param payload 请求负载
     * @return ACK 响应
     */
    public byte[] handleStopApp(byte[] payload) {
        if (payload.length < 2) {
            System.err.println(TAG + " STOP_APP payload too short: " + payload.length);
            return new byte[]{0x00};
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.order(ByteOrder.BIG_ENDIAN);

        int packageLen = buffer.getShort() & 0xFFFF;

        if (payload.length < 2 + packageLen) {
            System.err.println(TAG + " STOP_APP package name truncated");
            return new byte[]{0x00};
        }

        byte[] packageBytes = new byte[packageLen];
        buffer.get(packageBytes);
        String packageName = new String(packageBytes, StandardCharsets.UTF_8);

        System.out.println(TAG + " STOP_APP: " + packageName);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        boolean success = uiAutomation.stopApp(packageName);
        return new byte[]{(byte) (success ? 0x01 : 0x00)};
    }

    /**
     * 处理 LIST_APPS 命令 (0x48)
     *
     * Payload 格式: filter[1B]
     *   filter: 0=all, 1=user, 2=system
     *
     * @param payload 请求负载 (1 字节)
     * @return ACK 响应 (1 字节 status + JSON 字符串)
     */
    public byte[] handleListApps(byte[] payload) {
        int filter = 0;  // 默认返回所有应用
        if (payload.length >= 1) {
            filter = payload[0] & 0xFF;
        }

        System.out.println(TAG + " LIST_APPS filter=" + filter);

        if (uiAutomation == null) {
            System.err.println(TAG + " UiAutomation not available");
            return new byte[]{0x00};
        }

        String jsonResult = uiAutomation.listApps(filter);
        byte[] jsonBytes = jsonResult.getBytes(StandardCharsets.UTF_8);

        // 返回: status[1B] + json_len[2B] + json_data
        ByteBuffer response = ByteBuffer.allocate(3 + jsonBytes.length);
        response.order(ByteOrder.BIG_ENDIAN);
        response.put((byte) 0x01);  // success
        response.putShort((short) jsonBytes.length);
        response.put(jsonBytes);

        return response.array();
    }
}
