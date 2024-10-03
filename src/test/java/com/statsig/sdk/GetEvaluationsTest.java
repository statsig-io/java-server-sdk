package com.statsig.sdk;

import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import org.junit.Test;

import static org.junit.Assert.*;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutionException;
import java.util.Map;

@SuppressWarnings("KotlinInternalInJava")
public class GetEvaluationsTest {
    private Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private InputStream CONFIG_SPECS = StatsigE2ETest.class.getResourceAsStream("/evaluator_test_config_specs.json");

    @Test
    public void testGetEvaluations()
            throws NoSuchFieldException, IllegalAccessException, ExecutionException, InterruptedException {
        StatsigServer driver = StatsigServer.create();
        driver.initializeAsync("secret-local", new StatsigOptions()).get();

        SpecStore specStore = TestUtilJava.getSpecStoreFromStatsigServer(driver);
        Evaluator eval = TestUtilJava.getEvaluatorFromStatsigServer(driver);

        Reader reader = new InputStreamReader(CONFIG_SPECS);
        APIDownloadedConfigs configs = gson.fromJson(reader, APIDownloadedConfigs.class);
        specStore.setDownloadedConfigs(configs, false);
        TestUtilJava.setInitReasonFromSpecStore(specStore, EvaluationReason.NETWORK);

        StatsigUser user = new StatsigUser("123");

        Map<String, Object> values = driver.getEvaluationsForUser(user, HashAlgo.DJB2, null);
        assertTrue(values.containsKey("feature_gates"));
        assertTrue(values.containsKey("dynamic_configs"));
        assertTrue(values.containsKey("layer_configs"));
        assertTrue(values.containsKey("exposures"));
        assertTrue(values.containsKey("hash_used"));
        Map gates = (Map) values.get("feature_gates");
        assertTrue(gates instanceof Map && gates.containsKey("3968762550"));
    }
}
