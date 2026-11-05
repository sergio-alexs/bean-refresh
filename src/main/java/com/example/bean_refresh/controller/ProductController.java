package com.example.bean_refresh.controller;

import com.example.bean_refresh.model.Product;
import com.example.bean_refresh.service.ProductCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Controlador REST principal para la consulta de productos.
 */
@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductCatalogService catalogService;

    public ProductController(ProductCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    /**
     * Endpoint: GET /api/products
     * 
     * Flujo de ejecución:
     * 1. Consulta 'catalogService.getProducts()', que está protegido por @Cacheable(sync = true).
     * 2. Si la caché tiene datos -> Devuelve los productos inmediatamente y añade la cabecera 'X-Cache-Status: HIT'.
     * 3. Si la caché estaba vacía o fue invalidada por fallo del scheduler de 4 horas -> Consulta la fuente externa,
     *    repuebla la caché y devuelve 'X-Cache-Status: MISS_OR_RELOADED'.
     * 4. Si la fuente externa sigue inaccesible -> Se lanza 'ExternalServiceException', retornando HTTP 503.
     */
    @GetMapping
    public ResponseEntity<List<Product>> getProducts() {
        boolean wasInCacheBefore = catalogService.isDataInCache();
        List<Product> products = catalogService.getProducts();

        return ResponseEntity.ok()
                .header("X-Cache-Status", wasInCacheBefore ? "HIT" : "MISS_OR_RELOADED")
                .body(products);
    }
}
