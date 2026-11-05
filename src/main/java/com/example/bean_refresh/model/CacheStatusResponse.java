package com.example.bean_refresh.model;

import java.time.LocalDateTime;

public record CacheStatusResponse(
        boolean hasCachedData,
        int cachedItemCount,
        LocalDateTime lastScheduledRun,
        String lastScheduledStatus,
        String lastErrorMessage,
        boolean simulationFailing,
        CaffeineStatsDto caffeineStats
) {
}
