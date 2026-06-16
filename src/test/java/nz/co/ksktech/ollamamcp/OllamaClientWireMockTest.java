package nz.co.ksktech.ollamamcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import nz.co.ksktech.ollamamcp.service.OllamaService;
import nz.co.ksktech.ollamamcp.testsupport.WireMockTestResource;
import org.junit.jupiter.api.Test;

/**
 * Integration test exercising the REAL typed {@link nz.co.ksktech.ollamamcp.client.OllamaClient}
 * against a WireMock-stubbed Ollama API. Unlike the {@code @InjectMock} tests, this validates that
 * the request/response DTOs actually (de)serialise against Ollama's JSON shapes over HTTP.
 */
@QuarkusTest
@QuarkusTestResource(WireMockTestResource.class)
class OllamaClientWireMockTest {

  @Inject OllamaService ollama;

  @Test
  void listModelsDeserialisesTagsResponse() {
    assertThat(ollama.listModels()).contains("qwen3:8b").contains("llama3:8b");
  }

  @Test
  void generateSerialisesRequestAndReadsResponse() {
    assertThat(ollama.generate("ping", "qwen3:8b")).isEqualTo("pong");
  }
}
