package io.fouracres.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.net.http.HttpClient;

@Configuration
public class HttpClientConfig {
    @Bean
    public HttpClient httpClient() {
        // HttpClient.newHttpClient() defaults to preferring HTTP/2. Over plain
        // http:// (not https://) it does this by sending an HTTP/1.1 request with
        // "Connection: Upgrade" / "Upgrade: h2c" headers, attempting a cleartext
        // HTTP/2 upgrade. Node's plain HTTP server (what Next.js's standalone
        // server.js runs on, used for the GeoEngineClient calls) doesn't handle
        // that negotiation and just closes the connection without responding —
        // producing a deterministic "header parser received no bytes" IOException
        // on every request. Forcing HTTP/1.1 avoids the upgrade attempt entirely;
        // it's also fine for the other (HTTPS) clients, which negotiate via ALPN
        // during the TLS handshake regardless of this preference.
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }
}
