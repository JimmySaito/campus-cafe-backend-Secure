\# Campus Café - Serverless Architecture Application



Aplicación serverless de microservicios desarrollada para el taller de Arquitectura Empresarial, construida con \*\*Java 17\*\*, \*\*AWS Lambda\*\*, \*\*API Gateway (HTTP API v2)\*\* y \*\*DynamoDB\*\*, provisionada mediante \*\*AWS SAM\*\*.



\----------------------------------------------------



\##  Arquitectura del Sistema



La solución está dividida en dos microservicios independientes con sus propias bases de datos y responsabilidades:



1\. \*\*Catalog Service\*\*: 

&#x20;  - Gestiona el catálogo de productos, precios y disponibilidad.

&#x20;  - Rutas: `GET /products`, `GET /products/{productId}`.

&#x20;  - Tabla DynamoDB: `CampusCafeCatalog`.

2\. \*\*Orders Service\*\*: 

&#x20;  - Gestiona la creación de órdenes calculando totales con base en el catálogo, consultas por ID y cancelaciones.

&#x20;  - Implementa control estricto de idempotencia mediante la cabecera `Idempotency-Key`.

&#x20;  - Rutas: `POST /orders`, `GET /orders/{orderId}`, `POST /orders/{orderId}/cancel`.

&#x20;  - Tabla DynamoDB: `CampusCafeOrders`.

3\. \*\*Seguridad y Despliegue\*\*: 

&#x20;  - Integración con el rol institucional `LabRole` y aislamiento de permisos por servicio.





\[ Browser / Client ] 

&#x20;       │ (HTTPS)

&#x20;       ▼

\[ Amazon API Gateway (HTTP API v2) ]

&#x20;       ├─── /products ────► \[ Catalog Lambda ] ────► \[ Catalog DynamoDB ]

&#x20;       └─── /orders  ─────► \[ Orders Lambda  ] ────► \[ Orders DynamoDB  ]



\-------------------------------------------------------------------



\##  Estructura del Proyecto



```text

campus-cafe-backend/

├── src/

│   └── main/java/com/campuscafe/

│       ├── catalog/          # CatalogHandler.java

│       └── orders/           # OrdersHandler.java

├── template.yaml             # Definición de infraestructura SAM (Tablas, Lambdas, API Gateway)

└── README.md                 # Documentación del proyecto





Instrucciones de Despliegue

Asegúrate de tener instalados AWS CLI, AWS SAM CLI, Java 17 y Maven. Configura tus credenciales y sigue estos pasos en tu terminal:



Validar la plantilla SAM:



Bash

sam validate --lint

Compilar el proyecto (evitando caché para empaquetar dependencias limpias):



Bash

sam build --no-cached

Desplegar en AWS:



Bash

sam deploy



\------------------------------------------------------------



&#x20;Comandos de Verificación (PowerShell)

1\. Consultar el Catálogo

PowerShell

Invoke-RestMethod -Uri "https://<tu-api-id>\[.execute-api.us-east-1.amazonaws.com/products](https://.execute-api.us-east-1.amazonaws.com/products)" -Method Get

2\. Crear una Orden (Con Idempotencia)

PowerShell

Invoke-RestMethod -Uri "https://<tu-api-id>\[.execute-api.us-east-1.amazonaws.com/orders](https://.execute-api.us-east-1.amazonaws.com/orders)" -Method Post -Headers @{ "Content-Type" = "application/json"; "Idempotency-Key" = "test-key-001" } -Body '{"items":\[{"productId":"prod-1","quantity":1}]}'

3\. Consultar una Orden por ID

PowerShell

Invoke-RestMethod -Uri "https://<tu-api-id>\[.execute-api.us-east-1.amazonaws.com/orders/](https://.execute-api.us-east-1.amazonaws.com/orders/)<orderId>" -Method Get

4\. Cancelar una Orden

PowerShell

Invoke-RestMethod -Uri "https://<tu-api-id>\[.execute-api.us-east-1.amazonaws.com/orders/](https://.execute-api.us-east-1.amazonaws.com/orders/)<orderId>/cancel" -Method Post





