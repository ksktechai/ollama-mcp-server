package nz.co.ksktech.ollamamcp.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ProcessingException;
import java.util.List;
import nz.co.ksktech.ollamamcp.client.OllamaClient;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ChatMessage;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ChatResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.EmbedRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.EmbedResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.GenerateRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.GenerateResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ListModelsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ListModelsResponse.Model;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.PsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ShowRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ShowResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Fast, offline unit tests for {@link OllamaService} — the logic-bearing class. The REST client is
 * a plain Mockito mock (no Quarkus boot), so every branch (default-model fallback, defensive
 * parsing, the "never 500 the caller" error payloads) runs in milliseconds.
 */
class OllamaServiceTest {

  private static final String DEFAULT_MODEL = "default-model";
  private static final String BASE_URL = "http://192.168.1.5:11434";

  private OllamaClient client;
  private OllamaService service;

  @BeforeEach
  void setUp() {
    client = mock(OllamaClient.class);
    service = new OllamaService(client, new ObjectMapper(), DEFAULT_MODEL, BASE_URL);
  }

  // --- ollama_list -----------------------------------------------------------------------------

  @Test
  void listModelsMapsNames() {
    when(client.listModels())
        .thenReturn(
            new ListModelsResponse(
                List.of(
                    new Model("qwen3:8b", "qwen3:8b", 1L, "d1", null),
                    new Model("llama3:8b", "llama3:8b", 2L, "d2", null))));

    assertThat(service.listModels()).isEqualTo("[\"qwen3:8b\",\"llama3:8b\"]");
  }

  @Test
  void listModelsHandlesNullResponseAndNullModels() {
    when(client.listModels()).thenReturn(null);
    assertThat(service.listModels()).isEqualTo("[]");

    when(client.listModels()).thenReturn(new ListModelsResponse(null));
    assertThat(service.listModels()).isEqualTo("[]");
  }

  // --- ollama_show -----------------------------------------------------------------------------

  @Test
  void showSendsResolvedModelAndReturnsJson() {
    when(client.show(any()))
        .thenReturn(new ShowResponse("MIT", "FROM x", "params", "tmpl", null, null));

    String out = service.show("  "); // blank -> default model
    assertThat(out).contains("\"license\":\"MIT\"");

    ArgumentCaptor<ShowRequest> captor = ArgumentCaptor.forClass(ShowRequest.class);
    org.mockito.Mockito.verify(client).show(captor.capture());
    assertThat(captor.getValue().model()).isEqualTo(DEFAULT_MODEL);
  }

  // --- ollama_ps -------------------------------------------------------------------------------

  @Test
  void psReturnsJsonAndHandlesNull() {
    when(client.ps()).thenReturn(new PsResponse(List.of()));
    assertThat(service.ps()).contains("\"models\"");

    when(client.ps()).thenReturn(null);
    assertThat(service.ps()).isEqualTo("{\"models\":[]}");
  }

  // --- ollama_generate -------------------------------------------------------------------------

  @Test
  void generateReturnsTextAndUsesProvidedModel() {
    when(client.generate(any())).thenReturn(new GenerateResponse("m", "hello world", true));

    assertThat(service.generate("hi", "qwen3:30b")).isEqualTo("hello world");

    ArgumentCaptor<GenerateRequest> captor = ArgumentCaptor.forClass(GenerateRequest.class);
    org.mockito.Mockito.verify(client).generate(captor.capture());
    assertThat(captor.getValue().model()).isEqualTo("qwen3:30b");
    assertThat(captor.getValue().stream()).isFalse();
  }

  @Test
  void generateHandlesNullResponseAndNullText() {
    when(client.generate(any())).thenReturn(null);
    assertThat(service.generate("hi", null)).isEmpty();

    when(client.generate(any())).thenReturn(new GenerateResponse("m", null, true));
    assertThat(service.generate("hi", null)).isEmpty();
  }

  // --- ollama_chat -----------------------------------------------------------------------------

  @Test
  void chatParsesMessagesAndReturnsReply() {
    when(client.chat(any())).thenReturn(new ChatResponse("m", new ChatMessage("assistant", "hi!"), true));

    assertThat(service.chat("[{\"role\":\"user\",\"content\":\"hey\"}]", "")).isEqualTo("hi!");
  }

  @Test
  void chatHandlesNullMessage() {
    when(client.chat(any())).thenReturn(new ChatResponse("m", null, true));
    assertThat(service.chat("[]", null)).isEmpty();
  }

  @Test
  void chatWithMalformedJsonReturnsReadableErrorAndNeverCallsClient() {
    String out = service.chat("{ not an array", null);
    assertThat(out).isEqualTo("{\"error\":\"messagesJson must be a JSON array of {role,content}\"}");
    org.mockito.Mockito.verifyNoInteractions(client);
  }

  // --- ollama_embed ----------------------------------------------------------------------------

  @Test
  void embedAcceptsBareString() {
    when(client.embed(any())).thenReturn(new EmbedResponse("m", List.of(List.of(0.1, 0.2))));

    assertThat(service.embed("hello", "")).contains("\"embeddings\"").contains("0.1");

    ArgumentCaptor<EmbedRequest> captor = ArgumentCaptor.forClass(EmbedRequest.class);
    org.mockito.Mockito.verify(client).embed(captor.capture());
    assertThat(captor.getValue().input()).containsExactly("hello");
  }

  @Test
  void embedAcceptsJsonArrayOfStrings() {
    when(client.embed(any())).thenReturn(new EmbedResponse("m", List.of()));

    service.embed("[\"a\",\"b\"]", null);

    ArgumentCaptor<EmbedRequest> captor = ArgumentCaptor.forClass(EmbedRequest.class);
    org.mockito.Mockito.verify(client).embed(captor.capture());
    assertThat(captor.getValue().input()).containsExactly("a", "b");
  }

  @Test
  void embedFallsBackForEmptyArrayInvalidArrayAndNull() {
    when(client.embed(any())).thenReturn(new EmbedResponse("m", List.of()));

    service.embed("[]", null); // parses to empty -> treat whole string as one input
    service.embed("[oops not json", null); // looks like array, fails to parse -> single input
    service.embed(null, null); // null -> single empty input

    ArgumentCaptor<EmbedRequest> captor = ArgumentCaptor.forClass(EmbedRequest.class);
    org.mockito.Mockito.verify(client, org.mockito.Mockito.times(3)).embed(captor.capture());
    List<EmbedRequest> calls = captor.getAllValues();
    assertThat(calls.get(0).input()).containsExactly("[]");
    assertThat(calls.get(1).input()).containsExactly("[oops not json");
    assertThat(calls.get(2).input()).containsExactly("");
  }

  // --- transport failure -> readable error payload naming the base URL -------------------------

  @Test
  void clientFailureReturnsCantReachPayloadNamingBaseUrl() {
    // message carries a quote, backslash and newline to exercise the JSON escaping.
    Throwable root = new RuntimeException("Connection \"refused\"\\path\nretry");
    when(client.listModels()).thenThrow(new ProcessingException("wrapper", root));

    String out = service.listModels();
    assertThat(out)
        .contains("\"error\":\"can't reach Ollama at " + BASE_URL + "\"")
        .contains("\\\"refused\\\"") // quotes escaped
        .doesNotContain("\n"); // newline flattened
  }
}
