package com.statsig.sdk;

import java.lang.reflect.Field;

public class TestUtilJava {
    static public Evaluator getEvaluatorFromStatsigServer(StatsigServer driver) throws NoSuchFieldException, IllegalAccessException {
        Field privateEvaluatorField = driver.getClass().getDeclaredField("configEvaluator");
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
}
