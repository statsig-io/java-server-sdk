import com.google.gson.Gson;
import com.statsig.sdk.*;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.SupervisorKt;
import kotlinx.coroutines.test.TestCoroutineScope;
import kotlinx.coroutines.test.TestCoroutineScopeKt;
import org.junit.Before;
import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class ServerSDKConsistencyTest {
    String secret;

    @Before
    public void setUp() throws Exception {
        secret = System.getenv("test_api_key");
        if (secret == null || secret.length() == 0) {
            try {
                secret = Files.readString(Paths.get(
                        Paths.get("").toAbsolutePath()
                                + "/../../ops/secrets/prod_keys/statsig-rulesets-eval-consistency-test-secret.key"),
                        StandardCharsets.US_ASCII);
            } catch (Exception e) {
                throw new Exception("THIS TEST IS EXPECTED TO FAIL FOR NON-STATSIG EMPLOYEES! If this is the" +
                        "only test failing, please proceed to submit a pull request. If you are a Statsig employee," +
                        "chat with jkw.");
            }
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

        APITestDataSet[] data = (new Gson()).fromJson(response.body(), APIEvaluationConsistencyTestData.class).getData();
        CoroutineScope scope = TestCoroutineScopeKt.TestCoroutineScope(EmptyCoroutineContext.INSTANCE);
        ServerDriver driver = new ServerDriver(secret, new StatsigOptions(api), scope);
        Future initFuture = driver.initializeAsync();
        initFuture.get();

        for (APITestDataSet d: data) {
            StatsigUser user = d.getUser();
            for (Map.Entry<String, Boolean> entry : d.getGates().entrySet()) {
                Future<Boolean> gate = driver.checkGateAsync(user, entry.getKey());
                assertEquals(entry.getKey() + " for " + user.toString(), entry.getValue(), gate.get());
            }

            for (Map.Entry<String, APIConfigData> entry : d.getConfigs().entrySet()) {
                Future<DynamicConfig> sdkConfig = driver.getConfigAsync(user, entry.getKey());
                assertTrue("Config value mismatch for " + entry.getKey()+ " for " + user.toString(), sdkConfig.get().getValue().equals(entry.getValue().getValue()));
                assertTrue("RuleID mismatch for " + entry.getKey() + " for " + user.toString(), sdkConfig.get().getRuleID().equals(entry.getValue().getRuleID()));
            }
        }
    }

    @Test
    public void testProd() throws Exception {
        testConsistency("https://api.statsig.com/v1");
    }

    @Test
    public void testStaging() throws Exception {
        testConsistency("https://latest.api.statsig.com/v1");
    }

    @Test
    public void testUSWest() throws Exception {
        testConsistency("https://us-west-2.api.statsig.com/v1");
    }

    @Test
    public void testUSEast() throws Exception {
        testConsistency("https://us-east-2.api.statsig.com/v1");
    }

    @Test
    public void testAPSouth() throws Exception {
        testConsistency("https://ap-south-1.api.statsig.com/v1");
    }
}
