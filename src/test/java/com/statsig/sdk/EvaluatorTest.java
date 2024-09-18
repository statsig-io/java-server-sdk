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

@SuppressWarnings("KotlinInternalInJava")
public class EvaluatorTest {
    private Gson gson = new GsonBuilder().setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();
    private InputStream CONFIG_SPECS = StatsigE2ETest.class.getResourceAsStream("/evaluator_test_config_specs.json");

    @Test
    public void testIP3Country() throws NoSuchFieldException, IllegalAccessException, ExecutionException, InterruptedException {
        StatsigServer driver = StatsigServer.create();
        driver.initializeAsync("secret-local", new StatsigOptions()).get();

        SpecStore specStore = TestUtilJava.getSpecStoreFromStatsigServer(driver);
        Evaluator eval = TestUtilJava.getEvaluatorFromStatsigServer(driver);

        Reader reader = new InputStreamReader(CONFIG_SPECS);
        APIDownloadedConfigs configs = gson.fromJson(reader, APIDownloadedConfigs.class);
        specStore.setDownloadedConfigs(configs,false);
        TestUtilJava.setInitReasonFromSpecStore(specStore, EvaluationReason.NETWORK);

        // IP Passes, but ID doesnt pass rollout percentage
        StatsigUser user = new StatsigUser("123");
        user.setIp("1.1.1.1");
        EvaluationContext context = new EvaluationContext(user);
        eval.checkGate(context, "test_country_partial");
        assertFalse(context.getEvaluation().getBooleanValue());

        // IP Passes and ID passes rollout percentage
        user.setUserID("4");
        eval.checkGate(context, "test_country_partial");
        assertTrue(context.getEvaluation().getBooleanValue());

        // IP Does not pass and ID passes rollout percentage
        user.setIp("27.62.93.211");
        eval.checkGate(context, "test_country_partial");
        assertFalse(context.getEvaluation().getBooleanValue());
    }

    private class CaseSensitiveTestCase {
        public String name;
        public Object first;
        public String second;
        public boolean ignoreCase;
        public boolean expectedResult;

        public CaseSensitiveTestCase (String name, Object one, String two, boolean ignoreCase, boolean result) {
            this.name = name;
            this.first = one;
            this.second = two;
            this.ignoreCase = ignoreCase;
            this.expectedResult = result;
        }
    }

    private CaseSensitiveTestCase[] testCases = new CaseSensitiveTestCase[]{
        new CaseSensitiveTestCase("case insensitive matching strings in array", new String[]{"my_string"}, "my_string", true, true),
        new CaseSensitiveTestCase("case insensitive nonmatching strings in array", new String[]{"my_strinG"}, "my_string", true, true),
        new CaseSensitiveTestCase("case sensitive matching strings in array", new String[]{"my_string"}, "my_string", false, true),
        new CaseSensitiveTestCase("case sensitive nonmatching strings in array", new String[]{"my_strinG"}, "my_string", false, false),
    };

    @Test
    public void testCaseSensitivity() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException, NoSuchFieldException, ExecutionException, InterruptedException {
        StatsigServer driver = StatsigServer.create();
        driver.initializeAsync("secret-local", new StatsigOptions()).get();

        Evaluator eval = TestUtilJava.getEvaluatorFromStatsigServer(driver);

        Method privateContainsMethod = Evaluator.class.
            getDeclaredMethod("contains", Object.class, Object.class, boolean.class);

        privateContainsMethod.setAccessible(true);

        for (CaseSensitiveTestCase testCase : this.testCases) {
            boolean result = (boolean) privateContainsMethod.invoke(eval, testCase.first, testCase.second, testCase.ignoreCase);
            if (testCase.expectedResult) {
                assertTrue(testCase.name, result);
            } else {
                assertFalse(testCase.name, result);
            }
        }
    }
}
