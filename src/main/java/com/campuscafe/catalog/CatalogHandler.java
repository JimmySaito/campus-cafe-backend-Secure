package com.campuscafe.catalog;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import java.util.*;

public class CatalogHandler implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private final DynamoDbClient dbClient = DynamoDbClient.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .build();
    private final String tableName = System.getenv("CATALOG_TABLE");
    private final ObjectMapper mapper = new ObjectMapper();
    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        String httpMethod = event.getRequestContext().getHttp().getMethod();
        Map<String, String> pathParams = event.getPathParameters();

        try {
            if ("GET".equalsIgnoreCase(httpMethod)) {
                if (pathParams != null && pathParams.containsKey("productId")) {
                    return getProductById(pathParams.get("productId"));
                } else {
                    return listProducts();
                }
            }
            return buildResponse(405, Map.of("message", "Method Not Allowed"));
        } catch (Exception e) {
            context.getLogger().log("Error in CatalogHandler: " + e.getMessage());
            return buildResponse(503, Map.of("code", "DEPENDENCY_FAILURE", "message", "Error accessing store"));
        }
    }

    private APIGatewayV2HTTPResponse listProducts() throws Exception {
        ScanRequest scanRequest = ScanRequest.builder().tableName(tableName).build();
        ScanResponse response = dbClient.scan(scanRequest);

        List items = new ArrayList();
        for (Map<String, AttributeValue> item : response.items()) {
            items.add(parseItem(item));
        }

        return buildResponse(200, Map.of("items", items));
    }

    private APIGatewayV2HTTPResponse getProductById(String productId) throws Exception {
        GetItemRequest request = GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of("productId", AttributeValue.builder().s(productId).build()))
                .build();

        Map<String, AttributeValue> item = dbClient.getItem(request).item();

        if (item == null || item.isEmpty()) {
            return buildResponse(404, Map.of("code", "NOT_FOUND", "message", "Product not found"));
        }

        return buildResponse(200, parseItem(item));
    }

    private Map<String, Object> parseItem(Map<String, AttributeValue> item) {
        Map<String, Object> product = new HashMap<String, Object>();
        product.put("productId", item.get("productId").s());
        product.put("name", item.get("name").s());
        product.put("priceCents", Integer.parseInt(item.get("priceCents").n()));
        product.put("currency", item.get("currency").s());
        product.put("available", item.get("available").bool());
        return product;
    }

    private APIGatewayV2HTTPResponse buildResponse(int statusCode, Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of(
                            "Content-Type", "application/json",
                            "Access-Control-Allow-Origin", "*"
                    ))
                    .withBody(mapper.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder().withStatusCode(500).build();
        }
    }
}