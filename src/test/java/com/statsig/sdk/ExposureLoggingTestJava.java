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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public class ExposureLoggingTestJava {
    private Gson gson;
    private CompletableFuture<LogEventInput> eventLogInputCompletable;
    private StatsigServer driver;
    private StatsigUser user;
    private StatsigOptions options;

    @Before
    public void setUp() {
        gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
        user = new StatsigUser("abc");
        eventLogInputCompletable = new CompletableFuture();

        APIFeatureGate mockGateResponse = new APIFeatureGate("a_gate", true, "ruleID", new ArrayList<>());
        String mockResponseBody = gson.toJson(mockGateResponse);

        String downloadConfigSpecsResponse =
                String.valueOf(StatsigE2ETest.class.getResource("/layer_exposure_download_config_specs.json"));

        Dispatcher dispatcher = new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) throws InterruptedException {
                if (recordedRequest.getPath().contains("/v1/download_config_specs")) {
                    return new MockResponse().setResponseCode(200).setBody(downloadConfigSpecsResponse);
                }
                if (recordedRequest.getPath().contains("/v1/check_gate")) {
                    return new MockResponse().setResponseCode(200).setBody(mockResponseBody);
                }
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
        options.setDisableDiagnostics(true);
        options.setApi(server.url("/v1").toString());

        driver = StatsigServer.create();
    }

    @Test
    public void testManualLogLayerParameterExposureAsync() throws ExecutionException, InterruptedException {
        driver.initializeAsync("secret-local", options).get();
        driver.manuallyLogLayerParameterExposureAsync(user, "a_layer", "a_param").get();
        driver.shutdown();

        StatsigEvent[] events = captureEvents(eventLogInputCompletable);
        Assert.assertEquals(1, events.length);
        Assert.assertEquals(events[0].getEventMetadata().get("isManualExposure"), "true");
    }

    @Test
    public void testManuallyLogGateExposureAsync() throws ExecutionException, InterruptedException {
        driver.initializeAsync("secret-local", options).get();
        driver.manuallyLogGateExposureAsync(user, "a_gate").get();
        driver.shutdown();

        StatsigEvent[] events = captureEvents(eventLogInputCompletable);
        Assert.assertEquals(1, events.length);
        Assert.assertEquals(events[0].getEventMetadata().get("isManualExposure"), "true");
    }

    @Test
    public void testManuallyLogConfigExposureAsync() throws ExecutionException, InterruptedException {
        driver.initializeAsync("secret-local", options).get();
        driver.manuallyLogConfigExposureAsync(user, "a_config").get();
        driver.shutdown();

        StatsigEvent[] events = captureEvents(eventLogInputCompletable);
        Assert.assertEquals(1, events.length);
        Assert.assertEquals(events[0].getEventMetadata().get("isManualExposure"), "true");
    }

    // TODO: call manuallyExperimentExposureAsync from top level.
    @Test
    public void testManuallyLogExperimentExposureAsync() throws ExecutionException, InterruptedException {
        driver.initializeAsync("secret-local", options).get();
        driver.manuallyLogConfigExposureAsync(user, "an_experiment").get();
        driver.shutdown();

        StatsigEvent[] events = captureEvents(eventLogInputCompletable);
        Assert.assertEquals(1, events.length);
        Assert.assertEquals(events[0].getEventMetadata().get("isManualExposure"), "true");
    }


    private StatsigEvent[] captureEvents(CompletableFuture<LogEventInput> eventLogInputCompletable) {
        try {
            StatsigEvent[] res = eventLogInputCompletable.get(100, TimeUnit.MILLISECONDS).getEvents();
            return Arrays.stream(res).filter(event -> !event.getEventName().equals("statsig::diagnostics")).toArray(StatsigEvent[] ::new);
        } catch (Exception e){}

        return new StatsigEvent[0];
    }
}