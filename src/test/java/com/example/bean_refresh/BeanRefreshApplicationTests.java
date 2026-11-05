package com.example.bean_refresh;

import com.example.bean_refresh.service.ExternalDataSourceSimulator;
import com.example.bean_refresh.service.ProductCatalogScheduler;
import com.example.bean_refresh.service.ProductCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class BeanRefreshApplicationTests {

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private ProductCatalogService catalogService;

    @Autowired
    private ProductCatalogScheduler scheduler;

    @Autowired
    private ExternalDataSourceSimulator simulator;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        simulator.setShouldFail(false);
        catalogService.invalidateCache();
    }

    @Test
    @DisplayName("1. Primera petición llena la caché y la segunda devuelve HIT")
    void testInitialRequestCachesDataAndSecondRequestHitsCache() throws Exception {
        assertThat(catalogService.isDataInCache()).isFalse();

        // 1. Primera petición: MISS_OR_RELOADED
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Status", "MISS_OR_RELOADED"))
                .andExpect(jsonPath("$.length()").value(5));

        assertThat(catalogService.isDataInCache()).isTrue();

        // 2. Segunda petición: HIT
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Status", "HIT"))
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    @DisplayName("2. La ejecución del scheduler exitosa actualiza la caché")
    void testSchedulerSuccessRefreshesCache() throws Exception {
        // Cargar datos en caché inicialmente
        catalogService.getProducts();
        assertThat(catalogService.isDataInCache()).isTrue();

        // Ejecutar scheduler
        boolean success = scheduler.executeRefresh();
        assertThat(success).isTrue();
        assertThat(scheduler.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(catalogService.isDataInCache()).isTrue();
    }

    @Test
    @DisplayName("3. Si el scheduler falla, invalida la caché de inmediato")
    void testSchedulerFailureInvalidatesCacheImmediately() throws Exception {
        // Poblamos la caché primero
        catalogService.getProducts();
        assertThat(catalogService.isDataInCache()).isTrue();

        // Simulamos fallo en la fuente externa
        simulator.setShouldFail(true);

        // Disparamos el scheduler
        boolean success = scheduler.executeRefresh();
        assertThat(success).isFalse();
        assertThat(scheduler.getLastStatus()).isEqualTo("FAILED");

        // Verificamos que la caché quedó invalidada inmediatamente
        assertThat(catalogService.isDataInCache()).isFalse();
    }

    @Test
    @DisplayName("4. Tras fallo del scheduler, la siguiente petición intenta actualizar: falla si la fuente sigue caída (503)")
    void testNextRequestFailsIfSourceStillDown() throws Exception {
        catalogService.getProducts();
        simulator.setShouldFail(true);
        scheduler.executeRefresh();
        assertThat(catalogService.isDataInCache()).isFalse();

        // La siguiente petición HTTP intenta consultar la fuente y retorna 503
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"));
    }

    @Test
    @DisplayName("5. Tras fallo del scheduler, la siguiente petición recupera y repuebla la caché cuando la fuente se restablece")
    void testNextRequestRecoversWhenSourceIsRestored() throws Exception {
        // 1. Poblamos caché
        catalogService.getProducts();

        // 2. Simulamos fallo y ejecución de scheduler (invalida la caché)
        simulator.setShouldFail(true);
        scheduler.executeRefresh();
        assertThat(catalogService.isDataInCache()).isFalse();

        // 3. La fuente externa se recupera
        simulator.setShouldFail(false);

        // 4. La siguiente petición HTTP debe consultar la fuente y repoblar la caché
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Status", "MISS_OR_RELOADED"))
                .andExpect(jsonPath("$.length()").value(5));

        assertThat(catalogService.isDataInCache()).isTrue();

        // 5. La subsiguiente petición ya vuelve a ser HIT
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache-Status", "HIT"));
    }

    @Test
    @DisplayName("6. Endpoints de administración de la PoC funcionan correctamente")
    void testAdminEndpoints() throws Exception {
        // Activar fallo
        mockMvc.perform(post("/api/simulator/toggle-failure?enabled=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simulationFailing").value(true));

        assertThat(simulator.isShouldFail()).isTrue();

        // Disparar scheduler vía endpoint
        mockMvc.perform(post("/api/scheduler/trigger"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        // Consultar estado de caché
        mockMvc.perform(get("/api/cache/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasCachedData").value(false))
                .andExpect(jsonPath("$.simulationFailing").value(true))
                .andExpect(jsonPath("$.lastScheduledStatus").value("FAILED"))
                .andExpect(jsonPath("$.caffeineStats").exists())
                .andExpect(jsonPath("$.caffeineStats.hitCount").isNumber())
                .andExpect(jsonPath("$.caffeineStats.missCount").isNumber());
    }
}
