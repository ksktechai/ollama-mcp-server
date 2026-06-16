package nz.co.ksktech.ollamamcp.tools;

import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import nz.co.ksktech.ollamamcp.logging.Logged;
import nz.co.ksktech.ollamamcp.service.OllamaService;

/**
 * The MCP tool surface of this bridge. Names mirror the well-known {@code rawveg/ollama-mcp} tools
 * so they feel familiar to existing users. Each method is a thin, well-described pass-through to
 * {@link OllamaService}; the descriptions and {@link ToolArg} text are what Postman / Claude
 * Desktop / Cline show the user.
 *
 * <p>Kept intentionally tiny: no parsing, no error handling, no logging lives here. Defensive
 * parsing and "never 500 the caller" error payloads live in {@link OllamaService}; the
 * {@code >>> / <<<} call tracing comes from {@link Logged}. The model argument is always optional —
 * blank falls back to {@code ollama.default-model}.
 */
@ApplicationScoped
@Logged
public class OllamaTools {

  @Inject OllamaService ollama;

  @Tool(
      name = "ollama_list",
      description =
          "List the models available on the remote Ollama host (GET /api/tags). Returns a JSON "
              + "array of model names. Call this first to confirm the host is reachable.")
  public String ollama_list() {
    return ollama.listModels();
  }

  @Tool(
      name = "ollama_show",
      description =
          "Show details for one model (POST /api/show): license, parameters, template and model "
              + "info, as JSON.")
  public String ollama_show(
      @ToolArg(description = "Model name, e.g. 'qwen3:30b'. Blank uses the configured default.")
          String model) {
    return ollama.show(model);
  }

  @Tool(
      name = "ollama_ps",
      description =
          "List the models currently loaded in memory on the remote host (GET /api/ps), as JSON.")
  public String ollama_ps() {
    return ollama.ps();
  }

  @Tool(
      name = "ollama_generate",
      description =
          "One-shot completion (POST /api/generate, stream=false). Returns the generated text.")
  public String ollama_generate(
      @ToolArg(description = "The prompt to complete.") String prompt,
      @ToolArg(description = "Model name. Blank uses the configured default.", required = false)
          String model) {
    return ollama.generate(prompt, model);
  }

  @Tool(
      name = "ollama_chat",
      description =
          "Multi-message chat (POST /api/chat, stream=false). Returns the assistant reply text. "
              + "A malformed messagesJson returns a readable error, not a 500.")
  public String ollama_chat(
      @ToolArg(
              description =
                  "A JSON array of chat messages, each {\"role\":\"user|assistant|system\","
                      + "\"content\":\"...\"}.")
          String messagesJson,
      @ToolArg(description = "Model name. Blank uses the configured default.", required = false)
          String model) {
    return ollama.chat(messagesJson, model);
  }

  @Tool(
      name = "ollama_embed",
      description =
          "Embeddings (POST /api/embed). 'input' may be a single string or a JSON array of "
              + "strings. Returns the embedding vector(s) as JSON.")
  public String ollama_embed(
      @ToolArg(description = "A string, or a JSON array of strings, to embed.") String input,
      @ToolArg(description = "Model name. Blank uses the configured default.", required = false)
          String model) {
    return ollama.embed(input, model);
  }
}
