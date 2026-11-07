# ADR-002: Idempotencia atómica para creación de órdenes

- Estado: Implementada y probada según los resultados documentados.
- Contexto: los reintentos del cliente y las solicitudes simultáneas no deben crear órdenes duplicadas.
- Decisión:
  1. Exigir `Idempotency-Key` con formato acotado.
  2. Calcular una huella SHA-256 del contenido validado de los artículos.
  3. Delimitar la clave por emisor y sujeto autenticados (`iss`, `sub`).
  4. Escribir orden y registro de idempotencia mediante una transacción condicional de DynamoDB.
  5. Devolver la respuesta original para misma clave y mismo contenido; devolver HTTP 409 `IDEMPOTENCY_KEY_REUSED` si cambia el contenido.
  6. Resolver conflictos/races con lectura acotada, sin escritura alternativa incondicional.
- Consecuencias: los reintentos idénticos conservan el mismo `orderId` y los precios originales. La transacción evita que se persista solo uno de los dos registros.
- Pendiente operativo: confirmar que TTL está habilitado en la tabla de idempotencia; la presencia del atributo `expiresAt` no prueba que el TTL de DynamoDB esté activo.
