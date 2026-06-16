package nz.co.ksktech.ollamamcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpSseTestClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import nz.co.ksktech.ollamamcp.client.OllamaClient;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.GenerateResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ListModelsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ListModelsResponse.Model;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;

/**
 * Drives the MCP tools end-to-end over the SSE transport with the Ollama REST client mocked, so the
 * whole chain (MCP transport → {@code OllamaTools} → {@code OllamaService} → {@code OllamaClient})
 * is exercised offline and deterministically — no LAN host (192.168.1.5) is ever called.
 *
 * <p>Covers the four behaviours from the spec: a stubbed completion, model-name mapping, the
 * "parse safely, never 500" error payload for malformed chat input, and the "can't reach
 * &lt;base-url&gt;" payload when the remote Ollama is unreachable.
 */
@QuarkusTest
class OllamaToolCallTest {

  // Mock the typed REST client. Both annotations are required to replace a @RegisterRestClient bean.
  @InjectMock @RestClient OllamaClient ollama;

  // The configured base URL. Asserting against this (rather than a hard-coded value) keeps the
  // test correct whether or not a local .env overrides ollama.base-url.
  @Inject
  @ConfigProperty(name = "ollama.base-url")
  String baseUrl;

  @Test
  void ollamaGenerateReturnsStubbedText() {
    when(ollama.generate(any()))
        .thenReturn(new GenerateResponse("test-model", "stubbed completion text", true));

    callTool(
        "ollama_generate",
        Map.of("prompt", "say hi"),
        text -> assertThat(text).isEqualTo("stubbed completion text"));
  }

  @Test
  void ollamaListMapsTagsIntoModelNames() {
    when(ollama.listModels())
        .thenReturn(
            new ListModelsResponse(
                List.of(
                    new Model("qwen3:30b", "qwen3:30b", 1L, "d1", null),
                    new Model("llama3:8b", "llama3:8b", 2L, "d2", null))));

    callTool(
        "ollama_list",
        Map.of(),
        text -> assertThat(text).contains("qwen3:30b").contains("llama3:8b"));
  }

  @Test
  void ollamaChatWithMalformedJsonReturnsReadableError() {
    callTool(
        "ollama_chat",
        Map.of("messagesJson", "{ this is not a json array"),
        text ->
            assertThat(text)
                .contains("error")
                .contains("messagesJson must be a JSON array of {role,content}"));
  }

  @Test
  void unreachableHostReturnsCantReachPayload() {
    when(ollama.listModels())
        .thenThrow(new ProcessingException("Connection refused: no further information"));

    callTool(
        "ollama_list",
        Map.of(),
        // The payload must name the configured base URL so a Postman user sees what's unreachable.
        text -> assertThat(text).contains("can't reach Ollama at " + baseUrl));
  }

  /** Connects an SSE MCP client, calls one tool, asserts on its text result, and disconnects. */
  private void callTool(String tool, Map<String, Object> args, Consumer<String> assertText) {
    McpSseTestClient client = McpAssured.newConnectedSseClient();
    client
        .when()
        .toolsCall(
            tool, args, response -> assertText.accept(response.firstContent().asText().text()))
        .thenAssertResults();
    client.disconnect();
  }
}
