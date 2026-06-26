package com.lxb.server.cortex;

import com.lxb.server.cortex.taskmap.TaskMapStore;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class CortexFsmCancellationTest {

    @Test
    public void visionAct_cancelledBeforeFirstTurnSettleFailsImmediately() throws Exception {
        TraceLogger trace = new TraceLogger(128);
        CortexFsmEngine engine = new CortexFsmEngine(
                null,
                null,
                null,
                trace,
                new TaskMapStore(Files.createTempDirectory("fsm-cancel-test").toFile())
        );
        CortexFsmEngine.Context ctx = new CortexFsmEngine.Context("task-vision-cancel");
        ctx.cancellationChecker = new CortexFsmEngine.CancellationChecker() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        CortexFsmEngine.State next = invokeVisionAct(engine, ctx);

        Assert.assertEquals(CortexFsmEngine.State.FAIL, next);
        Assert.assertEquals("cancelled_by_user", ctx.error);
        Assert.assertTrue(traceContains(trace, "fsm_task_cancelled", "\"task_id\":\"task-vision-cancel\""));
        Assert.assertFalse(traceContains(trace, "vision_settle_begin", "\"op\":\"FIRST_TURN_PRE_SCREENSHOT\""));
    }

    private static CortexFsmEngine.State invokeVisionAct(CortexFsmEngine engine, CortexFsmEngine.Context ctx) throws Exception {
        Method method = CortexFsmEngine.class.getDeclaredMethod("runVisionActState", CortexFsmEngine.Context.class);
        method.setAccessible(true);
        return (CortexFsmEngine.State) method.invoke(engine, ctx);
    }

    private static boolean traceContains(TraceLogger trace, String event, String fragment) {
        for (String line : traceLines(trace)) {
            if (line.contains("\"event\":\"" + event + "\"") && line.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> traceLines(TraceLogger trace) {
        TraceLogger.PullPage page = trace.pullTail(128);
        List<String> out = new ArrayList<String>();
        for (TraceLogger.PullItem item : page.items) {
            out.add(item.line);
        }
        return out;
    }
}
