package com.campuscafe.orders;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class OrdersHandler implements
        RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final int CATALOG_TIMEOUT_MILLIS = 1500;
    private static final int MAX_IDEMPOTENCY_LOOKUP_ATTEMPTS = 3;
    private static final String IDEMPOTENCY_KEY_PATTERN = "[A-Za-z0-9._~-]{1,128}";

    private final DynamoDbClient dbClient;
    private final String ordersTable;
    private final String idempotencyTable;
    private final ObjectMapper mapper;
    private final CatalogClient catalogClient;

    private static class Principal {
        final String iss;
        final String sub;

        Principal(String iss, String sub) {
            this.iss = iss;
            this.sub = sub;
        }
    }

    private static class ValidatedLine {
        final String productId;
        final int quantity;

        ValidatedLine(String productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }
    }

    private static class ValidatedRequest {
        final List<ValidatedLine> lines;
        final String requestHash;

        ValidatedRequest(List<ValidatedLine> lines, String requestHash) {
            this.lines = lines;
            this.requestHash = requestHash;
        }
    }

    public OrdersHandler() {
        this(
                DynamoDbClient.builder()
                        .region(Region.of(System.getenv().getOrDefault(
                                "AWS_REGION", "us-east-1")))
                        .httpClientBuilder(UrlConnectionHttpClient.builder())
                        .build(),
                System.getenv("ORDERS_TABLE"),
                System.getenv("IDEMPOTENCY_TABLE"),
                System.getenv("CATALOG_API_URL"),
                new ObjectMapper()
        );
    }

    // Constructor de producción y compatibilidad con la configuración SAM.
    OrdersHandler(DynamoDbClient dbClient,
                  String ordersTable,
                  String idempotencyTable,
                  String catalogApiUrl,
                  ObjectMapper mapper) {
        this(
                dbClient,
                ordersTable,
                idempotencyTable,
                mapper,
                new CatalogClient(catalogApiUrl, mapper, CATALOG_TIMEOUT_MILLIS)
        );
    }

    // Constructor package-private para inyectar un CatalogClient simulado en pruebas.
    OrdersHandler(DynamoDbClient dbClient,
                  String ordersTable,
                  String idempotencyTable,
                  ObjectMapper mapper,
                  CatalogClient catalogClient) {
        if (dbClient == null || blank(ordersTable) || blank(idempotencyTable)
                || mapper == null || catalogClient == null) {
            throw new IllegalArgumentException("Missing OrdersHandler configuration");
        }
        this.dbClient = dbClient;
        this.ordersTable = ordersTable;
        this.idempotencyTable = idempotencyTable;
        this.mapper = mapper;
        this.catalogClient = catalogClient;
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(
            APIGatewayV2HTTPEvent event, Context context) {
        try {
            Principal principal = extractPrincipal(event);
            if (principal == null) {
                safeLog(context, "operation=authorize result=DENIED");
                return buildResponse(401, Map.of(
                        "code", "UNAUTHORIZED",
                        "message", "Missing or invalid security context"));
            }

            String routeKey = event.getRouteKey();
            Map<String, String> pathParams = event.getPathParameters();
            String orderId = pathParams == null ? null : pathParams.get("orderId");

            if ("POST /orders".equals(routeKey)) {
                String key = getHeader(event.getHeaders(), "Idempotency-Key");
                if (key == null || !key.matches(IDEMPOTENCY_KEY_PATTERN)) {
                    return invalidRequest(
                            "Idempotency-Key must contain 1 to 128 allowed characters");
                }
                return createOrder(event.getBody(), key, principal, context);
            }
            if ("GET /orders/{orderId}".equals(routeKey)) {
                return getOrder(orderId, principal);
            }
            if ("POST /orders/{orderId}/cancel".equals(routeKey)) {
                return cancelOrder(orderId, principal);
            }
            return buildResponse(404, Map.of(
                    "code", "ROUTE_NOT_FOUND", "message", "Route not found"));
        } catch (Exception e) {
            safeLog(context, "operation=orders result=ERROR errorType="
                    + e.getClass().getSimpleName());
            return buildResponse(500, Map.of(
                    "code", "INTERNAL_ERROR",
                    "message", "Unable to process the request"));
        }
    }

    private Principal extractPrincipal(APIGatewayV2HTTPEvent event) {
        if (event == null || event.getRequestContext() == null
                || event.getRequestContext().getAuthorizer() == null
                || event.getRequestContext().getAuthorizer().getJwt() == null) {
            return null;
        }
        Map<String, String> claims = event.getRequestContext()
                .getAuthorizer().getJwt().getClaims();
        if (claims == null) return null;

        String issuer = claims.get("iss");
        String subject = claims.get("sub");
        if (blank(issuer) || blank(subject)) return null;
        return new Principal(issuer, subject);
    }

    private String getHeader(Map<String, String> headers, String expectedName) {
        if (headers == null) return null;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (expectedName.equalsIgnoreCase(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private APIGatewayV2HTTPResponse createOrder(
            String body, String clientKey, Principal principal, Context context)
            throws Exception {

        ValidatedRequest validated;
        try {
            validated = validateAndHashRequest(body);
        } catch (IllegalArgumentException e) {
            return invalidRequest(e.getMessage());
        }

        // El ID se deriva de una tupla con longitudes prefijadas:
        // issuer, subject y clientKey no pueden confundirse por delimitadores.
        String idempotencyId = sha256(encodeTuple(
                principal.iss, principal.sub, clientKey));

        // Primero se busca el replay. Así no se consulta el precio actual
        // cuando el cliente está repitiendo una solicitud ya completada.
        Map<String, AttributeValue> existing = readIdempotency(idempotencyId);
        if (!existing.isEmpty()) {
            return replayOrConflict(existing, principal, validated.requestHash);
        }

        List<Map<String, Object>> itemSnapshots = new ArrayList<>();
        int totalCents = 0;
        String currency = null;

        // La llamada al catálogo solo ocurre para una solicitud nueva.
        // Si falla, todavía no se ha escrito ni la orden ni el registro idem.
        for (ValidatedLine line : validated.lines) {
            CatalogClient.Product product;
            try {
                product = catalogClient.getProduct(line.productId);
            } catch (CatalogClient.ProductNotFoundException e) {
                return buildResponse(400, Map.of(
                        "code", "PRODUCT_NOT_FOUND",
                        "message", "Product ID not found: " + line.productId));
            } catch (CatalogClient.CatalogUnavailableException e) {
                safeLog(context, "operation=catalog_lookup result=UNAVAILABLE");
                return buildResponse(503, Map.of(
                        "code", "CATALOG_UNAVAILABLE",
                        "message", "Catalog service is temporarily unavailable"));
            }

            if (!product.isAvailable()) {
                return buildResponse(409, Map.of(
                        "code", "PRODUCT_UNAVAILABLE",
                        "message", "Product is unavailable: " + line.productId));
            }

            String productCurrency = product.getCurrency();
            if (blank(productCurrency)) {
                throw new IllegalStateException("Invalid catalog currency");
            }
            if (currency == null) {
                currency = productCurrency;
            } else if (!currency.equals(productCurrency)) {
                throw new IllegalStateException("Mixed catalog currencies");
            }

            int priceCents = product.getPriceCents();
            int subtotal = Math.multiplyExact(priceCents, line.quantity);
            totalCents = Math.addExact(totalCents, subtotal);

            itemSnapshots.add(Map.of(
                    "productId", product.getProductId(),
                    "name", product.getName(),
                    "quantity", line.quantity,
                    "unitPriceCents", priceCents));
        }

        String orderId = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        Map<String, Object> responseBody = new HashMap<>();
        responseBody.put("orderId", orderId);
        responseBody.put("status", "CREATED");
        responseBody.put("items", itemSnapshots);
        responseBody.put("totalCents", totalCents);
        responseBody.put("currency", currency);
        responseBody.put("createdAt", now);
        responseBody.put("updatedAt", now);
        String responseJson = mapper.writeValueAsString(responseBody);

        Map<String, AttributeValue> orderRecord = new HashMap<>();
        orderRecord.put("orderId", s(orderId));
        orderRecord.put("status", s("CREATED"));
        orderRecord.put("totalCents", n(totalCents));
        orderRecord.put("currency", s(currency));
        orderRecord.put("itemsJson", s(mapper.writeValueAsString(itemSnapshots)));
        orderRecord.put("ownerIssuer", s(principal.iss));
        orderRecord.put("ownerSubject", s(principal.sub));
        orderRecord.put("createdAt", s(now));
        orderRecord.put("updatedAt", s(now));

        Map<String, AttributeValue> idempotencyRecord = new HashMap<>();
        idempotencyRecord.put("idempotencyId", s(idempotencyId));
        idempotencyRecord.put("ownerIssuer", s(principal.iss));
        idempotencyRecord.put("ownerSubject", s(principal.sub));
        idempotencyRecord.put("requestHash", s(validated.requestHash));
        idempotencyRecord.put("orderId", s(orderId));
        idempotencyRecord.put("responseBody", s(responseJson));
        idempotencyRecord.put("createdAt", s(now));

        try {
            // Ambas escrituras se confirman o se cancelan juntas.
            // Las condiciones evitan sobrescribir un registro ganador.
            dbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                    .transactItems(
                            TransactWriteItem.builder().put(Put.builder()
                                    .tableName(ordersTable)
                                    .item(orderRecord)
                                    .conditionExpression(
                                            "attribute_not_exists(orderId)")
                                    .build()).build(),
                            TransactWriteItem.builder().put(Put.builder()
                                    .tableName(idempotencyTable)
                                    .item(idempotencyRecord)
                                    .conditionExpression(
                                            "attribute_not_exists(idempotencyId)")
                                    .build()).build())
                    .build());

            return response(201, responseJson, Map.of(
                    "Location", "/orders/" + orderId));
        } catch (TransactionCanceledException e) {
            // Puede ser una carrera: otro request con la misma tupla ganó.
            // Solo se acepta el resultado después de releer el registro ganador.
            for (int attempt = 0; attempt < MAX_IDEMPOTENCY_LOOKUP_ATTEMPTS;
                 attempt++) {
                Map<String, AttributeValue> winner =
                        readIdempotency(idempotencyId);
                if (!winner.isEmpty()) {
                    return replayOrConflict(
                            winner, principal, validated.requestHash);
                }
                Thread.yield();
            }

            safeLog(context, "operation=idempotency_transaction result=CANCELLED");
            // No se hace PutItem alternativo ni escritura no condicional.
            return buildResponse(503, Map.of(
                    "code", "IDEMPOTENCY_RESULT_UNAVAILABLE",
                    "message", "Unable to confirm request outcome; retry with the same key"));
        }
    }

    private ValidatedRequest validateAndHashRequest(String body) {
        if (blank(body)) throw new IllegalArgumentException("Request body is required");

        final JsonNode request;
        try {
            request = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body must be valid JSON");
        }
        if (request == null || !request.isObject()) {
            throw new IllegalArgumentException("Request body must be a JSON object");
        }

        // Esquema estricto: el cliente solo controla items/productId/quantity.
        // Así se rechazan también precios enviados por el cliente.
        request.fieldNames().forEachRemaining(field -> {
            if (!"items".equals(field)) {
                throw new IllegalArgumentException(
                        "Unsupported request field: " + field);
            }
        });

        JsonNode items = request.get("items");
        if (items == null || !items.isArray()
                || items.size() < 1 || items.size() > 10) {
            throw new IllegalArgumentException(
                    "Must contain 1 to 10 product lines");
        }

        List<ValidatedLine> lines = new ArrayList<>();
        Set<String> seenProducts = new HashSet<>();
        ObjectNode canonical = mapper.createObjectNode();
        ArrayNode canonicalItems = canonical.putArray("items");

        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                        "Each product line must be an object");
            }
            item.fieldNames().forEachRemaining(field -> {
                if (!"productId".equals(field) && !"quantity".equals(field)) {
                    throw new IllegalArgumentException(
                            "Unsupported product line field: " + field);
                }
            });

            JsonNode productIdNode = item.get("productId");
            JsonNode quantityNode = item.get("quantity");
            if (productIdNode == null || !productIdNode.isTextual()
                    || blank(productIdNode.asText())
                    || productIdNode.asText().length() > 128) {
                throw new IllegalArgumentException(
                        "Each line must contain a valid productId");
            }
            if (quantityNode == null || !quantityNode.isIntegralNumber()
                    || !quantityNode.canConvertToInt()
                    || quantityNode.intValue() < 1
                    || quantityNode.intValue() > 10) {
                throw new IllegalArgumentException(
                        "Quantity must be an integer between 1 and 10");
            }

            String productId = productIdNode.asText();
            int quantity = quantityNode.intValue();
            if (!seenProducts.add(productId)) {
                throw new IllegalArgumentException(
                        "Duplicate product IDs are not allowed");
            }
            lines.add(new ValidatedLine(productId, quantity));

            ObjectNode canonicalLine = canonicalItems.addObject();
            canonicalLine.put("productId", productId);
            canonicalLine.put("quantity", quantity);
        }

        try {
            // Orden de propiedades fijado explícitamente; el orden de las
            // líneas se conserva porque forma parte del input aceptado.
            return new ValidatedRequest(
                    lines, sha256(mapper.writeValueAsBytes(canonical)));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to canonicalize request", e);
        }
    }

    private APIGatewayV2HTTPResponse replayOrConflict(
            Map<String, AttributeValue> record,
            Principal principal,
            String requestHash) {

        String ownerIssuer = stringAttribute(record, "ownerIssuer");
        String ownerSubject = stringAttribute(record, "ownerSubject");

        // Defensa adicional: aunque la clave ya está namespaced, nunca se
        // devuelve un registro si sus propietarios no coinciden.
        if (!principal.iss.equals(ownerIssuer)
                || !principal.sub.equals(ownerSubject)) {
            return buildResponse(409, Map.of(
                    "code", "IDEMPOTENCY_OWNER_MISMATCH",
                    "message", "Idempotency key cannot be reused"));
        }

        String storedHash = stringAttribute(record, "requestHash");
        if (!requestHash.equals(storedHash)) {
            return buildResponse(409, Map.of(
                    "code", "IDEMPOTENCY_KEY_REUSED",
                    "message", "Idempotency key was already used with a different request"));
        }

        String responseBody = stringAttribute(record, "responseBody");
        String orderId = stringAttribute(record, "orderId");
        if (responseBody == null || orderId == null) {
            return buildResponse(503, Map.of(
                    "code", "IDEMPOTENCY_RECORD_INVALID",
                    "message", "Unable to replay the original request"));
        }
        return response(201, responseBody, Map.of(
                "Location", "/orders/" + orderId,
                "Idempotency-Replayed", "true"));
    }

    private Map<String, AttributeValue> readIdempotency(String idempotencyId) {
        GetItemResponse result = dbClient.getItem(GetItemRequest.builder()
                .tableName(idempotencyTable)
                .key(Map.of("idempotencyId", s(idempotencyId)))
                .consistentRead(true)
                .build());
        return result.item() == null ? Map.of() : result.item();
    }

    private byte[] encodeTuple(String... values) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            for (String value : values) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                out.writeInt(encoded.length);
                out.write(encoded);
            }
        }
        return bytes.toByteArray();
    }

    private String sha256(byte[] input) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(input));
    }

    private String sha256(String input) throws Exception {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    private AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue n(int value) {
        return AttributeValue.builder().n(Integer.toString(value)).build();
    }

    private APIGatewayV2HTTPResponse getOrder(
            String orderId, Principal principal) {
        if (blank(orderId)) return orderNotFound();
        Map<String, AttributeValue> item = readOrder(orderId);
        if (!isOwnedBy(item, principal)) return orderNotFound();
        return orderResponse(item);
    }

    private APIGatewayV2HTTPResponse cancelOrder(
            String orderId, Principal principal) {
        if (blank(orderId)) return orderNotFound();

        Map<String, AttributeValue> item = readOrder(orderId);
        if (!isOwnedBy(item, principal)) return orderNotFound();

        String status = stringAttribute(item, "status");
        if ("CANCELLED".equals(status)) return orderResponse(item);
        if (!"CREATED".equals(status)) return invalidOrderState();

        String now = Instant.now().toString();
        try {
            UpdateItemResponse updated = dbClient.updateItem(
                    UpdateItemRequest.builder()
                            .tableName(ordersTable)
                            .key(Map.of("orderId", s(orderId)))
                            .updateExpression("SET #st = :cancelled, #updated = :now")
                            .conditionExpression(
                                    "attribute_exists(#id) AND #issuer = :issuer "
                                    + "AND #subject = :subject AND #st = :created")
                            .expressionAttributeNames(Map.of(
                                    "#id", "orderId",
                                    "#issuer", "ownerIssuer",
                                    "#subject", "ownerSubject",
                                    "#st", "status",
                                    "#updated", "updatedAt"))
                            .expressionAttributeValues(Map.of(
                                    ":issuer", s(principal.iss),
                                    ":subject", s(principal.sub),
                                    ":created", s("CREATED"),
                                    ":cancelled", s("CANCELLED"),
                                    ":now", s(now)))
                            .returnValues(ReturnValue.ALL_NEW)
                            .build());
            return orderResponse(updated.attributes());
        } catch (ConditionalCheckFailedException e) {
            Map<String, AttributeValue> current = readOrder(orderId);
            if (!isOwnedBy(current, principal)) return orderNotFound();
            if ("CANCELLED".equals(stringAttribute(current, "status"))) {
                return orderResponse(current);
            }
            return invalidOrderState();
        }
    }

    private Map<String, AttributeValue> readOrder(String orderId) {
        GetItemResponse result = dbClient.getItem(GetItemRequest.builder()
                .tableName(ordersTable)
                .key(Map.of("orderId", s(orderId)))
                .consistentRead(true)
                .build());
        return result.item() == null ? Map.of() : result.item();
    }

    private boolean isOwnedBy(
            Map<String, AttributeValue> item, Principal principal) {
        return item != null && !item.isEmpty() && principal != null
                && principal.iss.equals(stringAttribute(item, "ownerIssuer"))
                && principal.sub.equals(stringAttribute(item, "ownerSubject"));
    }

    private String stringAttribute(
            Map<String, AttributeValue> item, String name) {
        if (item == null) return null;
        AttributeValue value = item.get(name);
        return value == null ? null : value.s();
    }

    private APIGatewayV2HTTPResponse orderResponse(
            Map<String, AttributeValue> item) {
        Map<String, Object> result = new HashMap<>();
        result.put("orderId", stringAttribute(item, "orderId"));
        result.put("status", stringAttribute(item, "status"));
        AttributeValue total = item.get("totalCents");
        result.put("totalCents", total == null ? 0 : Integer.parseInt(total.n()));
        result.put("currency", stringAttribute(item, "currency"));

        String itemsJson = stringAttribute(item, "itemsJson");
        try {
            List<?> items = itemsJson == null ? List.of()
                    : mapper.readValue(itemsJson, List.class);
            result.put("items", items);
        } catch (Exception e) {
            result.put("items", List.of());
        }
        result.put("createdAt", stringAttribute(item, "createdAt"));
        result.put("updatedAt", stringAttribute(item, "updatedAt"));
        return buildResponse(200, result);
    }

    private APIGatewayV2HTTPResponse orderNotFound() {
        return buildResponse(404, Map.of(
                "code", "NOT_FOUND", "message", "Order not found"));
    }

    private APIGatewayV2HTTPResponse invalidOrderState() {
        return buildResponse(409, Map.of(
                "code", "INVALID_ORDER_STATE",
                "message", "Order cannot be cancelled in its current state"));
    }

    private APIGatewayV2HTTPResponse invalidRequest(String message) {
        return buildResponse(400, Map.of(
                "code", "INVALID_REQUEST", "message", message));
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, Object body) {
        try {
            return response(statusCode, mapper.writeValueAsString(body), Map.of());
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(500)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody("{\"code\":\"INTERNAL_ERROR\","
                            + "\"message\":\"Unable to serialize response\"}")
                    .build();
        }
    }

    private APIGatewayV2HTTPResponse response(
            int statusCode, String body, Map<String, String> extraHeaders) {
        Map<String, String> headers = new HashMap<>(extraHeaders);
        headers.put("Content-Type", "application/json");
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(body)
                .build();
    }

    private void safeLog(Context context, String message) {
        if (context != null && context.getLogger() != null) {
            context.getLogger().log(message + " requestId="
                    + context.getAwsRequestId() + "\n");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
