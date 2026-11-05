package com.example.bean_refresh.controller;

import com.example.bean_refresh.model.CacheStatusResponse;
import com.example.bean_refresh.model.Product;
import com.example.bean_refresh.service.ExternalDataSourceSimulator;
import com.example.bean_refresh.service.ProductCatalogScheduler;
import com.example.bean_refresh.service.ProductCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Controlador de administración y utilidades para la Prueba de Concepto (PoC).
 * 
 * Permite controlar y demostrar de forma interactiva:
 * 1. Simulación de caídas/errores en la base de datos o servicio externo.
 * 2. Ejecución inmediata a demanda del proceso programado de 4 horas.
 * 3. Monitoreo en tiempo real del estado de la caché, registros y métricas de Caffeine.
 */
@RestController
@RequestMapping("/api")
public class AdminPoCController {

    private final ExternalDataSourceSimulator simulator;
    private final ProductCatalogScheduler scheduler;
    private final ProductCatalogService catalogService;

    public AdminPoCController(ExternalDataSourceSimulator simulator,
                             ProductCatalogScheduler scheduler,
                             ProductCatalogService catalogService) {
        this.simulator = simulator;
        this.scheduler = scheduler;
        this.catalogService = catalogService;
    }

    /**
     * Endpoint: POST /api/simulator/toggle-failure?enabled=true/false
     * Activa o desactiva la simulación de caída de la base de datos o API remota.
     */
    @PostMapping("/simulator/toggle-failure")
    public ResponseEntity<Map<String, Object>> toggleFailure(
            @RequestParam(required = false) Boolean enabled) {
        boolean newState = (enabled != null) ? enabled : !simulator.isShouldFail();
        simulator.setShouldFail(newState);
        return ResponseEntity.ok(Map.of(
                "simulationFailing", newState,
                "message", newState ? "Fallo simulado activado: las consultas a la fuente fallarán tras reintentar."
                                    : "Fallo simulado desactivado: las consultas a la fuente responderán exitosamente."
        ));
    }

    /**
     * Endpoint: GET /api/simulator/status
     * Retorna si el simulador está actualmente en modo de fallo o en modo normal.
     */
    @GetMapping("/simulator/status")
    public ResponseEntity<Map<String, Object>> getSimulatorStatus() {
        return ResponseEntity.ok(Map.of(
                "simulationFailing", simulator.isShouldFail()
        ));
    }

    /**
     * Endpoint: POST /api/scheduler/trigger
     * Dispara inmediatamente la tarea de las 4 horas sin tener que esperar el intervalo de tiempo.
     * Ideal para probar cómo reacciona el sistema cuando la fuente está activa o cuando está caída.
     */
    @PostMapping("/scheduler/trigger")
    public ResponseEntity<Map<String, Object>> triggerScheduler() {
        boolean success = scheduler.executeRefresh();
        return ResponseEntity.ok(Map.of(
                "status", success ? "SUCCESS" : "FAILED",
                "message", success ? "Proceso programado ejecutado con éxito. Caché actualizada."
                                   : "Proceso programado falló. La caché ha sido invalidada para forzar actualización en la próxima petición.",
                "lastError", scheduler.getLastError() != null ? scheduler.getLastError() : "Ninguno",
                "lastRunTime", scheduler.getLastRunTime()
        ));
    }

    /**
     * Endpoint: GET /api/cache/status
     * Devuelve una radiografía completa del estado de la solución:
     * - Si hay datos en caché ('hasCachedData').
     * - Cuántos elementos hay almacenados ('cachedItemCount').
     * - Fecha, hora y resultado de la última ejecución del scheduler.
     * - Métricas nativas de Caffeine (hits, misses, hit rate, evictions).
     */
    @GetMapping("/cache/status")
    public ResponseEntity<CacheStatusResponse> getCacheStatus() {
        List<Product> cachedProducts = catalogService.inspectCachedData();
        CacheStatusResponse response = new CacheStatusResponse(
                catalogService.isDataInCache(),
                cachedProducts.size(),
                scheduler.getLastRunTime(),
                scheduler.getLastStatus(),
                scheduler.getLastError(),
                simulator.isShouldFail(),
                catalogService.getCaffeineStats()
        );
        return ResponseEntity.ok(response);
    }
}
