import org.junit.Test;
import server.*;

import java.util.Collections;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class StatsigTest {

    @Test
    public void testInitialize() {
        try {
            Future initFuture = StatsigServer.initializeAsync("");
            initFuture.get();
            fail("Should throw an exception for invalid server secret");
        } catch (Exception e) {}

        try {
            Future initFuture = StatsigServer.initializeAsync("client-123");
            initFuture.get();
            fail("Should throw an exception for invalid server secret");
        } catch (Exception e) {}

        try {
            Future initFuture = StatsigServer.initializeAsync("secret-9IWfdzNwExEYHEW4YfOQcFZ4xreZyFkbOXHaNbPsMwW");
            initFuture.get();

            Future<Boolean> noGateFuture = StatsigServer.checkGateAsync(null, "i_do_not_exist");
            assertFalse(noGateFuture.get());

            Future<DynamicConfig> noConfigFuture = StatsigServer.getConfigAsync(null, "i_do_not_exist");
            assertTrue(noConfigFuture.get().getValue().isEmpty());

            Future<Boolean> checkPublicGateFuture = StatsigServer.checkGateAsync(null, "test_public");
            assertEquals(true, checkPublicGateFuture.get());

            StatsigUser user = new StatsigUser();
            Future<Boolean> checkEmailGateFuture = StatsigServer.checkGateAsync(user, "test_email");
            assertFalse(checkEmailGateFuture.get());

            user.setEmail("tore@statsig.com");
            checkEmailGateFuture = StatsigServer.checkGateAsync(user, "test_email");
            assertTrue(checkEmailGateFuture.get());

            user.setUserID("123");
            user.setCustom(Collections.singletonMap("country", "US"));
            Future<Boolean> usCountryGateFuture = StatsigServer.checkGateAsync(user, "test_country_partial");
            assertFalse(usCountryGateFuture.get());

            user.setUserID("4");
            usCountryGateFuture = StatsigServer.checkGateAsync(user, "test_country_partial");
            assertTrue(usCountryGateFuture.get());

            Future<DynamicConfig> checkOsConfig = StatsigServer.getConfigAsync(user, "operating_system_config");
            DynamicConfig config = checkOsConfig.get();
            assertTrue(config.getBoolean("bool", false));
            assertEquals(13, config.getInt("num", 7));
            assertEquals(13.0, config.getDouble("num", 7.0), 0);
            assertEquals("hello", config.getString("str", ""));
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @Test
    public void testEnvironment() {
        try {
            StatsigOptions options = new StatsigOptions();
            options.setTier(Tier.DEVELOPMENT);
            ServerDriver driver = new ServerDriver("secret-9IWfdzNwExEYHEW4YfOQcFZ4xreZyFkbOXHaNbPsMwW", options);
            driver.initializeAsync().get();
            Future<Boolean> environmentGate = driver.checkGateAsync(null, "test_environment_tier");
            assertTrue(environmentGate.get());

            options.setTier(Tier.PRODUCTION);
            driver = new ServerDriver("secret-9IWfdzNwExEYHEW4YfOQcFZ4xreZyFkbOXHaNbPsMwW", options);
            driver.initializeAsync().get();
            environmentGate = driver.checkGateAsync(null, "test_environment_tier");
            assertFalse(environmentGate.get());
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }
}
