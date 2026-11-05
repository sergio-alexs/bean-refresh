package com.example.bean_refresh.service;

import com.example.bean_refresh.exception.ExternalServiceException;
import com.example.bean_refresh.model.Product;
import net.datafaker.Faker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simulador de fuente de datos externa (simula una base de datos remota o una API Faker).
 * 
 * Capacidades:
 * 1. Generación de datos falsos mediante Datafaker (nombres de productos, departamentos, precios).
 * 2. Interruptor atómico (AtomicBoolean) para provocar fallos manuales de red o caídas de BD en demos.
 * 3. Tolerancia a fallos (@Retryable): Si ocurre un fallo, reintenta hasta 3 veces con retroceso exponencial.
 */
@Service
public class ExternalDataSourceSimulator {

    private static final Logger log = LoggerFactory.getLogger(ExternalDataSourceSimulator.class);
    private final Faker faker = new Faker();

    // Variable atómica thread-safe para alternar entre simulación de éxito o error
    private final AtomicBoolean shouldFail = new AtomicBoolean(false);

    /**
     * Activa o desactiva el modo de fallo simulado.
     */
    public void setShouldFail(boolean fail) {
        log.info("Simulador de origen de datos: modo de fallo cambiado a {}", fail);
        this.shouldFail.set(fail);
    }

    public boolean isShouldFail() {
        return this.shouldFail.get();
    }

    /**
     * Consulta y genera los registros simulados.
     * 
     * Resiliencia con @Retryable:
     * - retryFor: Aplica únicamente ante excepciones de tipo ExternalServiceException.
     * - maxAttempts = 3: Intenta hasta 3 veces (intento original + 2 reintentos).
     * - backoff: Espera inicial de 200ms con multiplicador 2x (intento 1: inmediato, intento 2: 200ms, intento 3: 400ms).
     */
    @Retryable(
            retryFor = ExternalServiceException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 200, multiplier = 2)
    )
    public List<Product> fetchProductsFromRemoteSource() {
        log.info("--> Consultando fuente de datos externa simulada...");

        // Si el interruptor de fallo está encendido, simulamos una pérdida de conexión
        if (shouldFail.get()) {
            log.error("¡Fallo simulado! Error al obtener datos de la base de datos externa/API Faker.");
            throw new ExternalServiceException("No fue posible comunicarse con la base de datos remota o el servicio externo.");
        }

        // Simulación de registros obtenidos con Datafaker
        List<Product> products = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (int i = 1; i <= 5; i++) {
            products.add(new Product(
                    "PROD-" + i,
                    faker.commerce().productName(),
                    faker.commerce().department(),
                    BigDecimal.valueOf(faker.number().randomDouble(2, 10, 500)).setScale(2, RoundingMode.HALF_UP),
                    faker.number().numberBetween(1, 100),
                    now
            ));
        }

        log.info("<-- Éxito: {} productos recuperados de la fuente externa.", products.size());
        return products;
    }
}
