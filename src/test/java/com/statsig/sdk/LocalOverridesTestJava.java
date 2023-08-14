package com.statsig.sdk;

import kotlin.jvm.JvmStatic;
import org.junit.*;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class LocalOverridesTestJava {
   private Evaluator evaluator;
   private StatsigServer driver;
   private StatsigUser userA = new StatsigUser("user-a");
   private StatsigUser userB = new StatsigUser(new HashMap<String, String>() {{
       put("customID", "abc123");
   }});

   @Before
   @JvmStatic
   public void setUp() throws Exception {
       StatsigOptions options = new StatsigOptions();
       options.setLocalMode(true);

       driver = StatsigServer.create("secret-local", options);
       Future initFuture = driver.initializeAsync();
       initFuture.get();

       evaluator = TestUtilJava.getEvaluatorFromStatsigServer(driver);
   }

   @Test
   public void testGateOverrides() throws Exception {
       assertEquals(userA.getCopyForLogging$StatsigSDK().getUserID(), "user-a");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().get("customID"), "abc123");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().size(), 1);

       assertFalse(driver.checkGateAsync(userA, "override_me").get());

       evaluator.overrideGate("override_me", true);
       assertTrue(driver.checkGateAsync(userA, "override_me").get());
       assertTrue(driver.checkGateAsync(userB, "override_me").get());

       evaluator.overrideGate("override_me", false);
       assertFalse(driver.checkGateAsync(userB, "override_me").get());
   }

   @Test
   public void testConfigOverrides() throws Exception {
       assertEquals(userA.getCopyForLogging$StatsigSDK().getUserID(), "user-a");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().get("customID"), "abc123");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().size(), 1);

       Map<String, String> emptyMap = new HashMap<>();

       assertEquals(driver.getConfigAsync(userA, "override_me").get().getValue(), emptyMap);

       Map<String, String> overriddenValue = new HashMap<>();
       overriddenValue.put("hello", "its me");
       evaluator.overrideConfig("override_me", overriddenValue);

       assertEquals(driver.getConfigAsync(userA, "override_me").get().getValue(), overriddenValue);

       overriddenValue.put("hello", "its no longer me");
       evaluator.overrideConfig("override_me", overriddenValue);
       assertEquals(driver.getConfigAsync(userB, "override_me").get().getValue(), overriddenValue);

       evaluator.overrideConfig("override_me", emptyMap);
       assertEquals(driver.getConfigAsync(userB, "override_me").get().getValue(), emptyMap);
   }
}
