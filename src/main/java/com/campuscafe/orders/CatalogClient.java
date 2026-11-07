package com.campuscafe.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class CatalogClient {

    private final String catalogApiUrl;
    private final ObjectMapper mapper;
    private final int timeoutMillis;

    public CatalogClient(
            String catalogApiUrl,
            ObjectMapper mapper,
            int timeoutMillis) {

        if (catalogApiUrl == null || catalogApiUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "CATALOG_API_URL is required"
            );
        }

        if (mapper == null) {
            throw new IllegalArgumentException(
                    "ObjectMapper is required"
            );
        }

        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "Catalog timeout must be positive"
            );
        }

        this.catalogApiUrl = removeTrailingSlash(catalogApiUrl);
        this.mapper = mapper;
        this.timeoutMillis = timeoutMillis;
    }

    public Product getProduct(String productId)
            throws CatalogUnavailableException,
            ProductNotFoundException,
            IOException {

        if (productId == null || productId.isBlank()) {
            throw new ProductNotFoundException(
                    "Product ID is required"
            );
        }

        String encodedProductId = URLEncoder.encode(
                productId,
                StandardCharsets.UTF_8
        ).replace("+", "%20");

        String endpoint = catalogApiUrl
                + "/products/"
                + encodedProductId;

        HttpURLConnection connection = null;

        try {
            URL url = URI.create(endpoint).toURL();

            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setUseCaches(false);
            connection.setRequestProperty(
                    "Accept",
                    "application/json"
            );

            int status = connection.getResponseCode();

            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new ProductNotFoundException(
                        "Product not found: " + productId
                );
            }

            if (status < 200 || status >= 300) {
                throw new CatalogUnavailableException(
                        "Catalog returned HTTP " + status
                );
            }

            try (InputStream input =
                         connection.getInputStream()) {

                JsonNode json = mapper.readTree(input);

                if (json == null || !json.isObject()) {
                    throw new CatalogUnavailableException(
                            "Catalog returned an invalid response"
                    );
                }

                return parseProduct(json, productId);
            }

        } catch (ProductNotFoundException
                 | CatalogUnavailableException e) {

            throw e;

        } catch (Exception e) {
            throw new CatalogUnavailableException(
                    "Catalog request failed",
                    e
            );

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Product parseProduct(
            JsonNode json,
            String requestedProductId) {

        JsonNode productIdNode = json.get("productId");
        JsonNode nameNode = json.get("name");
        JsonNode priceNode = json.get("priceCents");
        JsonNode currencyNode = json.get("currency");
        JsonNode availableNode = json.get("available");

        if (productIdNode == null
                || !productIdNode.isTextual()
                || nameNode == null
                || !nameNode.isTextual()
                || priceNode == null
                || !priceNode.isIntegralNumber()
                || currencyNode == null
                || !currencyNode.isTextual()
                || availableNode == null
                || !availableNode.isBoolean()) {

            throw new IllegalArgumentException(
                    "Catalog product has an invalid schema"
            );
        }

        String productId = productIdNode.asText();

        if (!requestedProductId.equals(productId)) {
            throw new IllegalArgumentException(
                    "Catalog returned an unexpected product"
            );
        }

        int priceCents = priceNode.asInt();

        if (priceCents < 0) {
            throw new IllegalArgumentException(
                    "Catalog returned a negative price"
            );
        }

        return new Product(
                productId,
                nameNode.asText(),
                priceCents,
                currencyNode.asText(),
                availableNode.asBoolean()
        );
    }

    private String removeTrailingSlash(String value) {
        String result = value.trim();

        while (result.endsWith("/")) {
            result = result.substring(
                    0,
                    result.length() - 1
            );
        }

        return result;
    }

    public static final class Product {

        private final String productId;
        private final String name;
        private final int priceCents;
        private final String currency;
        private final boolean available;

        public Product(
                String productId,
                String name,
                int priceCents,
                String currency,
                boolean available) {

            this.productId = productId;
            this.name = name;
            this.priceCents = priceCents;
            this.currency = currency;
            this.available = available;
        }

        public String getProductId() {
            return productId;
        }

        public String getName() {
            return name;
        }

        public int getPriceCents() {
            return priceCents;
        }

        public String getCurrency() {
            return currency;
        }

        public boolean isAvailable() {
            return available;
        }
    }

    public static class CatalogUnavailableException
            extends Exception {

        public CatalogUnavailableException(
                String message) {

            super(message);
        }

        public CatalogUnavailableException(
                String message,
                Throwable cause) {

            super(message, cause);
        }
    }

    public static class ProductNotFoundException
            extends Exception {

        public ProductNotFoundException(
                String message) {

            super(message);
        }
    }
}