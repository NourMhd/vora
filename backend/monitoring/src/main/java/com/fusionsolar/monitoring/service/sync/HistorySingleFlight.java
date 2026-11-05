package com.fusionsolar.monitoring.service.sync;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import com.fusionsolar.monitoring.dto.HistoryPointDto;

/** Coalesces overlapping requests. Completed values remain only during the call. */
public final class HistorySingleFlight {
    private static final ConcurrentHashMap<String, CompletableFuture<List<HistoryPointDto>>> active = new ConcurrentHashMap<>();
    private HistorySingleFlight() { }
    public static List<HistoryPointDto> execute(String key, Supplier<List<HistoryPointDto>> loader) {
        CompletableFuture<List<HistoryPointDto>> own = new CompletableFuture<>();
        CompletableFuture<List<HistoryPointDto>> existing = active.putIfAbsent(key, own);
        if (existing != null) return existing.join();
        try {
            List<HistoryPointDto> result = loader.get();
            own.complete(result);
            return result;
        } catch (RuntimeException | Error error) {
            own.completeExceptionally(error);
            throw error;
        } finally { active.remove(key, own); }
    }
}
