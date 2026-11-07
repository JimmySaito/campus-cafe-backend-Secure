# ADR-001: Límites de servicios y propiedad de datos

- Estado: Aceptada
- Contexto: catálogo y órdenes tienen responsabilidades y ciclos de cambio distintos.
- Decisión: separar Catalog y Orders en funciones Lambda. Cada servicio mantiene la propiedad lógica de su tabla DynamoDB. Orders obtiene precio y disponibilidad mediante la interfaz del catálogo, no mediante escrituras directas sobre sus datos.
- Consecuencias: menor acoplamiento y límites más claros; Orders depende de la disponibilidad de Catalog para nuevas órdenes.
- Nota: verificar que la plantilla SAM y los permisos efectivos del laboratorio correspondan a esta descripción. El rol compartido del laboratorio limita algunos cambios IAM.
