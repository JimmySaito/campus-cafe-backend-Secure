# Observabilidad y rendimiento

## Muestra disponible
Consulta de CloudWatch Logs Insights sobre registros `REPORT`, 27 invocaciones:
- Promedio: 966.9774 ms
- p95: 2654.51 ms
- Máximo: 3815.53 ms

## Interpretación
Estos valores describen duración de invocaciones Lambda en una muestra exploratoria. No representan latencia extremo a extremo del navegador ni prueban cumplimiento de un objetivo de servicio. No se documentó un umbral SLO acordado.

Se observaron registros de plataforma `INIT_START`, `START`, `END` y `REPORT`, con runtime Java 17. También apareció el aviso SLF4J `StaticLoggerBinder` ausente y fallback a NOP logger. No se observaron logs de negocio suficientes en los fragmentos compartidos.

## Siguiente mejora
Agregar logs estructurados con request ID, operación, resultado y duración; evitar tokens, datos personales y secretos en logs. Correlacionar API Gateway y Lambda mediante identificadores de solicitud.
