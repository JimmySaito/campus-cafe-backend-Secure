# Datos semilla del catálogo

Datos cargados manualmente en DynamoDB durante el laboratorio:

| productId | Producto | priceCents | currency | available |
|---|---|---:|---|---|
| prod-1 | Café Americano | 3500 | COP | true |
| prod-2 | Café con leche | 4000 | COP | true |
| prod-3 | Pan de bono | 3600 | COP | true |

La carga fue manual; `seed/seed_catalog.py` forma parte del repositorio, pero antes de afirmar que la carga es repetible debe probarse desde un entorno limpio y documentarse el comando exacto y sus prerrequisitos.
