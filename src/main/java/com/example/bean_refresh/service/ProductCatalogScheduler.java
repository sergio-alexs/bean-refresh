package com.example.bean_refresh.service;

import com.example.bean_refresh.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Servicio encargado de la actualización programada en segundo plano cada 4 horas.
 * 
 * Responsabilidades clave:
 * 1. Warm-up: Precargar los datos en caché automáticamente en cuanto la aplicación inicia.
 * 2. Background Refresh: Ejecutar la tarea periódica cada 4 horas (expresión cron).
 * 3. Manejo de Fallo acordado: Si la actualización programada falla tras los reintentos,
 *    la caché se INVALIDA de inmediato para que la siguiente petición HTTP asuma la responsabilidad
 *    de consultar la fuente y reintentar la carga de datos.
 */
@Service
public class ProductCatalogScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogScheduler.class);

    private final ProductCatalogService catalogService;

    // Metadatos para trazabilidad y monitoreo en /api/cache/status
    private LocalDateTime lastRunTime;
    private String lastStatus = "NO_EXECUTIONS_YET";
    private String lastError;

    public ProductCatalogScheduler(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * [MEJORA WARM-UP] Precarga de la caché al inicio del ciclo de vida de la aplicación.
     * Escucha 'ApplicationReadyEvent' para garantizar que todos los beans y el servidor web estén listos.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("🚀 [WARM-UP] Precargando caché al inicio de la aplicación...");
        executeRefresh();
    }

    /**
     * [REQUERIMIENTO PRINCIPAL] Tarea periódica programada cada 4 horas.
     * La expresión cron se lee de 'app.cache.cron' (valor por defecto: '0 0 * / 4 * * *').
     */
    @Scheduled(cron = "${app.cache.cron:0 0 */4 * * *}")
    public void scheduledRefresh() {
        log.info("⏰ [SCHEDULER] Iniciando proceso programado de actualización de datos (cada 4 horas)...");
        executeRefresh();
    }

    /**
     * Lógica central de ejecución de la actualización:
     * - Sincronizado ('synchronized') para evitar carreras entre el cron y el endpoint de trigger manual.
     * 
     * Flujo de decisión:
     * - Si la consulta tiene éxito: guarda los nuevos registros en caché y marca el estado como SUCCESS.
     * - Si la consulta falla: captura la excepción, registra el error y expulsa/invalida la caché (EVICT).
     *   Al invalidarse la caché, la próxima llamada a 'GET /api/products' provocará un Cache Miss forzando
     *   la consulta directa a la fuente externa.
     */
    public synchronized boolean executeRefresh() {
        this.lastRunTime = LocalDateTime.now();
        try {
            // Intenta obtener los datos frescos de la base de datos externa / API Faker
            List<Product> updatedProducts = catalogService.refreshCacheExplicitly();
            this.lastStatus = "SUCCESS";
            this.lastError = null;
            log.info("⏰ [SCHEDULER SUCCESS] Proceso completado exitosamente. Se actualizaron {} productos en caché.",
                    updatedProducts.size());
            return true;
        } catch (Exception ex) {
            this.lastStatus = "FAILED";
            this.lastError = ex.getMessage();
            log.error("⏰ [SCHEDULER FAILED] Falló la actualización programada: {}", ex.getMessage());

            // DECISIÓN CRÍTICA DEL DISEÑO:
            // Al fallar el proceso de las 4 horas, invalidamos la caché inmediatamente.
            // De este modo, la siguiente petición HTTP no devolverá datos desactualizados,
            // sino que intentará consultar la fuente en ese mismo instante.
            catalogService.invalidateCache();
            return false;
        }
    }

    public LocalDateTime getLastRunTime() {
        return lastRunTime;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public String getLastError() {
        return lastError;
    }
}
