# Limitaciones conocidas y pendientes

1. **IAM del laboratorio:** no se modificó el rol compartido `LabRole`/`voclabs`; las políticas IAM avanzadas quedan limitadas por el entorno académico.
2. **Fallo E2E de catálogo:** se verificó mediante prueba unitaria que una falla de Catalog no persiste una orden; falta una prueba desplegada de extremo a extremo.
3. **CloudWatch:** se inspeccionaron logs de plataforma, pero falta instrumentación estructurada de eventos de negocio.
4. **CORS:** el backend envió `Idempotency-Replayed`, pero no se confirmó `Access-Control-Expose-Headers`; JavaScript del navegador puede no poder leerlo.
5. **TTL:** existe el atributo `expiresAt`, pero no se confirmó que TTL esté habilitado en DynamoDB.
6. **Rendimiento:** la muestra de 27 invocaciones es exploratoria y no representa latencia completa de usuario ni una prueba formal de SLO.
7. **Concurrencia:** la prueba desplegada reportada ejecutó cinco POST simultáneos con mismo principal, clave y cuerpo, todos con HTTP 201 y el mismo `orderId`. La prueba unitaria de carrera complementa, pero no sustituye, esta evidencia.

Estas limitaciones deben presentarse de forma transparente. No implican que las pruebas ya realizadas hayan fallado.
