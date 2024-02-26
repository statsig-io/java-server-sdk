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
   private Evaluator evaluator1; // for multi-instance test
   private Evaluator evaluator2; // for multi-instance test
   private StatsigServer driver;
   private StatsigServer instance1; // for multi-instance test
   private StatsigServer instance2; // for multi-instance test
   private StatsigUser userA = new StatsigUser("user-a");
   private StatsigUser userB = new StatsigUser(new HashMap<String, String>() {{
       put("customID", "abc123");
   }});

   @Before
   @JvmStatic
   public void setUp() throws Exception {
       StatsigOptions options = new StatsigOptions();
       options.setLocalMode(true);

       driver = StatsigServer.create();
       Future initFuture = driver.initializeAsync("secret-local", options);
       initFuture.get();

       instance1 = StatsigServer.create();
       instance1.initializeAsync("secret-local-1", options).get();

       instance2 = StatsigServer.create();
       instance2.initializeAsync("secret-local-2", options).get();

       evaluator = TestUtilJava.getEvaluatorFromStatsigServer(driver);
       evaluator1 = TestUtilJava.getEvaluatorFromStatsigServer(instance1);
       evaluator2 = TestUtilJava.getEvaluatorFromStatsigServer(instance2);
   }

   @Test
   public void testGateOverrides() throws Exception {
       assertEquals(userA.getCopyForLogging$StatsigSDK().getUserID(), "user-a");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().get("customID"), "abc123");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().size(), 1);

       assertFalse(driver.checkGateAsync(userA, "override_me").get());
       assertFalse(driver.checkGateSync(userA, "override_me", new CheckGateOptions()));
       assertFalse(driver.checkGateSync(userA, "override_me", null));
       assertFalse(driver.checkGateSync(userA, "override_me", new CheckGateOptions(true)));
       assertFalse(instance1.checkGateAsync(userA, "override_me").get());
       assertFalse(instance1.checkGateSync(userA, "override_me", null));
       assertFalse(instance2.checkGateAsync(userA, "override_me").get());
       assertFalse(instance2.checkGateSync(userA, "override_me", null));


       evaluator.overrideGate("override_me", true);
       evaluator1.overrideGate("override_me", true);
       evaluator2.overrideGate("override_me", true);
       assertTrue(driver.checkGateAsync(userA, "override_me").get());
       assertTrue(driver.checkGateAsync(userB, "override_me").get());
       assertTrue(driver.checkGateSync(userA, "override_me", null));
       assertTrue(driver.checkGateSync(userA, "override_me", new CheckGateOptions()));
       assertTrue(driver.checkGateSync(userB, "override_me", new CheckGateOptions(true)));

       assertTrue(instance1.checkGateAsync(userA, "override_me").get());
       assertTrue(instance1.checkGateAsync(userB, "override_me").get());
       assertTrue(instance1.checkGateSync(userA, "override_me", null));
       assertTrue(instance1.checkGateSync(userB, "override_me", null));

       assertTrue(instance2.checkGateAsync(userA, "override_me").get());
       assertTrue(instance2.checkGateAsync(userB, "override_me").get());
       assertTrue(instance2.checkGateSync(userA, "override_me", null));
       assertTrue(instance2.checkGateSync(userB, "override_me", null));

       evaluator.overrideGate("override_me", false);
       evaluator1.overrideGate("override_me", false);
       evaluator2.overrideGate("override_me", false);
       assertFalse(driver.checkGateAsync(userB, "override_me").get());
       assertFalse(driver.checkGateSync(userA, "override_me", null));
       assertFalse(driver.checkGateSync(userB, "override_me", new CheckGateOptions()));
       assertFalse(driver.checkGateSync(userB, "override_me", new CheckGateOptions(true)));

       assertFalse(instance1.checkGateAsync(userA, "override_me").get());
       assertFalse(instance1.checkGateSync(userA, "override_me", null));
       assertFalse(instance2.checkGateAsync(userA, "override_me").get());
       assertFalse(instance2.checkGateSync(userA, "override_me", null));
   }

   @Test
   public void testConfigOverrides() throws Exception {
       assertEquals(userA.getCopyForLogging$StatsigSDK().getUserID(), "user-a");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().get("customID"), "abc123");
       assertEquals(userB.getCopyForLogging$StatsigSDK().getCustomIDs().size(), 1);

       Map<String, String> emptyMap = new HashMap<>();

       assertEquals(driver.getConfigAsync(userA, "override_me").get().getValue(), emptyMap);
       assertEquals(driver.getConfigSync(userA, "override_me", null).getValue(), emptyMap);
       assertEquals(driver.getConfigSync(userA, "override_me", new GetConfigOptions()).getValue(), emptyMap);
       assertEquals(driver.getConfigSync(userA, "override_me", new GetConfigOptions(true)).getValue(), emptyMap);

       assertEquals(instance1.getConfigAsync(userA, "override_me").get().getValue(), emptyMap);
       assertEquals(instance1.getConfigSync(userA, "override_me", null).getValue(), emptyMap);

       assertEquals(instance2.getConfigAsync(userA, "override_me").get().getValue(), emptyMap);
       assertEquals(instance2.getConfigSync(userA, "override_me", null).getValue(), emptyMap);

       Map<String, String> overriddenValue = new HashMap<>();
       overriddenValue.put("hello", "its me");
       evaluator.overrideConfig("override_me", overriddenValue);
       evaluator1.overrideConfig("override_me", overriddenValue);
       evaluator2.overrideConfig("override_me", overriddenValue);

       assertEquals(driver.getConfigAsync(userA, "override_me").get().getValue(), overriddenValue);
       assertEquals(driver.getConfigSync(userA, "override_me", null).getValue(), overriddenValue);
       assertEquals(driver.getConfigSync(userA, "override_me", new GetConfigOptions()).getValue(), overriddenValue);
       assertEquals(driver.getConfigSync(userA, "override_me", new GetConfigOptions(true)).getValue(), overriddenValue);

       overriddenValue.put("hello", "its no longer me");
       evaluator.overrideConfig("override_me", overriddenValue);
       evaluator1.overrideConfig("override_me", overriddenValue);
       evaluator2.overrideConfig("override_me", overriddenValue);

       assertEquals(driver.getConfigAsync(userB, "override_me").get().getValue(), overriddenValue);
       assertEquals(driver.getConfigSync(userB, "override_me", new GetConfigOptions()).getValue(), overriddenValue);
       assertEquals(instance1.getConfigAsync(userB, "override_me").get().getValue(), overriddenValue);
       assertEquals(instance1.getConfigSync(userB, "override_me", null).getValue(), overriddenValue);
       assertEquals(instance2.getConfigAsync(userB, "override_me").get().getValue(), overriddenValue);
       assertEquals(instance2.getConfigSync(userB, "override_me", null).getValue(), overriddenValue);

       evaluator.overrideConfig("override_me", emptyMap);
       evaluator1.overrideConfig("override_me", emptyMap);
       evaluator2.overrideConfig("override_me", emptyMap);

       assertEquals(driver.getConfigAsync(userB, "override_me").get().getValue(), emptyMap);
       assertEquals(driver.getConfigSync(userB, "override_me", null).getValue(), emptyMap);
       assertEquals(driver.getConfigSync(userB, "override_me", new GetConfigOptions()).getValue(), emptyMap);
       assertEquals(driver.getConfigSync(userB, "override_me", new GetConfigOptions(true)).getValue(), emptyMap);

       assertEquals(instance1.getConfigAsync(userB, "override_me").get().getValue(), emptyMap);
       assertEquals(instance1.getConfigSync(userB, "override_me", null).getValue(), emptyMap);

       assertEquals(instance2.getConfigAsync(userB, "override_me").get().getValue(), emptyMap);
       assertEquals(instance2.getConfigSync(userB, "override_me", null).getValue(), emptyMap);
   }
}
