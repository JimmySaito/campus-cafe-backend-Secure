# Matriz de verificaciÃ³n

Los estados se basan en las pruebas reportadas durante el laboratorio. Adjuntar las capturas originales en una carpeta de evidencias antes de entregar.

|ID|Prueba|Resultado|ObservaciÃ³n|
|-|-|-|-|
|G1|CatÃ¡logo pÃºblico `GET /products`|PASS|HTTP 200|
|G2|Sin token / token alterado|PASS|HTTP 401|
|G3|Alice y Bob crean y consultan sus Ã³rdenes|PASS (reportado)|Evidencias previas del estudiante|
|G4|Acceso cruzado de lectura/cancelaciÃ³n|PASS (reportado)|Evidencias previas del estudiante|
|G5|Token sin scope requerido|PASS|HTTP 403|
|G6|ID Token usado como Access Token|PASS|HTTP 401|
|S5|Misma clave y mismo cuerpo|PASS|Reproduce el mismo `orderId` y respuesta original|
|S5b|Misma clave y cuerpo diferente|PASS|HTTP 409 `IDEMPOTENCY\\\_KEY\\\_REUSED`|
|S6|Misma clave entre dos usuarios|PASS|Claves aisladas por principal|
|S7|Cinco POST concurrentes idÃ©nticos|PASS|Cinco respuestas HTTP 201 con el mismo `orderId`|
|S8|Precio enviado por el cliente|PASS|HTTP 400 `INVALID\\\_REQUEST`|
|S8b|Fallo real de Catalog sin persistencia|PARTIAL|Prueba unitaria; no se hizo simulaciÃ³n E2E de caÃ­da|
|S9|InspecciÃ³n de CloudWatch|PARTIAL|Se inspeccionaron logs de plataforma; falta trazabilidad de negocio completa|
|S10|Replay despuÃ©s de cambiar precio del catÃ¡logo|PASS|Se conservÃ³ la respuesta original con precio 3500 COP|
|P1|MediciÃ³n de duraciÃ³n Lambda|PASS (mediciÃ³n)|Muestra exploratoria; no demuestra cumplimiento de un SLO|
|D1|Datos semilla del catÃ¡logo|PASS|Productos prod-1, prod-2 y prod-3 cargados|
|CORS1|Lectura de `Idempotency-Replayed` desde navegador|PARTIAL|Header observado en respuesta, pero no se confirmÃ³ exposiciÃ³n CORS|
|IAM1|Ajustes de permisos IAM|BLOCKED|RestricciÃ³n del rol compartido del laboratorio|

## Pruebas locales

* `sam validate --lint`: correcto.
* `sam build --no-cached`: correcto.
* `mvn clean test`: 8 pruebas, 0 fallos y 0 errores.

## PrecauciÃ³n

PASS (reportado) distingue una prueba comunicada por el estudiante de una ejecuciÃ³n reproducida durante la Ãºltima sesiÃ³n. No presentar pruebas parciales o bloqueadas como completas.



\## Alcance de los resultados



Los resultados PASS corresponden a pruebas ejecutadas o evidencias previamente aportadas, segÃºn se especifica en cada fila.



Las pruebas PARTIAL representan verificaciones con cobertura incompleta y no deben interpretarse como aprobaciÃ³n integral.



La prueba IAM1 permanece BLOCKED debido a las restricciones del rol institucional compartido del entorno AWS Academy Learner Lab.



Las mediciones P1 son exploratorias y corresponden a duraciÃ³n de ejecuciÃ³n de Lambda. No representan latencia extremo a extremo ni permiten afirmar cumplimiento de objetivos de rendimiento (SLO).



Las evidencias originales deben adjuntarse separadamente, identificadas por el cÃ³digo de prueba correspondiente.
