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
}
