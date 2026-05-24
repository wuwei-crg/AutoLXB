package com.lxb.server.cortex;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CortexFsmStateContractTest {

    @Test
    public void stateEnum_exposesOrderedRefactoredFsmStatesOnly() {
        Set<String> states = new HashSet<String>();
        for (CortexFsmEngine.State state : CortexFsmEngine.State.values()) {
            states.add(state.name());
        }

        Assert.assertEquals(new HashSet<String>(Arrays.asList(
                "INIT",
                "TASK_DECOMPOSE",
                "DEVICE_PREPARE",
                "APP_RESOLVE",
                "APP_ENTER",
                "SCRIPT_ACT",
                "VISION_ACT",
                "FINISH",
                "FAIL"
        )), states);
    }

    @Test
    public void stateEnum_removesDeprecatedRouteMapStates() {
        Set<String> states = new HashSet<String>();
        for (CortexFsmEngine.State state : CortexFsmEngine.State.values()) {
            states.add(state.name());
        }

        Assert.assertFalse(states.contains("TASK_MAP_ROOT_LOOKUP"));
        Assert.assertFalse(states.contains("ROUTE_PLAN"));
        Assert.assertFalse(states.contains("PREPARE_DEVICE"));
        Assert.assertFalse(states.contains("ROUTING"));
    }
}
