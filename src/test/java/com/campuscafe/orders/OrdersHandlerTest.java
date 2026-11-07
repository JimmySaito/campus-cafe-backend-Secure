package com.campuscafe.orders;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrdersHandlerTest {

    private static final String ORDERS_TABLE = "orders-test";
    private static final String IDEMPOTENCY_TABLE = "idempotency-test";
    private static final String ISSUER = "https://campus-cafe.example/";
    private static final String SUBJECT = "student-123";
    private static final String KEY = "client-key-001";
    private static final String BODY =
            "{\"items\":[{\"productId\":\"p1\",\"quantity\":2}]}";
    private static final String CANONICAL_BODY =
            "{\"items\":[{\"productId\":\"p1\",\"quantity\":2}]}";

    private DynamoDbClient db;
    private CatalogClient catalog;
    private ObjectMapper mapper;
    private Context context;

    @BeforeEach
    void setUp() {
        db = mock(DynamoDbClient.class);
        catalog = mock(CatalogClient.class);
        mapper = new ObjectMapper();
        context = mock(Context.class);
    }

    @Test
    void missingAuthorizerFailsClosed() {
        OrdersHandler handler = handler();
        APIGatewayV2HTTPEvent event = mock(APIGatewayV2HTTPEvent.class);
        APIGatewayV2HTTPEvent.RequestContext requestContext =
                mock(APIGatewayV2HTTPEvent.RequestContext.class);

        when(event.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAuthorizer()).thenReturn(null);

        APIGatewayV2HTTPResponse response =
                handler.handleRequest(event, context);

        assertEquals(401, response.getStatusCode());
        verifyNoInteractions(db, catalog);
    }

    @Test
    void identicalRequestReplaysStoredResponseWithoutCallingCatalog() throws Exception {
        String originalResponse = "{\"orderId\":\"order-original\","
                + "\"status\":\"CREATED\",\"totalCents\":1800}";
        Map<String, AttributeValue> stored = idempotencyRecord(
                ISSUER, SUBJECT, requestHash(), "order-original", originalResponse);

        when(db.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(stored).build());

        APIGatewayV2HTTPResponse response = handler().handleRequest(
                postEvent(ISSUER, SUBJECT, KEY, BODY), context);

        assertEquals(201, response.getStatusCode());
        assertEquals(originalResponse, response.getBody());
        assertEquals("true", response.getHeaders().get("Idempotency-Replayed"));
        assertEquals("/orders/order-original", response.getHeaders().get("Location"));
        verify(db, times(1)).getItem(any(GetItemRequest.class));
        verify(db, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
        verifyNoInteractions(catalog);
    }

    @Test
    void sameKeyWithDifferentPayloadReturnsConflictAndDoesNotWrite() throws Exception {
        Map<String, AttributeValue> stored = idempotencyRecord(
                ISSUER, SUBJECT, requestHash(), "order-original",
                "{\"orderId\":\"order-original\"}");

        when(db.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(stored).build());

        String changedBody =
                "{\"items\":[{\"productId\":\"p1\",\"quantity\":3}]}";
        APIGatewayV2HTTPResponse response = handler().handleRequest(
                postEvent(ISSUER, SUBJECT, KEY, changedBody), context);

        assertEquals(409, response.getStatusCode());
        assertTrue(response.getBody().contains("IDEMPOTENCY_KEY_REUSED"));
        verify(db, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
        verifyNoInteractions(catalog);
    }

    @Test
    void successfulCreationWritesOrderAndIdempotencyRecordInOneTransaction()
            throws Exception {
        when(db.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(catalog.getProduct("p1")).thenReturn(
                new CatalogClient.Product("p1", "Coffee", 900, "COP", true));

        APIGatewayV2HTTPResponse response = handler().handleRequest(
                postEvent(ISSUER, SUBJECT, KEY, BODY), context);

        assertEquals(201, response.getStatusCode());
        ArgumentCaptor<TransactWriteItemsRequest> captor =
                ArgumentCaptor.forClass(TransactWriteItemsRequest.class);
        verify(db, times(1)).transactWriteItems(captor.capture());

        TransactWriteItemsRequest transaction = captor.getValue();
        assertEquals(2, transaction.transactItems().size());

        var firstPut = transaction.transactItems().get(0).put();
        var secondPut = transaction.transactItems().get(1).put();

        assertEquals(ORDERS_TABLE, firstPut.tableName());
        assertEquals("attribute_not_exists(orderId)", firstPut.conditionExpression());
        assertEquals(IDEMPOTENCY_TABLE, secondPut.tableName());
        assertEquals("attribute_not_exists(idempotencyId)",
                secondPut.conditionExpression());

        assertEquals("1800", firstPut.item().get("totalCents").n());
        assertEquals(ISSUER, firstPut.item().get("ownerIssuer").s());
        assertEquals(SUBJECT, secondPut.item().get("ownerSubject").s());
        assertEquals(requestHash(), secondPut.item().get("requestHash").s());
    }

    @Test
    void catalogFailureDoesNotPersistOrderOrIdempotencyRecord() throws Exception {
        when(db.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(catalog.getProduct("p1")).thenThrow(
                new CatalogClient.CatalogUnavailableException("catalog down"));

        APIGatewayV2HTTPResponse response = handler().handleRequest(
                postEvent(ISSUER, SUBJECT, KEY, BODY), context);

        assertEquals(503, response.getStatusCode());
        assertTrue(response.getBody().contains("CATALOG_UNAVAILABLE"));
        verify(db, never()).transactWriteItems(any(TransactWriteItemsRequest.class));
        verify(db, times(1)).getItem(any(GetItemRequest.class));
    }

    @Test
    void sameClientKeyUsedByDifferentPrincipalsHasDifferentStorageKeys()
            throws Exception {
        when(db.getItem(any(GetItemRequest.class)))
                .thenReturn(GetItemResponse.builder().item(Map.of()).build());
        when(catalog.getProduct("p1")).thenReturn(
                new CatalogClient.Product("p1", "Coffee", 900, "COP", true));

        APIGatewayV2HTTPResponse first = handler().handleRequest(
                postEvent(ISSUER, "student-A", KEY, BODY), context);
        APIGatewayV2HTTPResponse second = handler().handleRequest(
                postEvent(ISSUER, "student-B", KEY, BODY), context);

        assertEquals(201, first.getStatusCode());
        assertEquals(201, second.getStatusCode());

        ArgumentCaptor<GetItemRequest> captor =
                ArgumentCaptor.forClass(GetItemRequest.class);
        verify(db, times(2)).getItem(captor.capture());

        String firstId = captor.getAllValues().get(0).key()
                .get("idempotencyId").s();
        String secondId = captor.getAllValues().get(1).key()
                .get("idempotencyId").s();

        assertNotEquals(firstId, secondId);
        verify(db, times(2)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void transactionRaceReplaysWinnerAfterConsistentReadFindsIt() throws Exception {
        String originalResponse = "{\"orderId\":\"winner-order\","
                + "\"status\":\"CREATED\"}";
        Map<String, AttributeValue> winner = idempotencyRecord(
                ISSUER, SUBJECT, requestHash(), "winner-order", originalResponse);

        when(db.getItem(any(GetItemRequest.class)))
                .thenReturn(
                        GetItemResponse.builder().item(Map.of()).build(),
                        GetItemResponse.builder().item(winner).build());
        when(catalog.getProduct("p1")).thenReturn(
                new CatalogClient.Product("p1", "Coffee", 900, "COP", true));
        when(db.transactWriteItems(any(TransactWriteItemsRequest.class)))
                .thenThrow(TransactionCanceledException.builder()
                        .message("conditional transaction conflict")
                        .build());

        APIGatewayV2HTTPResponse response = handler().handleRequest(
                postEvent(ISSUER, SUBJECT, KEY, BODY), context);

        assertEquals(201, response.getStatusCode());
        assertEquals(originalResponse, response.getBody());
        assertEquals("true", response.getHeaders().get("Idempotency-Replayed"));
        verify(db, times(2)).getItem(any(GetItemRequest.class));
        verify(db, times(1)).transactWriteItems(any(TransactWriteItemsRequest.class));
    }

    @Test
    void clientSuppliedPriceFieldsAreRejectedBeforeDatabaseAccess() {
        String bodyWithPrice =
                "{\"items\":[{\"productId\":\"p1\",\"quantity\":2,"
                        + "\"unitPriceCents\":1}]}";

        APIGatewayV2HTTPResponse response = handler().handleRequest(
                postEvent(ISSUER, SUBJECT, KEY, bodyWithPrice), context);

        assertEquals(400, response.getStatusCode());
        verifyNoInteractions(db, catalog);
    }

    private OrdersHandler handler() {
        return new OrdersHandler(
                db, ORDERS_TABLE, IDEMPOTENCY_TABLE, mapper, catalog);
    }

    private APIGatewayV2HTTPEvent postEvent(
            String issuer, String subject, String key, String body) {
        APIGatewayV2HTTPEvent event = mock(APIGatewayV2HTTPEvent.class);
        APIGatewayV2HTTPEvent.RequestContext requestContext =
                mock(APIGatewayV2HTTPEvent.RequestContext.class);
        APIGatewayV2HTTPEvent.RequestContext.Authorizer authorizer =
                mock(APIGatewayV2HTTPEvent.RequestContext.Authorizer.class);
        APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT jwt =
                mock(APIGatewayV2HTTPEvent.RequestContext.Authorizer.JWT.class);

        when(event.getRouteKey()).thenReturn("POST /orders");
        when(event.getHeaders()).thenReturn(Map.of("Idempotency-Key", key));
        when(event.getBody()).thenReturn(body);
        when(event.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAuthorizer()).thenReturn(authorizer);
        when(authorizer.getJwt()).thenReturn(jwt);
        when(jwt.getClaims()).thenReturn(Map.of(
                "iss", issuer,
                "sub", subject));
        return event;
    }

    private Map<String, AttributeValue> idempotencyRecord(
            String issuer,
            String subject,
            String hash,
            String orderId,
            String responseBody) {
        return Map.of(
                "ownerIssuer", AttributeValue.builder().s(issuer).build(),
                "ownerSubject", AttributeValue.builder().s(subject).build(),
                "requestHash", AttributeValue.builder().s(hash).build(),
                "orderId", AttributeValue.builder().s(orderId).build(),
                "responseBody", AttributeValue.builder().s(responseBody).build());
    }

    private String requestHash() throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(CANONICAL_BODY.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(bytes);
    }
}
