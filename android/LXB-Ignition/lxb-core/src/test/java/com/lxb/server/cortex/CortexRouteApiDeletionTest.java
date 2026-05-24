package com.lxb.server.cortex;

import com.lxb.server.protocol.CommandIds;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

public class CortexRouteApiDeletionTest {

    @Test
    public void commandIds_doNotExposeDeprecatedRouteOnlyRunCommand() {
        for (Field field : CommandIds.class.getDeclaredFields()) {
            Assert.assertNotEquals("CMD_CORTEX_ROUTE_RUN", field.getName());
        }
    }
}
