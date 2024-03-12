package com.statsig.sdk;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.*;

public class PreInitializeTest {

    @Test
    public void testDefaultsBeforeInitialize() {
        StatsigUser user = new StatsigUser("test");
        CompletableFuture<Boolean> gateResult = Statsig.checkGateAsync(user, "any_gate");
        assertEquals(gateResult.getNow(true), false);

        Boolean checkGateSyncRes = Statsig.checkGateSync(user, "any_gate", null);
        assertEquals(checkGateSyncRes, false);

        CompletableFuture<DynamicConfig> configResult = Statsig.getConfigAsync(user, "any_config");

        assertEquals(configResult.getNow((DynamicConfig.Companion.empty("any_config"))).getValue().size(), 0);

        DynamicConfig getConfigSyncRes = Statsig.getConfigSync(user, "any_config", null);
        assertEquals(getConfigSyncRes.getValue().size(), 0);

        CompletableFuture<DynamicConfig> expResult = Statsig.getExperimentAsync(user, "any_exp");
        assertEquals(expResult.getNow((DynamicConfig.Companion.empty("any_exp"))).getValue().size(), 0);

        DynamicConfig getExpSyncResult = Statsig.getExperimentSync(user, "any_exp", null);
        assertEquals(getExpSyncResult.getValue().size(), 0);

        CompletableFuture<Layer> layerResult = Statsig.getLayerAsync(user, "any_layer");
        assertEquals(layerResult.getNow((Layer.Companion.empty("any_layer"))).getValue().size(), 0);

        Layer getLayerSyncRes = Statsig.getLayerSync(user, "any_layer", null);
        assertEquals(getLayerSyncRes.getValue().size(), 0);

        APIFeatureGate featureGate = Statsig.getFeatureGate(user, "any_gate");
        assertEquals(featureGate.getName(), "any_gate");
        assertFalse(featureGate.getValue());
        assertNull(featureGate.getRuleID());
        assertEquals(featureGate.getSecondaryExposures(), new ArrayList<>());
        assertEquals(featureGate.getReason(), EvaluationReason.UNINITIALIZED);

        // Should not throw, should noop
        Statsig.overrideGate("test_gate", false);
        Statsig.removeGateOverride("test_gate");

        Statsig.overrideConfig("test_config", new HashMap<>());
        Statsig.removeConfigOverride("test_config");

        Statsig.overrideLayer("test_layer", new HashMap<>());
        Statsig.removeLayerOverride("test_layer");

        Statsig.logEvent(user, "test_event");
        Statsig.logEvent(user, "test_event", "test_values");
        Statsig.logEvent(user, "test_event", 1);
        Statsig.logEvent(user, "test_event", 1, new HashMap<>());
        Statsig.shutdown();
    }

}
