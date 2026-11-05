package com.example.bean_refresh.model;

public record CaffeineStatsDto(
        long hitCount,
        long missCount,
        double hitRate,
        long evictionCount
) {
}

