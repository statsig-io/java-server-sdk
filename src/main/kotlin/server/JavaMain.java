package server;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class JavaMain {
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        Future initFuture = StatsigServer.initializeAsync("secret-wzolRc4LHvErMJsvMTlzVEagE1YoKlm9n53OixNnLAv", new StatsigOptions());
        initFuture.get();
        Future gateFuture = StatsigServer.checkGateAsync("always_on_gate");
        gateFuture.get();
    }
}
