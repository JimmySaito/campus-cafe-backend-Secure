# Runbook de verificaciones pendientes

## Antes de entregar
- [ ] Adjuntar capturas originales de G1–G6 y S5–S10 donde existan.
- [ ] Adjuntar salida real de `mvn clean test`, `sam validate --lint` y `sam build --no-cached`.
- [ ] Confirmar en DynamoDB si TTL está habilitado para `CampusCafeIdempotency`.
- [ ] Si el laboratorio lo permite, ejecutar una prueba E2E controlada de fallo de Catalog y comprobar que no se crea ninguna orden.
- [ ] Verificar la configuración CORS efectiva para `Idempotency-Replayed`; no modificar el rol compartido para intentar superar restricciones IAM.
- [ ] Documentar el comando repetible de seed después de ejecutarlo en un entorno limpio.
- [ ] Revisar que no haya tokens, secretos, credenciales ni datos personales en archivos, capturas o historial Git.

## Reglas de evidencia
Registrar fecha, entorno, comando o solicitud, resultado HTTP, resultado esperado/observado y captura original. Ocultar tokens y credenciales. No fabricar salidas ni capturas.
