package com.statsig.sdk;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class StatsigServerVisibilityTest {
    private StatsigServer statsigServer;
    private StatsigUser user;

    @Before
    public void setUp() throws ExecutionException, InterruptedException {
        statsigServer = StatsigServer.create();
        CompletableFuture<InitializationDetails> future = statsigServer.initializeAsync("secret-key", new StatsigOptions());
        future.get();
        user = new StatsigUser("test123");
    }

    @Test
    public void testVisibility() throws ExecutionException, InterruptedException {
        statsigServer.getFeatureGate(user, "test_gate");
        CompletableFuture<Void> future = statsigServer.manuallyLogLayerParameterExposureAsync(user, "test_layer", "button_color");
        future.get();
    }
}
