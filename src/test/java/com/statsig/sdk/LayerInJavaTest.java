package com.statsig.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;

public class LayerInJavaTest {

    private StatsigServer driver;
    private StatsigUser user = new StatsigUser("123");
    private Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    @Before
    public void setUp() throws Exception {
        StatsigOptions options = new StatsigOptions();
        options.setLocalMode(true);
        options.setRulesUpdatedCallback(rules -> {
            APIDownloadedConfigs configs = gson.fromJson(rules, APIDownloadedConfigs.class);
            assertEquals(configs.getTime(), 0);
            assertEquals(configs.getFeatureGates().length, 0);
            assertEquals(configs.getDynamicConfigs().length, 0);
            assertEquals(configs.getLayerConfigs().length, 0);
        });
        driver = StatsigServer.create("secret-test", options);
        Future initFuture = driver.initializeAsync();
        initFuture.get();
    }

    @Test
    public void testLayer() throws Exception {
        CompletableFuture<Layer> futureLayer = driver.getLayerAsync(user, "empty_layer");
        Layer layer = futureLayer.get();

        assertEquals(layer.getBoolean("default_bool", true), true);
    }
}
