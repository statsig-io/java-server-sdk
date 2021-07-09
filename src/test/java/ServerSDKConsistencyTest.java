import com.google.gson.Gson;
import org.junit.Test;
import server.APIDownloadedConfigs;
import server.ConfigEvaluation;
import server.Evaluator;
import server.StatsigUser;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServerSDKConsistencyTest {

    private static String CONFIG_SPEC = "{\"dynamic_configs\":[{\"name\":\"operating_system_config\",\"type\":\"dynamic_config\",\"salt\":\"35bc682e-975a-4ccf-ae51-e38b18132959\",\"enabled\":true,\"defaultValue\":{\"num\":13,\"bool\":true,\"str\":\"hello\",\"arr\":[\"hi\",\"there\"]},\"rules\":[]}],\"feature_gates\":[{\"name\":\"test_public\",\"type\":\"feature_gate\",\"salt\":\"64fa52a6-4195-4658-b124-aa0be3ff8860\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"6X3qJgyfwA81IJ2dxI7lYp\",\"passPercentage\":100,\"conditions\":[{\"type\":\"public\",\"targetValue\":null,\"operator\":null,\"field\":null,\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"6X3qJgyfwA81IJ2dxI7lYp\"}]},{\"name\":\"test_email\",\"type\":\"feature_gate\",\"salt\":\"44827b9b-c6dd-49f7-9017-f4ae2a09ef32\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"2D8ddk1zZqqaFbBjkOmCA3\",\"passPercentage\":100,\"conditions\":[{\"type\":\"user_field\",\"targetValue\":[\"@statsig.com\",\"@statsig.io\"],\"operator\":\"str_contains_any\",\"field\":\"email\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"2D8ddk1zZqqaFbBjkOmCA3\"}]},{\"name\":\"test_country\",\"type\":\"feature_gate\",\"salt\":\"6418e4dd-db03-4f40-8f34-4c7b82ecea08\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"1yhP7ww1Ot82rjqi1kh4eR\",\"passPercentage\":100,\"conditions\":[{\"type\":\"ip_based\",\"targetValue\":[\"US\",\"CA\"],\"operator\":\"any\",\"field\":\"country\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"1yhP7ww1Ot82rjqi1kh4eR\"}]},{\"name\":\"test_ua\",\"type\":\"feature_gate\",\"salt\":\"7b6c5d9a-3eac-4cb5-a9af-4d06a028e9df\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"7CRdApC3iHXzDFisD1d4bH\",\"passPercentage\":100,\"conditions\":[{\"type\":\"ua_based\",\"targetValue\":[\"iOS\"],\"operator\":\"any\",\"field\":\"os_name\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"7CRdApC3iHXzDFisD1d4bH\"},{\"name\":\"7CRdArh5L073X4Qoe98HuJ\",\"passPercentage\":100,\"conditions\":[{\"type\":\"ua_based\",\"targetValue\":[\"Chrome\"],\"operator\":\"any\",\"field\":\"browser_name\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"7CRdArh5L073X4Qoe98HuJ\"}]},{\"name\":\"test_country_partial\",\"type\":\"feature_gate\",\"salt\":\"884c77e0-04ec-40cd-ba96-71a0bf232896\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"7MaaS2nqRxuY3ovw5cOG3D\",\"passPercentage\":50,\"conditions\":[{\"type\":\"ip_based\",\"targetValue\":[\"US\"],\"operator\":\"any\",\"field\":\"country\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"7MaaS2nqRxuY3ovw5cOG3D\"}]},{\"name\":\"test_environment_tier\",\"type\":\"feature_gate\",\"salt\":\"51e3cfd7-2fc6-485d-a794-73303aef3813\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"uUegaWiu32iB1ShHaCxkv\",\"passPercentage\":100,\"conditions\":[{\"type\":\"environment_field\",\"targetValue\":[\"staging\",\"development\"],\"operator\":\"any\",\"field\":\"tier\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"uUegaWiu32iB1ShHaCxkv\"}]},{\"name\":\"test_version\",\"type\":\"feature_gate\",\"salt\":\"086df540-5c55-4c14-9fdb-b869f41712e0\",\"enabled\":true,\"defaultValue\":false,\"rules\":[{\"name\":\"5T0pMvNgNMVlysjjTaMfCy\",\"passPercentage\":100,\"conditions\":[{\"type\":\"user_field\",\"targetValue\":\"1.2.3.4\",\"operator\":\"version_lt\",\"field\":\"clientVersion\",\"additionalValues\":{}}],\"returnValue\":true,\"id\":\"5T0pMvNgNMVlysjjTaMfCy\"}]}],\"has_updates\":true,\"time\":1624069244242}";

    @Test
    public void getEnv() {
        String key = System.getenv("test_api_key");
        assertTrue(key == "secret-9IWfdzNwExEYHEW4YfOQcFZ4xreZyFkbOXHaNbPsMwW");
    }

    @Test
    public void testIP3Country() {
        Evaluator eval = new Evaluator();
        APIDownloadedConfigs configs = (new Gson()).fromJson(CONFIG_SPEC, APIDownloadedConfigs.class);
        eval.setDownloadedConfigs(configs);

        // IP Passes, but ID doesnt pass rollout percentage
        StatsigUser user = new StatsigUser("123");
        user.setIp("1.1.1.1");
        ConfigEvaluation evaluation = eval.checkGate(user, "test_country_partial");
        assertFalse(evaluation.getBooleanValue());
        assertFalse(evaluation.getFetchFromServer());

        // IP Passes and ID passes rollout percentage
        user.setUserID("4");
        evaluation = eval.checkGate(user, "test_country_partial");
        assertTrue(evaluation.getBooleanValue());
        assertFalse(evaluation.getFetchFromServer());

        // IP Does not pass and ID passes rollout percentage
        user.setIp("27.62.93.211");
        evaluation = eval.checkGate(user, "test_country_partial");
        assertFalse(evaluation.getBooleanValue());
        assertFalse(evaluation.getFetchFromServer());
    }
}
