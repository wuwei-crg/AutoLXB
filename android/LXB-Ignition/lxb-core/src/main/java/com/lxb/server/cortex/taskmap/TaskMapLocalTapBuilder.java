package com.lxb.server.cortex.taskmap;

import com.lxb.server.cortex.dump.DumpActionsParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TaskMapLocalTapBuilder {

    public interface LocatorUniquenessGate {
        boolean isAccepted(Map<String, Object> locator);
    }

    public static final class LocalTapPayload {
        public final Map<String, Object> locator = new LinkedHashMap<String, Object>();
    }

    private TaskMapLocalTapBuilder() {}

    public static LocalTapPayload materializeTap(
            int x,
            int y,
            List<DumpActionsParser.ActionNode> nodes,
            LocatorUniquenessGate uniquenessGate
    ) {
        LocalTapPayload out = new LocalTapPayload();
        Map<String, Object> locator = RuntimeLocatorBuilder.buildLocator(x, y, nodes);
        boolean accepted = uniquenessGate != null && uniquenessGate.isAccepted(locator);
        if (accepted) {
            out.locator.putAll(locator);
            return out;
        }
        return out;
    }
}
