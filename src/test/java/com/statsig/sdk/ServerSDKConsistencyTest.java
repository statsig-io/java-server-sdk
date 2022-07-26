package com.statsig.sdk;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;

public class ServerSDKConsistencyTest {
    String secret;
    private Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

    @Before
    public void setUp() throws Exception {
        secret = System.getenv("test_api_key");
        if (secret == null || secret.length() == 0) {
            throw new Exception("THIS TEST IS EXPECTED TO FAIL FOR NON-STATSIG EMPLOYEES! If this is the" +
                    "only test failing, please proceed to submit a pull request. If you are a Statsig employee," +
                    "chat with jkw.");
        }
    }

    public void testConsistency(String api) throws Exception {
        System.out.println("Testing for " + api);
        HttpClient httpClient = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(api + "/rulesets_e2e_test"))
                .headers("STATSIG-API-KEY", secret, "Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        APITestDataSet[] data = gson.fromJson(response.body(), APIEvaluationConsistencyTestData.class).getData();
        StatsigServer driver = StatsigServer.create(secret, new StatsigOptions(api));
        Future initFuture = driver.initializeAsync();
        initFuture.get();

        Field privateEvaluatorField = StatsigServerImpl.class.getDeclaredField("configEvaluator");
        privateEvaluatorField.setAccessible(true);

        Evaluator evaluator = (Evaluator) privateEvaluatorField.get(driver);

        for (APITestDataSet d: data) {
            StatsigUser user = d.getUser();
            for (Map.Entry<String, APIFeatureGate> entry : d.getGates().entrySet()) {
                ConfigEvaluation sdkResult = evaluator.checkGate(user, entry.getKey());
                APIFeatureGate serverResult = entry.getValue();
                assertEquals("Value mismatch for gate " + entry.getKey() + " for user" + user.toString(), serverResult.getValue(),
                        sdkResult.getBooleanValue());
                assertEquals("Rule ID mismatch for gate " + entry.getKey(), serverResult.getRuleID(),
                        sdkResult.getRuleID());
                assertEquals("Secondary exposure mismatch for gate " + entry.getKey(),
                        gson.toJson(serverResult.getSecondaryExposures()), gson.toJson(sdkResult.getSecondaryExposures()));

                Future<Boolean> sdkValue = driver.checkGateAsync(user, entry.getKey());
                assertEquals("Server driver value mismatch for gate " + entry.getKey(), serverResult.getValue(), sdkValue.get());
            }

            for (Map.Entry<String, APIDynamicConfig> entry : d.getConfigs().entrySet()) {
                ConfigEvaluation sdkResult = evaluator.getConfig(user, entry.getKey());
                APIDynamicConfig serverResult = entry.getValue();
                assertEquals("Value mismatch for config " + entry.getKey() + " for user" + user.toString(),
                        gson.toJson(serverResult.getValue()), gson.toJson(sdkResult.getJsonValue()));
                assertEquals("Rule ID mismatch for config " + entry.getKey(), serverResult.getRuleID(),
                        sdkResult.getRuleID());
                assertEquals("Secondary exposure mismatch for config " + entry.getKey(),
                        gson.toJson(serverResult.getSecondaryExposures()), gson.toJson(sdkResult.getSecondaryExposures()));

                Future<DynamicConfig> sdkValue = driver.getConfigAsync(user, entry.getKey());
                assertEquals("Server driver value mismatch for config " + entry.getKey(),
                        gson.toJson(serverResult.getValue()), gson.toJson(sdkValue.get().getValue()));
            }

            for (Map.Entry<String, APIDynamicConfig> entry : d.getLayers().entrySet()) {
                ConfigEvaluation sdkResult = evaluator.getLayer(user, entry.getKey());
                APIDynamicConfig serverResult = entry.getValue();
                assertEquals("Value mismatch for layer " + entry.getKey() + " for user" + user.toString(),
                        gson.toJson(serverResult.getValue()), gson.toJson(sdkResult.getJsonValue()));
                assertEquals("Rule ID mismatch for layer " + entry.getKey(), serverResult.getRuleID(),
                        sdkResult.getRuleID());
                assertEquals("Secondary exposure mismatch for layer " + entry.getKey(),
                        gson.toJson(serverResult.getSecondaryExposures()), gson.toJson(sdkResult.getSecondaryExposures()));
                assertEquals("Undelegated secondary exposure mismatch for config " + entry.getKey(),
                        gson.toJson(serverResult.getUndelegatedSecondaryExposures()), gson.toJson(sdkResult.getUndelegatedSecondaryExposures()));

                Future<Layer> sdkValue = driver.getLayerAsync(user, entry.getKey());
                for (Map.Entry<String, Object> valEntry : serverResult.getValue().entrySet()) {
                    Object sdkVal = "ERR";
                    if (valEntry.getValue() instanceof Number) {
                        sdkVal = sdkValue.get().getInt(valEntry.getKey(), -1);
                    } else if (valEntry.getValue() instanceof Boolean) {
                        sdkVal = sdkValue.get().getBoolean(valEntry.getKey(), false);
                    } else if (valEntry.getValue() instanceof List) {
                        sdkVal = sdkValue.get().getArray(valEntry.getKey(), new Object[]{});
                    } else if (valEntry.getValue() instanceof Map) {
                        sdkVal = sdkValue.get().getDictionary(valEntry.getKey(), new HashMap<>());
                    } else {
                        sdkVal = sdkValue.get().getString(valEntry.getKey(), "ERR");
                    }

                    assertEquals("Server driver value mismatch for layer " + entry.getKey(),
                            gson.toJson(valEntry.getValue()), gson.toJson(sdkVal));
                }
            }
        }
        driver.shutdown();
    }

    @Test
    public void testProd() throws Exception {
       testConsistency("https://statsigapi.net/v1");
    }

    @Test
    public void testStaging() throws Exception {
        testConsistency("https://staging.statsigapi.net/v1");
    }

}
