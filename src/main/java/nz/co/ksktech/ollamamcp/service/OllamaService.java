package nz.co.ksktech.ollamamcp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import nz.co.ksktech.ollamamcp.client.OllamaClient;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ChatMessage;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ChatRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.EmbedRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.GenerateRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ListModelsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.PsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ShowRequest;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The brains of the bridge: default-model fallback, building Ollama request DTOs, calling
 * {@link OllamaClient}, and turning responses (or failures) into the plain String/JSON that the
 * MCP tools hand back to the caller.
 *
 * <p>Design rule borrowed from bian-fraud-detection: <b>parse safely, never 500 the caller</b>.
 * Any Ollama failure (host down, timeout, bad input) is caught here and rendered as a readable
 * JSON error payload that names the base URL, so a Postman user sees "can't reach
 * http://192.168.1.5:11434" rather than a stack trace. The tools stay thin; the parsing lives
 * here.
 */
@ApplicationScoped
public class OllamaService {

  private final OllamaClient ollama;
  private final ObjectMapper json;
  private final String defaultModel;
  private final String baseUrl;

  @Inject
  public OllamaService(
      @RestClient OllamaClient ollama,
      ObjectMapper json,
      @ConfigProperty(name = "ollama.default-model") String defaultModel,
      @ConfigProperty(name = "ollama.base-url") String baseUrl) {
    this.ollama = ollama;
    this.json = json;
    this.defaultModel = defaultModel;
    this.baseUrl = baseUrl;
  }

  // --- Tool-facing operations. Each returns a ready-to-send String (text or JSON). -----------

  /** List the models available on the remote host as a JSON array of names. */
  public String listModels() {
    return call(
        () -> {
          ListModelsResponse resp = ollama.listModels();
          List<String> names =
              resp == null || resp.models() == null
                  ? List.of()
                  : resp.models().stream().map(ListModelsResponse.Model::name).toList();
          return toJson(names);
        });
  }

  /** Details for one model (license, parameters, template, model info) as JSON. */
  public String show(String model) {
    return call(() -> toJson(ollama.show(new ShowRequest(resolveModel(model)))));
  }

  /** Models currently loaded in memory on the remote host, as JSON. */
  public String ps() {
    return call(
        () -> {
          PsResponse resp = ollama.ps();
          return toJson(resp == null ? new PsResponse(List.of()) : resp);
        });
  }

  /** One-shot completion: returns just the generated text. */
  public String generate(String prompt, String model) {
    return call(
        () -> {
          var resp = ollama.generate(new GenerateRequest(resolveModel(model), prompt));
          return resp == null || resp.response() == null ? "" : resp.response();
        });
  }

  /**
   * Multi-message chat. {@code messagesJson} is a JSON array of {@code {role, content}} objects;
   * it is parsed defensively here — a malformed payload returns a clear error string rather than
   * throwing.
   */
  public String chat(String messagesJson, String model) {
    List<ChatMessage> messages;
    try {
      messages = json.readValue(messagesJson, new TypeReference<List<ChatMessage>>() {});
    } catch (Exception parseFailure) {
      Log.warnf("ollama_chat: could not parse messagesJson: %s", parseFailure.getMessage());
      return "{\"error\":\"messagesJson must be a JSON array of {role,content}\"}";
    }
    return call(
        () -> {
          var resp = ollama.chat(new ChatRequest(resolveModel(model), messages));
          return resp == null || resp.message() == null ? "" : resp.message().content();
        });
  }

  /**
   * Embeddings for {@code input}, which may be a bare string or a JSON array of strings. The input
   * is normalised to a list here; the returned JSON contains the embedding vector(s).
   */
  public String embed(String input, String model) {
    List<String> inputs = normaliseEmbedInput(input);
    return call(() -> toJson(ollama.embed(new EmbedRequest(resolveModel(model), inputs))));
  }

  // --- helpers -------------------------------------------------------------------------------

  /** Falls back to {@code ollama.default-model} when the caller leaves the model blank. */
  private String resolveModel(String model) {
    return model == null || model.isBlank() ? defaultModel : model.strip();
  }

  /**
   * Accepts either a JSON array of strings ({@code ["a","b"]}) or a bare/plain string, returning a
   * non-empty list. A non-array string is treated as a single input.
   */
  private List<String> normaliseEmbedInput(String input) {
    String trimmed = input == null ? "" : input.strip();
    if (trimmed.startsWith("[")) {
      try {
        List<String> parsed = json.readValue(trimmed, new TypeReference<List<String>>() {});
        if (parsed != null && !parsed.isEmpty()) {
          return parsed;
        }
      } catch (Exception ignored) {
        // not a JSON array of strings — fall through and treat the whole thing as one input
      }
    }
    return List.of(input == null ? "" : input);
  }

  /** Serialise a value to JSON, degrading to a readable error payload if that ever fails. */
  private String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      return "{\"error\":\"could not serialise Ollama response: " + escape(e.getMessage()) + "\"}";
    }
  }

  /**
   * Runs an Ollama call, converting any failure (connection refused, timeout, 5xx) into a readable
   * JSON error that names the base URL — the single place transport errors become caller-friendly
   * text instead of a 500 / stack trace.
   */
  private String call(OllamaCall action) {
    try {
      return action.run();
    } catch (Exception failure) {
      Log.errorf(failure, "Ollama call failed against %s", baseUrl);
      return "{\"error\":\"can't reach Ollama at "
          + escape(baseUrl)
          + "\",\"detail\":\""
          + escape(rootMessage(failure))
          + "\"}";
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    String msg = cause.getMessage();
    return (msg == null ? cause.getClass().getSimpleName() : msg);
  }

  /** Minimal JSON string escaping for the hand-built error payloads above. */
  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
  }

  /** A unit of work that talks to Ollama and may throw. */
  @FunctionalInterface
  private interface OllamaCall {
    String run() throws Exception;
  }
}
