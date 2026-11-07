# Campus Café – Backend seguro y arquitectura serverless

Aplicación serverless de microservicios desarrollada para el taller de Arquitectura Empresarial. Implementa un backend para la gestión de catálogo y órdenes de Campus Café, utilizando Java 17, AWS Lambda, Amazon API Gateway, Amazon DynamoDB y AWS SAM.

El proyecto incorpora autenticación mediante Auth0, autorización por scopes e idempotencia segura para la creación de órdenes.

## 1. Tecnologías

- Java 17
- Maven
- AWS Lambda
- Amazon API Gateway (HTTP API v2)
- Amazon DynamoDB
- AWS SAM
- Auth0 (OAuth 2.0 / JWT)
- JUnit

## 2. Arquitectura

La solución se organiza en dos servicios principales:

### Catalog Service

Responsable de administrar el catálogo de productos.

- Consulta de productos.
- Consulta de un producto por identificador.
- Gestión de precios y disponibilidad.
- Tabla: `CampusCafeCatalog`.

### Orders Service

Responsable de administrar las órdenes.

- Creación de órdenes.
- Consulta de órdenes por identificador.
- Cancelación de órdenes.
- Validación de productos y precios desde el catálogo.
- Control de propiedad de las órdenes.
- Idempotencia mediante la cabecera `Idempotency-Key`.

Tablas utilizadas:

- `CampusCafeOrders`: información de las órdenes.
- `CampusCafeIdempotency`: registros de idempotencia.

### Seguridad

- Autenticación mediante JWT de Auth0.
- Autorización mediante scopes.
- Validación de identidad del usuario mediante `iss` y `sub`.
- Aislamiento de órdenes entre usuarios.
- Validación del contenido de las solicitudes.
- Protección frente a reutilización de claves de idempotencia con diferentes solicitudes.

## 3. Rutas de la API

| Método | Ruta | Permiso |
|---|---|---|
| GET | `/products` | Consulta pública según configuración |
| GET | `/products/{productId}` | Consulta pública según configuración |
| POST | `/orders` | `orders:create` |
| GET | `/orders/{orderId}` | `orders:read` |
| POST | `/orders/{orderId}/cancel` | `orders:cancel` |

Las rutas protegidas requieren un Access Token válido y el scope correspondiente.

## 4. Idempotencia

La creación de órdenes utiliza la cabecera `Idempotency-Key`.

Comportamiento esperado:

- Una misma clave con el mismo usuario y contenido devuelve la orden original.
- Una misma clave con un contenido diferente devuelve HTTP 409.
- La clave se encuentra aislada por identidad del usuario.
- La creación de la orden y el registro de idempotencia se realizan mediante una operación transaccional de DynamoDB.
- No se aceptan precios enviados por el cliente; el backend obtiene los precios del catálogo.

## 5. Estructura del proyecto

```text
campus-cafe-backend/
├── docs/
│   ├── arquitectura/
│   ├── decisiones/
│   ├── limitaciones/
│   ├── operacion/
│   └── pruebas/
├── frontend/
│   ├── app.js
│   └── index.html
├── seed/
│   └── seed_catalog.py
├── src/
│   ├── main/
│   │   └── java/com/campuscafe/
│   │       ├── catalog/
│   │       └── orders/
│   └── test/
├── openapi.yaml
├── pom.xml
├── template.yaml
└── README.md
```

## 6. Requisitos

Antes de ejecutar el proyecto se requiere:

- Java 17.
- Maven.
- AWS CLI.
- AWS SAM CLI.
- Acceso al entorno AWS Academy Learner Lab.
- Configuración de Auth0 para el frontend y la API.

En el laboratorio se utiliza el rol institucional proporcionado. No se deben modificar sus permisos fuera de las capacidades autorizadas por el entorno académico.

## 7. Compilación y validación

Desde la carpeta raíz del proyecto:

Validar la plantilla SAM:

```powershell
sam validate --lint
```

Compilar el proyecto:

```powershell
sam build --no-cached
```

Ejecutar las pruebas unitarias:

```powershell
mvn clean test
```

## 8. Despliegue

Para el primer despliegue:

```powershell
sam deploy --guided
```

Seguir las instrucciones del asistente y proporcionar los parámetros solicitados por la plantilla.

Para despliegues posteriores:

```powershell
sam deploy
```

La región utilizada durante el desarrollo fue `us-east-1`.

## 9. Verificación de la API

Configurar la URL base del API y un Access Token válido en PowerShell:

```powershell
$ApiUrl = "https://<api-id>.execute-api.us-east-1.amazonaws.com"
$Token = "<ACCESS_TOKEN>"
```

Consultar el catálogo:

```powershell
Invoke-RestMethod -Uri "$ApiUrl/products" -Method Get
```

Crear una orden:

```powershell
$Headers = @{
    Authorization