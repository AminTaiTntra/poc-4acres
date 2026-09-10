package io.fouracres.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Mints short-lived OAuth2 access tokens for the Earth Engine REST API from a
 * Google service-account key, using the JWT-bearer grant. Pure JDK — no extra
 * dependency. Tokens are cached in-process until shortly before they expire.
 *
 * <p>Configure with {@code app.dynamicworld.service-account-json}: either the raw
 * service-account JSON or a filesystem path to it. When unset, {@link #accessToken()}
 * returns empty and the Dynamic World client degrades gracefully.</p>
 */
@Component
public class GoogleEarthEngineAuth {

    private static final Logger log = LoggerFactory.getLogger(GoogleEarthEngineAuth.class);

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/earthengine.readonly";
    private static final String JWT_BEARER = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    private static final long TOKEN_TTL_SECONDS = 3600;
    private static final long REFRESH_SKEW_SECONDS = 120;

    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final String serviceAccountConfig;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiry = Instant.EPOCH;

    public GoogleEarthEngineAuth(HttpClient httpClient,
                                 ObjectMapper mapper,
                                 @Value("${app.dynamicworld.service-account-json:}") String serviceAccountConfig) {
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.serviceAccountConfig = serviceAccountConfig == null ? "" : serviceAccountConfig.trim();
    }

    public boolean isConfigured() {
        return !serviceAccountConfig.isBlank();
    }

    /** @return a bearer token valid for the Earth Engine REST API, or empty if auth is not configured / failed. */
    public synchronized Optional<String> accessToken() {
        if (!isConfigured()) return Optional.empty();

        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiry)) {
            return Optional.of(cachedToken);
        }
        try {
            JsonNode key = readServiceAccount();
            String assertion = buildSignedJwt(
                key.path("client_email").asText(),
                key.path("private_key").asText());

            String form = "grant_type=" + URLEncoder.encode(JWT_BEARER, StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);

            var request = HttpRequest.newBuilder(URI.create(TOKEN_URI))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() / 100 != 2) {
                log.warn("Earth Engine token exchange failed: HTTP {} {}", response.statusCode(), response.body());
                return Optional.empty();
            }
            JsonNode body = mapper.readTree(response.body());
            String token = body.path("access_token").asText(null);
            long expiresIn = body.path("expires_in").asLong(TOKEN_TTL_SECONDS);
            if (token == null || token.isBlank()) return Optional.empty();

            cachedToken = token;
            cachedTokenExpiry = Instant.now().plusSeconds(Math.max(0, expiresIn - REFRESH_SKEW_SECONDS));
            return Optional.of(token);
        } catch (Exception e) {
            log.warn("Earth Engine auth failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private JsonNode readServiceAccount() throws Exception {
        String raw = serviceAccountConfig;
        if (!raw.startsWith("{")) {
            raw = Files.readString(Path.of(raw), StandardCharsets.UTF_8);
        }
        return mapper.readTree(raw);
    }

    private String buildSignedJwt(String clientEmail, String privateKeyPem) throws Exception {
        long now = Instant.now().getEpochSecond();
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claims = base64Url(("{"
            + "\"iss\":\"" + clientEmail + "\","
            + "\"scope\":\"" + SCOPE + "\","
            + "\"aud\":\"" + TOKEN_URI + "\","
            + "\"iat\":" + now + ","
            + "\"exp\":" + (now + TOKEN_TTL_SECONDS)
            + "}").getBytes(StandardCharsets.UTF_8));

        String signingInput = header + "." + claims;
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(parsePrivateKey(privateKeyPem));
        signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        return signingInput + "." + signature;
    }

    private static PrivateKey parsePrivateKey(String pem) throws Exception {
        String body = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(body);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private static String base64Url(String s) {
        return base64Url(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
