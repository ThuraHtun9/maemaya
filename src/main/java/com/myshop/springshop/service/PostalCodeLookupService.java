package com.myshop.springshop.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

@Service
public class PostalCodeLookupService {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public PostalCodeLookupService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
    }

    public String normalize(String zipCode) {
        if (zipCode == null) {
            return "";
        }
        return zipCode.replaceAll("\\D", "");
    }

    public boolean isValid(String normalizedZipCode) {
        return normalizedZipCode != null && normalizedZipCode.matches("\\d{7}");
    }

    public Optional<String> findAddressByZipCode(String normalizedZipCode) {
        if (!isValid(normalizedZipCode)) {
            return Optional.empty();
        }

        try {
            String encodedZipCode = URLEncoder.encode(normalizedZipCode, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://zipcloud.ibsnet.co.jp/api/search?zipcode=" + encodedZipCode))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );

            if (response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return Optional.empty();
            }

            JsonNode first = results.get(0);
            String address = String.join(
                    " ",
                    read(first, "address1"),
                    read(first, "address2"),
                    read(first, "address3")
            ).trim();

            if (!StringUtils.hasText(address)) {
                return Optional.empty();
            }

            return Optional.of(address);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private String read(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.asText("");
    }
}
