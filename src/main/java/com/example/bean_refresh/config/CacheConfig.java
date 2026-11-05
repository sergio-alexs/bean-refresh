package com.example.bean_refresh.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.concurrent.TimeUnit;

/**
 * Configuración central de infraestructura:
 * - @EnableCaching: Habilita el soporte de caché de Spring Framework (@Cacheable, @CachePut, @CacheEvict).
 * - @EnableScheduling: Permite la ejecución de tareas periódicas en segundo plano (@Scheduled).
 * - @EnableRetry: Habilita la tolerancia a fallos con reintentos declarativos (@Retryable).
 */
@Configuration
@EnableCaching
@EnableScheduling
@EnableRetry
public class CacheConfig {

    /**
     * Nombre identificador del contenedor de caché para el catálogo de productos.
     */
    public static final String PRODUCTS_CACHE = "products";

    /**
     * Tiempo de vida (TTL) pasivo de respaldo configurable mediante 'app.cache.ttl-hours' (por defecto 4 horas).
     */
    @Value("${app.cache.ttl-hours:4}")
    private long ttlHours;

    /**
     * Configuración del motor de caché Caffeine:
     * 1. maximumSize(500): Limita a 500 entradas en memoria para evitar problemas de OutOfMemory (OOM).
     * 2. expireAfterWrite(ttlHours, TimeUnit.HOURS): TTL pasivo de respaldo. Si la tarea programada
     *    fallara de forma inesperada o se congelara el hilo, Caffeine garantiza que los datos expiren
     *    automáticamente a las 4 horas.
     * 3. recordStats(): Activa el registro interno de métricas (aciertos, fallos, tasa de éxito y desalojos).
     */
    @Bean
    public Caffeine<Object, Object> caffeineConfig() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .recordStats();
    }

    /**
     * Define el CacheManager que enlaza la abstracción de caché de Spring con el motor Caffeine.
     */
    @Bean
    public CacheManager cacheManager(Caffeine<Object, Object> caffeine) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(PRODUCTS_CACHE);
        cacheManager.setCaffeine(caffeine);
        return cacheManager;
    }
}
