# Statsig Java (Kotlin) Server SDK

This SDK is intended for use by Java/Kotlin in multi-user/server side environments.

```java
Future initFuture = StatsigServer.initializeAsync("<server_secret>");
initFuture.get();
// Now you can check gates, get configs, log events

StatsigUser user = new StatsigUser();
user.email = "address@domain.com"
Future<Boolean> featureOn = StatsigServer.checkGateAsync(user, "<gate_name>");
Boolean isFeatureOn = featureOn.get()
```

```kotlin
val initialize = CoroutineScope(Dispatchers.Default).async {
    StatsigServer.initialize("<server_secret>")
    val featureOn = StatsigServer.checkGate(StatsigUser(), "<gate_name>")
}
initialize.await()

// Now you can check gates, get configs, log events

for (i in 1..501) {
    StatsigServer.logEvent(null, "tore123", i * 1.0, mapOf("test" to "test2"))
}
```
