package com.statsig.sdk;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public class StatsigReinitializeTest {

    private static final int MAX_CNT = 2;

    private MockWebServer server;
    private StatsigOptions options;
    private int requestCnt;
    private String configSpecsResponse;

    @Before
    public void setUp() throws IOException {
        configSpecsResponse = new String(Files.readAllBytes(Paths.get(Objects.requireNonNull(getClass().getResource("/download_config_specs.json")).getPath())));
        server = new MockWebServer();
        requestCnt = 0;
        server.setDispatcher(new Dispatcher() {
            @NotNull
            @Override
            public MockResponse dispatch(@NotNull RecordedRequest request) {
                // Simulate failure on first request, success on subsequent requests
                if (request.getPath().contains("v1/download_config_specs")) {
                    if (requestCnt < MAX_CNT) {
                        requestCnt++;
                        return new MockResponse().setResponseCode(500);
                    } else {
                        return new MockResponse().setResponseCode(200).setBody(configSpecsResponse);
                    }
                }
                return new MockResponse().setResponseCode(200);
            }
        });

        server.start(9874);

        options = new StatsigOptions();
        options.setApi(server.url("/v1").toString());
        options.setDisableDiagnostics(true);
    }

    @After
    public void tearDown() throws IOException {
        server.shutdown();
        Statsig.shutdown();
    }

    @Test
    public void testReinitialize() throws Exception {
        CompletableFuture<InitializationDetails> detailsFuture = Statsig.initializeAsync("server-secret", options);
        StatsigUser user = new StatsigUser("123");
        user.setEmail("weihao@statsig.com");

        InitializationDetails details = detailsFuture.get();
        Assert.assertNotNull(details);
        Assert.assertTrue(details.isSDKReady());
        Assert.assertFalse(details.getConfigSpecReady());  // First init should fail due to 500 response
        Assert.assertNotNull(details.getFailureDetails());  // Expect failure details for the first init
        Assert.assertFalse(Statsig.checkGateSync(user, "always_on_gate"));
        Assert.assertFalse(Statsig.checkGateSync(user, "on_for_statsig_email"));
        Layer layer1 = Statsig.getLayerSync(user, "c_layer_with_holdout");
        Assert.assertEquals(layer1.getString("holdout_layer_param", "should_return_default"), "should_return_default");


        CompletableFuture<ConfigSyncDetails> details2Future = Statsig.syncConfigSpecs();
        ConfigSyncDetails details2 = details2Future.get();
        Assert.assertNotNull(details2);
        Assert.assertFalse(details2.getConfigSpecReady());  // Second try should also fail
        Assert.assertNotNull(details2.getLcut());
        Assert.assertFalse(Statsig.checkGateSync(user, "always_on_gate"));
        Assert.assertFalse(Statsig.checkGateSync(user, "on_for_statsig_email"));
        Layer layer2 = Statsig.getLayerSync(user, "c_layer_with_holdout");
        Assert.assertEquals(layer2.getString("holdout_layer_param", "should_return_default"), "should_return_default");

        CompletableFuture<ConfigSyncDetails> details3Future = Statsig.syncConfigSpecs();
        ConfigSyncDetails details3 = details3Future.get();
        Assert.assertNotNull(details3);
        Assert.assertTrue(details3.getConfigSpecReady());  // Third try should succeed with 200 response
        Assert.assertNull(details3.getFailureDetails());  // No failure details expected for successful init
        Assert.assertNotNull(details3.getLcut());
        Assert.assertTrue(Statsig.checkGateSync(user, "always_on_gate"));
        Assert.assertTrue(Statsig.checkGateSync(user, "on_for_statsig_email"));
        Layer layer3 = Statsig.getLayerSync(user, "c_layer_with_holdout");
        Assert.assertEquals(layer3.getString("holdout_layer_param", "should_not_return_default"), "layer_default");
    }
}
