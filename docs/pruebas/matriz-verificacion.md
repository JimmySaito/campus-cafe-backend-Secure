# Matriz de verificación

Los estados se basan en las pruebas reportadas durante el laboratorio. Adjuntar las capturas originales en una carpeta de evidencias antes de entregar.

| ID | Prueba | Resultado | Observación |
|---|---|---|---|
| G1 | Catálogo público `GET /products` | PASS | HTTP 200 |
| G2 | Sin token / token alterado | PASS | HTTP 401 |
| G3 | Alice y Bob crean y consultan sus órdenes | PASS (reportado) | Evidencias previas del estudiante |
| G4 | Acceso cruzado de lectura/cancelación | PASS (reportado) | Evidencias previas del estudiante |
| G5 | Token sin scope requerido | PASS | HTTP 403 |
| G6 | ID Token usado como Access Token | PASS | HTTP 401 |
| S5 | Misma clave y mismo cuerpo | PASS | Reproduce el mismo `orderId` y respuesta original |
| S5b | Misma clave y cuerpo diferente | PASS | HTTP 409 `IDEMPOTENCY_KEY_REUSED` |
| S6 | Misma clave entre dos usuarios | PASS | Claves aisladas por principal |
| S7 | Cinco POST concurrentes idénticos | PASS | Cinco respuestas HTTP 201 con el mismo `orderId` |
| S8 | Precio enviado por el cliente | PASS | HTTP 400 `INVALID_REQUEST` |
| S8b | Fallo real de Catalog sin persistencia | PARTIAL | Prueba unitaria; no se hizo simulación E2E de caída |
| S9 | Inspección de CloudWatch | PARTIAL | Se inspeccionaron logs de plataforma; falta trazabilidad de negocio completa |
| S10 | Replay después de cambiar precio del catálogo | PASS | Se conservó la respuesta original con precio 3500 COP |
| P1 | Medición de duración Lambda | PASS (medición) | Muestra exploratoria; no demuestra cumplimiento de un SLO |
| D1 | Datos semilla del catálogo | PASS | Productos prod-1, prod-2 y prod-3 cargados |
| CORS1 | Lectura de `Idempotency-Replayed` desde navegador | PARTIAL | Header observado en respuesta, pero no se confirmó exposición CORS |
| IAM1 | Ajustes de permisos IAM | BLOCKED | Restricción del rol compartido del laboratorio |

## Pruebas locales
- `sam validate --lint`: correcto.
- `sam build --no-cached`: correcto.
- `mvn clean test`: 8 pruebas, 0 fallos y 0 errores.

## Precaución
PASS (reportado) distingue una prueba comunicada por el estudiante de una ejecución reproducida durante la última sesión. No presentar pruebas parciales o bloqueadas como completas.
