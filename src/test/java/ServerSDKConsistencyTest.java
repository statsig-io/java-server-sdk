import com.google.gson.Gson;
import org.junit.Before;
import org.junit.Test;
import server.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class ServerSDKConsistencyTest {
    APITestDataSet[] prodTestData;
    APITestDataSet[] stagingTestData;

    @Before
    public void setUp() throws Exception {
        String secret = System.getenv("test_api_key");
        System.out.println("secret is: " + secret == null ? "null" : secret);

        StringBuilder sb = new StringBuilder();
        Map<String, String> env = System.getenv();
        for (String key : env.keySet()) {
            sb.append(key + ": " + env.get(key)  + "\n");
        }

        System.out.println(sb.toString());
        if (secret == null || secret.length() == 0) {
            try {
                secret = Files.readString(Paths.get(
                        Paths.get("").toAbsolutePath()
                                + "/../ops/secrets/prod_keys/statsig-rulesets-eval-consistency-test-secret.key"),
                        StandardCharsets.US_ASCII);
            } catch (Exception e) {
                throw new Exception("THIS TEST IS EXPECTED TO FAIL FOR NON-STATSIG EMPLOYEES! If this is the" +
                        "only test failing, please proceed to submit a pull request. If you are a Statsig employee," +
                        "chat with jkw.");
            }

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.statsig.com/v1/rulesets_e2e_test"))
                    .headers("STATSIG-API-KEY", secret, "Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            prodTestData = (new Gson()).fromJson(response.body(), APIEvaluationConsistencyTestData.class).getData();

            request = HttpRequest.newBuilder()
                    .uri(URI.create("https://latest.api.statsig.com/v1/rulesets_e2e_test"))
                    .headers("STATSIG-API-KEY", secret, "Content-Type", "application/json; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            stagingTestData = (new Gson()).fromJson(response.body(), APIEvaluationConsistencyTestData.class).getData();

            Future initFuture = StatsigServer.initializeAsync(secret);
            initFuture.get();
        }
    }

    public void testConsistency(APITestDataSet[] data) throws ExecutionException, InterruptedException {
        for (APITestDataSet d: data) {
            StatsigUser user = d.getUser();
            for (Map.Entry<String, Boolean> entry : d.getGates().entrySet()) {
                Future<Boolean> gate = StatsigServer.checkGateAsync(user, entry.getKey());
                assertEquals(gate.get(), entry.getValue());
            }

            for (Map.Entry<String, APIConfigData> entry : d.getConfigs().entrySet()) {
                Future<DynamicConfig> sdkConfig = StatsigServer.getConfigAsync(user, entry.getKey());
                assertTrue(sdkConfig.get().getValue().equals(entry.getValue().getValue()));
                assertTrue(sdkConfig.get().getRuleID().equals(entry.getValue().getRuleID()));
            }
        }
    }

    @Test
    public void testProdConsistency() throws ExecutionException, InterruptedException {
        testConsistency(prodTestData);
    }

    @Test
    public void testStagingConsistency() throws ExecutionException, InterruptedException {
        testConsistency(stagingTestData);
    }
}
