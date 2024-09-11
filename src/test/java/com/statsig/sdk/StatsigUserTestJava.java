package com.statsig.sdk;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class StatsigUserTestJava {
    @Test
    public void testInitializeUserIDAndCustomID() {
        String userId = "test123";
        Map<String, String> customIds = new HashMap<>();
        customIds.put("k1", "v1");
        customIds.put("k2", "v2");

        StatsigUser user = new StatsigUser(userId, customIds);

        assertEquals(userId, user.getUserID());
        assertEquals(customIds, user.getCustomIDs());
    }
}
