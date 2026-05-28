package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMap;

public final class StepVisualResolveRequest {
    public final String taskId;
    public final String routeId;
    public final String packageName;
    public final String segmentId;
    public final int stepIndex;
    public final TaskMap.Step step;
    public final byte[] screenshotPng;
    public final String historyText;
    public final String locatorFailureReason;

    public StepVisualResolveRequest(
            String taskId,
            String routeId,
            String packageName,
            String segmentId,
            int stepIndex,
            TaskMap.Step step,
            byte[] screenshotPng,
            String historyText,
            String locatorFailureReason
    ) {
        this.taskId = taskId != null ? taskId : "";
        this.routeId = routeId != null ? routeId : "";
        this.packageName = packageName != null ? packageName : "";
        this.segmentId = segmentId != null ? segmentId : "";
        this.stepIndex = stepIndex;
        this.step = step;
        this.screenshotPng = screenshotPng;
        this.historyText = historyText != null ? historyText : "";
        this.locatorFailureReason = locatorFailureReason != null ? locatorFailureReason : "";
    }
}
