package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;

public final class TaskMapTapVerificationRequest {
    public final String taskId;
    public final String routeId;
    public final String packageName;
    public final String segmentId;
    public final int stepIndex;
    public final TaskMap.Step currentStep;
    public final TaskMap.Step lastTapStep;
    public final int lastTapIndex;
    public final String currentStepLocatorMode;
    public final String lastTapLocatorMode;
    public final int lastTapX;
    public final int lastTapY;
    public final int lastTapRetryCount;
    public final String lastTapResultReason;
    public final byte[] screenshotPng;
    public final String historyText;

    public TaskMapTapVerificationRequest(
            String taskId,
            String routeId,
            String packageName,
            String segmentId,
            int stepIndex,
            TaskMap.Step currentStep,
            TaskMap.Step lastTapStep,
            int lastTapIndex,
            String currentStepLocatorMode,
            String lastTapLocatorMode,
            int lastTapX,
            int lastTapY,
            int lastTapRetryCount,
            String lastTapResultReason,
            byte[] screenshotPng,
            String historyText
    ) {
        this.taskId = taskId != null ? taskId : "";
        this.routeId = routeId != null ? routeId : "";
        this.packageName = packageName != null ? packageName : "";
        this.segmentId = segmentId != null ? segmentId : "";
        this.stepIndex = stepIndex;
        this.currentStep = currentStep;
        this.lastTapStep = lastTapStep;
        this.lastTapIndex = lastTapIndex;
        this.currentStepLocatorMode = currentStepLocatorMode != null ? currentStepLocatorMode : "";
        this.lastTapLocatorMode = lastTapLocatorMode != null ? lastTapLocatorMode : "";
        this.lastTapX = lastTapX;
        this.lastTapY = lastTapY;
        this.lastTapRetryCount = Math.max(0, lastTapRetryCount);
        this.lastTapResultReason = lastTapResultReason != null ? lastTapResultReason : "";
        this.screenshotPng = screenshotPng;
        this.historyText = historyText != null ? historyText : "";
    }
}
