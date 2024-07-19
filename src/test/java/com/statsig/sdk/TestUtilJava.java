package com.statsig.sdk;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

public class TestUtilJava {
    static public Evaluator getEvaluatorFromStatsigServer(StatsigServer driver) throws NoSuchFieldException, IllegalAccessException {
        Field privateEvaluatorField = driver.getClass().getDeclaredField("evaluator");
        privateEvaluatorField.setAccessible(true);
        return (Evaluator) privateEvaluatorField.get(driver);
    }
    static public SpecStore getSpecStoreFromStatsigServer(StatsigServer driver) throws NoSuchFieldException, IllegalAccessException {
        Evaluator eval = getEvaluatorFromStatsigServer(driver);
        Field privateSpecStoreField = eval.getClass().getDeclaredField("specStore");
        privateSpecStoreField.setAccessible(true);
        return (SpecStore) privateSpecStoreField.get(eval);
    }

    static public EvaluationReason getInitReasonFromSpecStore(SpecStore specStore) throws NoSuchFieldException, IllegalAccessException {
        Field privateSpecStoreField = specStore.getClass().getDeclaredField("initReason");
        privateSpecStoreField.setAccessible(true);
        return (EvaluationReason) privateSpecStoreField.get(specStore);
    }
    static public void setInitReasonFromSpecStore(SpecStore specStore, EvaluationReason reason) throws NoSuchFieldException, IllegalAccessException {
        Field privateSpecStoreField = specStore.getClass().getDeclaredField("initReason");
        privateSpecStoreField.setAccessible(true);
        privateSpecStoreField.set(specStore, reason);
    }

    static public MockResponse mockLogEventEndpoint(RecordedRequest request, CompletableFuture<LogEventInput> eventLogInputCompletable) {
        Buffer body = request.getBody();
        String contentEncoding = request.getHeaders().get("Content-Encoding");
        String logBody = "";
        if (contentEncoding != null && contentEncoding.equals("gzip")) {
            logBody = decompress(body);
        } else {
            logBody = body.readUtf8();
        }
        Gson gson = new Gson();
        eventLogInputCompletable.complete(gson.fromJson(logBody, LogEventInput.class));
        return new MockResponse().setResponseCode(200);
    }

    private static String decompress(Buffer body) {
        try {
            GZIPInputStream inputStream = new GZIPInputStream(body.inputStream());
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            inputStream.close();
            return outputStream.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
