package com.lxb.server.cortex;

import com.lxb.server.protocol.CommandIds;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

public class CortexRouteApiDeletionTest {

    @Test
    public void commandIds_doNotExposeDeprecatedRouteOnlyRunCommand() {
        for (Field field : CommandIds.class.getDeclaredFields()) {
            Assert.assertNotEquals("CMD_CORTEX_ROUTE_RUN", field.getName());
        }
    }

    @Test
    public void commandIds_areUnique() throws Exception {
        Set<Integer> seen = new HashSet<Integer>();
        for (Field field : CommandIds.class.getDeclaredFields()) {
            if (field.getType() != byte.class || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            int value = ((Byte) field.get(null)).intValue() & 0xFF;
            Assert.assertTrue("duplicate command id 0x" + Integer.toHexString(value), seen.add(value));
        }
    }
}
