package nz.co.ksktech.ollamamcp.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;

/**
 * Starts a WireMock server stubbing the Ollama HTTP API and points the {@code ollama-api} REST
 * client at it, so {@link nz.co.ksktech.ollamamcp.service.OllamaService} can be exercised against a
 * real HTTP round-trip (validating the DTO ↔ Ollama JSON mapping) — fully offline, no LAN host.
 */
public class WireMockTestResource implements QuarkusTestResourceLifecycleManager {

  private WireMockServer server;

  @Override
  public Map<String, String> start() {
    server = new WireMockServer(options().dynamicPort());
    server.start();

    server.stubFor(
        get(urlEqualTo("/api/tags"))
            .willReturn(
                okJson(
                    "{\"models\":[{\"name\":\"qwen3:8b\",\"model\":\"qwen3:8b\"},"
                        + "{\"name\":\"llama3:8b\",\"model\":\"llama3:8b\"}]}")));

    server.stubFor(
        post(urlEqualTo("/api/generate"))
            .willReturn(okJson("{\"model\":\"qwen3:8b\",\"response\":\"pong\",\"done\":true}")));

    // Override the REST client URL directly (beats application.properties and .env).
    return Map.of("quarkus.rest-client.ollama-api.url", server.baseUrl());
  }

  @Override
  public void stop() {
    if (server != null) {
      server.stop();
    }
  }
}
