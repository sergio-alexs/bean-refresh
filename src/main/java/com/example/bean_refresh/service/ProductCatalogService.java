package com.example.bean_refresh.service;

import com.example.bean_refresh.config.CacheConfig;
import com.example.bean_refresh.model.CaffeineStatsDto;
import com.example.bean_refresh.model.Product;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * Servicio de negocio que gestiona el catálogo de productos y su ciclo de vida en la caché Caffeine.
 * 
 * Estrategia de Caché:
 * - Clave única fija ('all') para todo el listado de productos.
 * - Sincronización concurrente (sync = true) para evitar el problema de "Cache Stampede".
 * - Refresco explícito y desalojo inmediato controlados programáticamente.
 */
@Service
public class ProductCatalogService {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogService.class);

    /**
     * Clave única bajo la cual se almacena la lista completa de productos en la caché.
     */
    public static final String CACHE_KEY = "all";

    private final ExternalDataSourceSimulator dataSourceSimulator;
    private final CacheManager cacheManager;

    public ProductCatalogService(ExternalDataSourceSimulator dataSourceSimulator, CacheManager cacheManager) {
        this.dataSourceSimulator = dataSourceSimulator;
        this.cacheManager = cacheManager;
    }

    /**
     * [CONSULTA PRINCIPAL / REQUISITO DE FALLBACK PEREZOSO]
     * 
     * Comportamiento:
     * 1. [CACHE HIT]: Si los datos existen en la caché (por ejemplo tras la precarga o el proceso de 4h),
     *    Spring intercepta el método y retorna la lista en memoria instantáneamente sin ejecutar este bloque.
     * 2. [CACHE MISS / FALLBACK]: Si la caché fue invalidada (por fallo de las 4 horas) o expiró:
     *    - Se ejecuta el cuerpo del método.
     *    - Se invoca a 'dataSourceSimulator.fetchProductsFromRemoteSource()'.
     *    - Si la fuente responde exitosamente, el resultado se almacena automáticamente en caché para futuras peticiones.
     *    - Si la fuente sigue fallando, se propaga la excepción hacia el GlobalExceptionHandler (retornando HTTP 503).
     * 
     * Protección contra 'Cache Stampede' ('sync = true'):
     * - Si 100 peticiones HTTP concurrentes encuentran la caché vacía, solo 1 hilo ejecuta la consulta a la BD
     *   mientras los otros 99 hilos esperan a que el primero termine y luego consumen el valor de la caché.
     */
    @Cacheable(value = CacheConfig.PRODUCTS_CACHE, key = "'" + CACHE_KEY + "'", sync = true)
    public List<Product> getProducts() {
        log.info("[CACHE MISS] La caché no contiene datos o fue invalidada. Obteniendo productos bajo demanda de la fuente externa...");
        return dataSourceSimulator.fetchProductsFromRemoteSource();
    }

    /**
     * [ACTUALIZACIÓN EXPLÍCITA / USADO POR EL SCHEDULER DE 4H]
     * Obtiene datos frescos de la fuente externa y sobrescribe explícitamente la entrada en caché.
     * Si la consulta a la fuente externa arroja una excepción, la caché previa permanece intacta
     * hasta que el scheduler capture el error y decida si invalidarla.
     */
    public List<Product> refreshCacheExplicitly() {
        log.info("Refrescando explícitamente la caché con nuevos datos...");
        List<Product> freshProducts = dataSourceSimulator.fetchProductsFromRemoteSource();
        Cache cache = getProductsCache();
        if (cache != null) {
            cache.put(CACHE_KEY, freshProducts);
            log.info("Caché actualizada con éxito con {} elementos.", freshProducts.size());
        }
        return freshProducts;
    }

    /**
     * [INVALIDACIÓN DE CACHÉ]
     * Expulsa la entrada 'all' de la caché de Caffeine.
     * Se invoca cuando la tarea programada de 4 horas falla, obligando a que la siguiente
     * petición HTTP intente consultar directamente la fuente externa.
     */
    public void invalidateCache() {
        log.warn("Invalidando caché de productos debido a un fallo en la actualización programada.");
        Cache cache = getProductsCache();
        if (cache != null) {
            cache.evict(CACHE_KEY);
            log.info("Caché invalidada correctamente.");
        }
    }

    /**
     * Permite inspeccionar qué datos residen actualmente en la caché sin disparar una recarga.
     */
    @SuppressWarnings("unchecked")
    public List<Product> inspectCachedData() {
        Cache cache = getProductsCache();
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(CACHE_KEY);
            if (wrapper != null && wrapper.get() instanceof List<?>) {
                return (List<Product>) wrapper.get();
            }
        }
        return Collections.emptyList();
    }

    /**
     * Verifica si existe actualmente una entrada válida en la caché.
     */
    public boolean isDataInCache() {
        Cache cache = getProductsCache();
        return cache != null && cache.get(CACHE_KEY) != null;
    }

    /**
     * Extrae las métricas nativas del motor Caffeine (hits, misses, hit rate, evictions)
     * registradas gracias a 'recordStats()'.
     */
    public CaffeineStatsDto getCaffeineStats() {
        Cache cache = getProductsCache();
        if (cache instanceof CaffeineCache caffeineCache) {
            CacheStats stats = caffeineCache.getNativeCache().stats();
            return new CaffeineStatsDto(
                    stats.hitCount(),
                    stats.missCount(),
                    stats.hitRate(),
                    stats.evictionCount()
            );
        }
        return new CaffeineStatsDto(0, 0, 0.0, 0);
    }

    private Cache getProductsCache() {
        return cacheManager.getCache(CacheConfig.PRODUCTS_CACHE);
    }
}
