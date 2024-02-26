package com.statsig.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class MultiInstancesTestJava {

    private StatsigUser user = new StatsigUser("test");
    private MockWebServer server;
    private CompletableFuture<LogEventInput> eventLogInputCompletable;
    private StatsigOptions options;
    private Gson gson;

    @Before
    public void setUp() {
        gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
        eventLogInputCompletable = new CompletableFuture();

        Dispatcher dispatcher = new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) throws InterruptedException {
                if (recordedRequest.getPath().contains("/v1/log_event")) {
                    String logBody = recordedRequest.getBody().readUtf8();
                    eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput.class));
                    return new MockResponse().setResponseCode(200);
                }
                return new MockResponse().setResponseCode(404);
            }
        };

        MockWebServer server = new MockWebServer();
        server.setDispatcher(dispatcher);

        options = new StatsigOptions();
        options.setApi(server.url("/v1").toString());
    }

    /**
     * Test case to verify the default behavior of sync core apis
     * before initialization.
     */
    @Test
    public void testDefaultsForSyncVersionBeforeInitialize() {
        StatsigServer instance1 = StatsigServer.create();
        StatsigServer instance2 = StatsigServer.create();

        assertFalse(instance1.isInitialized());
        assertFalse(instance2.isInitialized());

        // Check Gate
        Boolean checkGateSyncRes1 = instance1.checkGateSync(user, "any_gate", null);
        Boolean checkGateSyncRes2 = instance2.checkGateSync(user, "gate_2", new CheckGateOptions(true));
        assertEquals(checkGateSyncRes1, false);
        assertEquals(checkGateSyncRes2, false);

        // Get Config
        DynamicConfig getConfigSyncRes1 = instance1.getConfigSync(user, "any_config", null);
        DynamicConfig getConfigSyncRes2 = instance2.getConfigSync(user, "config_2", new GetConfigOptions(true));
        assertEquals(getConfigSyncRes1.getValue().size(), 0);
        assertEquals(getConfigSyncRes2.getValue().size(), 0);

        // Get Experiment
        DynamicConfig getExpSyncResult1 = instance1.getExperimentSync(user, "exp_1", null);
        DynamicConfig getExpSyncResult2 = instance2.getExperimentSync(user, "exp_2", null);
        assertEquals(getExpSyncResult1.getValue().size(), 0);
        assertEquals(getExpSyncResult2.getValue().size(), 0);

        // Get Layer
        Layer getLayerSyncRes1 = instance1.getLayerSync(user, "layer_1", null);
        Layer getLayerSyncRes2 = instance2.getLayerSync(user, "layer_2", null);
        assertEquals(getLayerSyncRes1.getValue().size(), 0);
        assertEquals(getLayerSyncRes2.getValue().size(), 0);

        instance1.shutdown();
        instance2.shutdown();
    }

    /**
     * Test case to verify the default behavior of async core apis
     * before initialization.
     */
    @Test
    public void testDefaultsForAsyncVersionBeforeInitialize() {
        StatsigServer instance1 = StatsigServer.create();
        StatsigServer instance2 = StatsigServer.create();

        // Check Gate
        CompletableFuture<Boolean> checkGateRes1 = instance1.checkGateAsync(user, "any_gate");
        CompletableFuture<Boolean> checkGateRes2 = instance2.checkGateAsync(user, "any_gate");
        assertEquals(checkGateRes1.getNow(true), false);
        assertEquals(checkGateRes2.getNow(true), false);

        // Get Config
        CompletableFuture<DynamicConfig> getConfigRes1 = instance1.getConfigAsync(user, "any_config");
        CompletableFuture<DynamicConfig> getConfigRes2 = instance2.getConfigAsync(user, "config_2");
        assertEquals(getConfigRes1.getNow((DynamicConfig.Companion.empty("any_config"))).getValue().size(), 0);
        assertEquals(getConfigRes2.getNow((DynamicConfig.Companion.empty("config_2"))).getValue().size(), 0);

        // Get Experiment
        CompletableFuture<DynamicConfig> getExpResult1 = instance1.getExperimentAsync(user, "exp_1");
        CompletableFuture<DynamicConfig> getExpResult2 = instance2.getExperimentAsync(user, "exp_2");
        assertEquals(getExpResult1.getNow(DynamicConfig.Companion.empty("exp_1")).getValue().size(), 0);
        assertEquals(getExpResult2.getNow(DynamicConfig.Companion.empty("exp_2")).getValue().size(), 0);
        
        // Get Layer
        CompletableFuture<Layer> getLayerRes1 = instance1.getLayerAsync(user, "layer_1");
        CompletableFuture<Layer> getLayerRes2 = instance2.getLayerAsync(user, "layer_2");
        assertEquals(getLayerRes1.getNow(Layer.Companion.empty("layer_1")).getValue().size(), 0);
        assertEquals(getLayerRes2.getNow(Layer.Companion.empty("layer_2")).getValue().size(), 0);

        instance1.shutdown();
        instance2.shutdown();
    }

    @Test
    public void testDefaultsForOverridesApi() throws Exception {
        StatsigServer instance1 = StatsigServer.create();
        StatsigServer instance2 = StatsigServer.create();

        instance1.initializeAsync("secret_key_1", new StatsigOptions()).get();

        assertFalse(instance1.checkGateSync(user, "test_gate", null));
        instance1.overrideGate("test_gate", false);
        assertFalse(instance1.checkGateSync(user, "test_gate", null));
        instance1.overrideGate("test_gate", true);
        assertTrue(instance1.checkGateSync(user, "test_gate", null));
        instance1.removeGateOverride("test_gate");
        assertFalse(instance1.checkGateSync(user, "test_gate", null));

        instance2.initializeAsync("secret_key_2", new StatsigOptions()).get();

        assertFalse(instance2.checkGateSync(user, "test_gate", null));
        instance2.overrideGate("test_gate", false);
        assertFalse(instance2.checkGateSync(user, "test_gate", null));
        instance2.overrideGate("test_gate", true);
        assertTrue(instance2.checkGateSync(user, "test_gate", null));
        instance2.removeGateOverride("test_gate");
        assertFalse(instance2.checkGateSync(user, "test_gate", null));

        // Check Config
        Map<String, String> emptyMap = new HashMap<>();
        Map<String, String> overrideValue = new HashMap<>();
        overrideValue.put("Hello", "Test");

        assertEquals(instance1.getConfigSync(user, "test_config", null).getValue(), emptyMap);
        instance1.overrideConfig("test_config", overrideValue);
        assertEquals(instance1.getConfigSync(user, "test_config", null).getValue(), overrideValue);
        instance1.removeConfigOverride("test_config");
        assertEquals(instance1.getConfigSync(user, "test_config", null).getValue(), emptyMap);

        assertEquals(instance2.getConfigSync(user, "test_config_2", null).getValue(), emptyMap);
        instance2.overrideConfig("test_config_2", overrideValue);
        assertEquals(instance2.getConfigSync(user, "test_config_2", null).getValue(), overrideValue);
        instance2.removeConfigOverride("test_config_2");
        assertEquals(instance2.getConfigSync(user, "test_config_2", null).getValue(), emptyMap);


        Map<String, String> emptyLayer = new HashMap<>();
        Map<String, String> overrideLayer = new HashMap<>();
        overrideLayer.put("Hello", "Test");

        assertEquals(instance1.getLayerSync(user, "test_layer", null).getValue(), emptyLayer);
        instance1.overrideLayer("test_layer", overrideLayer);
        assertEquals(instance1.getLayerSync(user, "test_layer", null).getValue(), overrideLayer);
        instance1.removeLayerOverride("test_layer");
        assertEquals(instance1.getLayerSync(user, "test_layer", null).getValue(), emptyLayer);

        assertEquals(instance2.getLayerSync(user, "test_layer_2", null).getValue(), emptyLayer);
        instance2.overrideLayer("test_layer_2", overrideLayer);
        assertEquals(instance2.getLayerSync(user, "test_layer_2", null).getValue(), overrideLayer);
        instance2.removeLayerOverride("test_layer_2");
        assertEquals(instance2.getLayerSync(user, "test_layer_2", null).getValue(), emptyLayer);

        instance1.shutdown();
        instance2.shutdown();
    }

    @Test
    public void testLogEvent() throws Exception {
        StatsigServer instance1 = StatsigServer.create();

        instance1.logEvent(user, "test_event");
        instance1.logEvent(user, "test_event", "test_values");

        StatsigEvent[] events = captureEvents(eventLogInputCompletable);
        Assert.assertEquals(0, events.length);

        instance1.initializeAsync("secret-local", options).get();
        instance1.logEvent(user, "test_event");
        instance1.shutdown();

        StatsigEvent[] events2 = captureEvents(eventLogInputCompletable);
        Assert.assertEquals(1, events2.length);
    }

    private StatsigEvent[] captureEvents(CompletableFuture<LogEventInput> eventLogInputCompletable) {
        try {
            StatsigEvent[] res = eventLogInputCompletable.get(100, TimeUnit.MILLISECONDS).getEvents();
            return Arrays.stream(res).filter(event -> !event.getEventName().equals("statsig::diagnostics")).toArray(StatsigEvent[] ::new);
        } catch (Exception e){}

        return new StatsigEvent[0];
    }
}
