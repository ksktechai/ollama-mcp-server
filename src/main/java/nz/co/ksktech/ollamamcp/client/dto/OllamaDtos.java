package nz.co.ksktech.ollamamcp.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * The slice of the Ollama HTTP API this bridge speaks, expressed as small Java records.
 *
 * <p>Conventions, kept deliberately minimal so the whole file reads in a minute:
 *
 * <ul>
 *   <li>Only the fields we actually use are modelled; everything else is dropped via
 *       {@link JsonIgnoreProperties}{@code (ignoreUnknown = true)} on every response type.
 *   <li>Requests always send {@code "stream": false} so a call returns a single JSON object
 *       (no token-by-token streaming to reassemble — MCP tool results are returned whole).
 *   <li>{@link JsonInclude}{@code (NON_NULL)} on requests keeps optional fields off the wire.
 * </ul>
 *
 * <p>See the Ollama API reference: https://github.com/ollama/ollama/blob/main/docs/api.md
 */
public final class OllamaDtos {

  private OllamaDtos() {}

  // --- A chat message ({role, content}) shared by /api/chat request + response. ---------------

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatMessage(String role, String content) {}

  // --- GET /api/tags : list models available on the host -------------------------------------

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ListModelsResponse(List<Model> models) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Model(String name, String model, Long size, String digest, Details details) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Details(
        String family, String parameterSize, String quantizationLevel, String format) {}
  }

  // --- POST /api/show : details for one model -------------------------------------------------

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ShowRequest(String model) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ShowResponse(
      String license,
      String modelfile,
      String parameters,
      String template,
      Map<String, Object> details,
      Map<String, Object> modelInfo) {}

  // --- GET /api/ps : models currently loaded in memory ---------------------------------------

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PsResponse(List<RunningModel> models) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RunningModel(String name, String model, Long size, Long sizeVram, String expiresAt) {}
  }

  // --- POST /api/generate : one-shot completion ----------------------------------------------

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record GenerateRequest(String model, String prompt, Boolean stream) {
    public GenerateRequest(String model, String prompt) {
      this(model, prompt, false);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record GenerateResponse(String model, String response, Boolean done) {}

  // --- POST /api/chat : multi-message chat ---------------------------------------------------

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ChatRequest(String model, List<ChatMessage> messages, Boolean stream) {
    public ChatRequest(String model, List<ChatMessage> messages) {
      this(model, messages, false);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ChatResponse(String model, ChatMessage message, Boolean done) {}

  // --- POST /api/embed : embeddings ----------------------------------------------------------

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record EmbedRequest(String model, List<String> input) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record EmbedResponse(String model, List<List<Double>> embeddings) {}
}
