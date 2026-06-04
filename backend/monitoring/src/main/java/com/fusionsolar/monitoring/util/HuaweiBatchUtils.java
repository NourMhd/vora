package com.fusionsolar.monitoring.util;

import java.util.ArrayList;
import java.util.List;

public final class HuaweiBatchUtils {
    private HuaweiBatchUtils() {}

    public static <T> List<List<T>> partition(List<T> items, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += batchSize) {
            batches.add(items.subList(i, Math.min(i + batchSize, items.size())));
        }
        return batches;
    }

    public static void sleepBetweenBatches(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}