# PoC: Spring Boot Bean Refresh con Caché y Fallback

Prueba de concepto de actualización periódica en segundo plano cada 4 horas con **Spring Cache (Caffeine)** y fallback/actualización perezosa bajo demanda en caso de fallos.

---

## ⚙️ Arquitectura de la Solución

1. **Gestión de Caché:**
   - **Spring Cache** con **Caffeine** (`CacheConfig.java`).
   - El catálogo de productos se almacena en memoria bajo la clave `'all'`.
   - **Protección contra Cache Stampede:** `@Cacheable(..., sync = true)` para evitar que múltiples hilos concurrentes saturen la base de datos externa en un cache miss.
   - **TTL Pasivo de Respaldo:** 4 horas configurables vía `app.cache.ttl-hours`.

2. **Precarga al Arranque (Warm-up):**
   - `@EventListener(ApplicationReadyEvent.class)` en `ProductCatalogScheduler.java` para poblar la caché automáticamente al arrancar la aplicación.

3. **Resiliencia con Reintentos (Spring Retry):**
   - `@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 200, multiplier = 2))` en las llamadas al origen de datos para tolerar microcortes de red antes de invalidar la caché.

4. **Proceso Programado (Cada 4 Horas):**
   - `@Scheduled(cron = "${app.cache.cron:0 0 */4 * * *}")` en `ProductCatalogScheduler.java`.
   - **Éxito:** Refresca la caché con nuevos datos generados/obtenidos.
   - **Fallo:** Invalida la caché inmediatamente tras agotar los 3 reintentos.

5. **Fallback en la Siguiente Petición HTTP:**
   - Endpoint: `GET /api/products`.
   - Al estar la caché invalidada, la petición forzará una consulta inmediata a la fuente de datos externa.
   - Si la fuente se recuperó: repuebla la caché y responde `200 OK`.
   - Si la fuente sigue fallando: responde `503 Service Unavailable`.

6. **Monitoreo con Spring Boot Actuator:**
   - Endpoints `/actuator/health` y `/actuator/metrics` (incluyendo métricas nativas de Caffeine `/actuator/metrics/cache.gets`).

4. **Simulador y Control de Fallas:**
   - `ExternalDataSourceSimulator.java` utiliza **Datafaker** para generar productos y expone un interruptor para simular fallos de base de datos o API remota a demanda.

---

## 🚀 Cómo Ejecutar la Aplicación

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./mvnw spring-boot:run
```

O para ejecutar las pruebas automatizadas:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home ./mvnw test
```

---

## 📮 Colección de Postman

El proyecto incluye el archivo [`Bean_Refresh_PoC.postman_collection.json`](file:///Users/sergio.aragonez/Documents/bean-refresh/Bean_Refresh_PoC.postman_collection.json) listo para importar directamente en Postman:
1. Abre Postman y haz clic en **Import**.
2. Selecciona el archivo `Bean_Refresh_PoC.postman_collection.json`.
3. La colección ya viene configurada con la variable de entorno `baseUrl = http://localhost:8080`, scripts de validación (`pm.test`) y agrupada en carpetas ordenadas:
   - **1. Catálogo de Productos:** `GET /api/products` (con chequeo de cabecera `X-Cache-Status`).
   - **2. Estado y Scheduler:** `GET /api/cache/status` y `POST /api/scheduler/trigger`.
   - **3. Simulador de Fallos:** `GET /api/simulator/status`, activar y desactivar fallos.

---

## 🧪 Guía de Pruebas con `curl`

### 1. Consultar Productos (Primera llamada: Cache Miss / Poblado inicial)
```bash
curl -i http://localhost:8080/api/products
```
*Header retornado:* `X-Cache-Status: MISS_OR_RELOADED`

### 2. Consultar Productos (Segunda llamada: Cache Hit)
```bash
curl -i http://localhost:8080/api/products
```
*Header retornado:* `X-Cache-Status: HIT`

### 3. Ver Estado de la Caché y del Scheduler
```bash
curl -s http://localhost:8080/api/cache/status | jq .
```

### 4. Forzar Ejecución Manual del Scheduler (Éxito)
```bash
curl -X POST http://localhost:8080/api/scheduler/trigger | jq .
```

---

## 💥 Demostración del Flujo de Fallo y Recuperación

### Paso 1: Simular caída de la base de datos externa / API
```bash
curl -X POST "http://localhost:8080/api/simulator/toggle-failure?enabled=true" | jq .
```

### Paso 2: Ejecutar el proceso programado de 4 horas
```bash
curl -X POST http://localhost:8080/api/scheduler/trigger | jq .
```
> **Resultado:** El proceso falla y la caché se invalida inmediatamente.

### Paso 3: Realizar la siguiente petición a la API
```bash
curl -i http://localhost:8080/api/products
```
> **Resultado:** Responde `503 Service Unavailable`, indicando que intentó recargar la información pero la fuente sigue inaccesible.

### Paso 4: Restaurar la base de datos externa
```bash
curl -X POST "http://localhost:8080/api/simulator/toggle-failure?enabled=false" | jq .
```

### Paso 5: Realizar la siguiente petición
```bash
curl -i http://localhost:8080/api/products
```
> **Resultado:** Responde `200 OK`, recarga los datos frescos de la fuente y repuebla la caché automáticamente (`X-Cache-Status: MISS_OR_RELOADED`).

