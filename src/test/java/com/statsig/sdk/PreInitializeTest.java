package com.statsig.sdk;

import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;

public class PreInitializeTest {

    @Test
    public void testDefaultsBeforeInitialize() {
        StatsigUser user = new StatsigUser("test");
        CompletableFuture<Boolean> gateResult = Statsig.checkGateAsync(user, "any_gate");
        assertEquals(gateResult.getNow(true), false);

        CompletableFuture<DynamicConfig> configResult = Statsig.getConfigAsync(user, "any_config");
        assertEquals(configResult.getNow((DynamicConfig.Companion.empty("any_config"))).getValue().size(), 0);

        CompletableFuture<DynamicConfig> expResult = Statsig.getExperimentAsync(user, "any_exp");
        assertEquals(expResult.getNow((DynamicConfig.Companion.empty("any_exp"))).getValue().size(), 0);

        CompletableFuture<Layer> layerResult = Statsig.getLayerAsync(user, "any_layer");
        assertEquals(layerResult.getNow((Layer.Companion.empty("any_layer"))).getValue().size(), 0);

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
