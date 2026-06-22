package com.lxb.server.cortex.taskmap;

import org.junit.Assert;
import org.junit.Test;

public class TaskRouteKeyTest {
    @Test
    public void routeKeyCarriesExplicitRouteIdOnly() {
        TaskRouteKey key = TaskRouteKey.route("template", "tpl_1", "com.demo", "open app", "pb", "manual", "tpl_1");
        Assert.assertEquals("tpl_1", key.taskKeyHash);
        Assert.assertEquals("tpl_1", key.asMap().get("route_id"));
    }
}
