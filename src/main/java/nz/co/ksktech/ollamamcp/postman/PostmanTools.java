package nz.co.ksktech.ollamamcp.postman;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import nz.co.ksktech.ollamamcp.logging.Logged;
import nz.co.ksktech.ollamamcp.service.OllamaService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * An operational MCP tool that runs a Postman collection with <a
 * href="https://github.com/postmanlabs/newman">Newman</a> and returns a concise pass/fail summary.
 *
 * <p>This is deliberately separate from {@code OllamaTools}: it has nothing to do with Ollama
 * inference — it just shells out to Newman so an MCP client (Postman Agent Mode, Claude Desktop,
 * Cline, …) can trigger a collection run, since those clients can drive an LLM but have no built-in
 * "run a Postman collection" capability.
 *
 * <p>Design choices (matching the rest of this server): no shell is used (args are passed as a
 * list, so there is no command-injection surface), the process output is redirected to a file to
 * avoid pipe deadlocks, the whole run is bounded by a timeout, the Newman JSON report is parsed
 * into a short summary, and the tool <b>never throws</b> — any failure (Newman missing, bad path,
 * timeout) becomes a readable string the caller can act on.
 */
@ApplicationScoped
@Logged
public class PostmanTools {

  @Inject ObjectMapper json;

  /** Used (only when {@code analyze=true}) to summarise the run with the local Ollama model. */
  @Inject OllamaService ollama;

  /** Default collection to run when the caller passes no path. */
  @ConfigProperty(name = "app.newman.collection")
  String defaultCollection;

  /**
   * How Newman is invoked, as a whitespace-separated prefix. Default {@code npx --yes newman}
   * (no global install needed). Set to an absolute path (e.g. {@code /usr/local/bin/newman}) if
   * {@code npx} is not on the server process's PATH.
   */
  @ConfigProperty(name = "app.newman.command", defaultValue = "npx --yes newman")
  String newmanCommand;

  /** Per-request timeout passed to Newman (ms). */
  @ConfigProperty(name = "app.newman.request-timeout-ms", defaultValue = "120000")
  int requestTimeoutMs;

  /** Overall wall-clock budget for the whole run (seconds) before the process is killed. */
  @ConfigProperty(name = "app.newman.run-timeout-seconds", defaultValue = "600")
  int runTimeoutSeconds;

  /**
   * Optional path to keep the full Newman JSON report at (overwritten each run). Blank (default) =
   * write to a temp file and delete it after summarising. Set this if you want to open the full
   * report after a run.
   */
  @ConfigProperty(name = "app.newman.report-path")
  Optional<String> reportPath;

  @Tool(
      name = "run_postman_collection",
      description =
          "Run a Postman collection with Newman and return a pass/fail summary (iterations, "
              + "requests, assertions, per-request results, and any failures). Use this to execute "
              + "a collection's tests from an MCP client. Requires Newman (invoked via "
              + "'npx --yes newman' by default) and the API the collection targets to be running. "
              + "Set analyze=true to also include a short natural-language summary/triage of the "
              + "run from the local Ollama model (advisory — the structured result is the source of "
              + "truth, and analysis adds an extra model call so it is slower).")
  public String run_postman_collection(
      @ToolArg(
              description =
                  "Absolute path to the .json collection. Blank uses the configured default "
                      + "(app.newman.collection).",
              required = false)
          String collectionPath,
      @ToolArg(
              description =
                  "If true, append an 'analysis' field with a local-LLM summary/triage of the run. "
                      + "Default false.",
              required = false)
          Boolean analyze,
      @ToolArg(
              description =
                  "Model for the analysis (only used when analyze=true). Blank uses the default "
                      + "model. A smaller model (e.g. qwen3:8b) is faster.",
              required = false)
          String analysisModel) {
    boolean doAnalyze = Boolean.TRUE.equals(analyze);
    String path = (collectionPath == null || collectionPath.isBlank()) ? defaultCollection : collectionPath.strip();

    File collection = new File(path);
    if (!collection.isFile()) {
      return "{\"error\":\"collection not found\",\"path\":\"" + escape(path) + "\"}";
    }
    if (!path.toLowerCase().endsWith(".json")) {
      return "{\"error\":\"not a .json collection\",\"path\":\"" + escape(path) + "\"}";
    }

    boolean keepReport = reportPath.filter(p -> !p.isBlank()).isPresent();
    Path report;
    Path cliLog;
    try {
      if (keepReport) {
        report = Path.of(reportPath.get().strip());
        if (report.getParent() != null) {
          Files.createDirectories(report.getParent());
        }
      } else {
        report = Files.createTempFile("newman-report-", ".json");
      }
      cliLog = Files.createTempFile("newman-cli-", ".log");
    } catch (Exception e) {
      return "{\"error\":\"could not create report/temp files: " + escape(e.getMessage()) + "\"}";
    }

    List<String> command = new ArrayList<>(Arrays.asList(newmanCommand.trim().split("\\s+")));
    command.add("run");
    command.add(path);
    command.add("--reporters");
    command.add("cli,json");
    command.add("--reporter-json-export");
    command.add(report.toString());
    command.add("--timeout-request");
    command.add(String.valueOf(requestTimeoutMs));

    Log.infof("run_postman_collection: %s", String.join(" ", command));

    try {
      ProcessBuilder pb = new ProcessBuilder(command);
      pb.redirectErrorStream(true);
      pb.redirectOutput(cliLog.toFile());
      Process process = pb.start();

      boolean finished = process.waitFor(runTimeoutSeconds, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        return "{\"error\":\"newman run exceeded "
            + runTimeoutSeconds
            + "s and was killed\",\"collection\":\""
            + escape(path)
            + "\"}";
      }
      int exitCode = process.exitValue();
      ObjectNode result = summarise(path, exitCode, report, cliLog, keepReport);
      if (doAnalyze) {
        addAnalysis(result, analysisModel);
      }
      return result.toString();
    } catch (Exception failure) {
      Log.errorf(failure, "run_postman_collection failed");
      // Most common cause: the newman command isn't on PATH (set app.newman.command).
      return "{\"error\":\"could not run newman (command: '"
          + escape(newmanCommand)
          + "'). Is it installed / on PATH?\",\"detail\":\""
          + escape(rootMessage(failure))
          + "\"}";
    } finally {
      // Keep the report if a stable path was configured; always remove the CLI temp log.
      cleanup(keepReport ? new Path[] {cliLog} : new Path[] {report, cliLog});
    }
  }

  /** Turns the Newman JSON report (and exit code) into a compact summary for the caller. */
  private ObjectNode summarise(
      String collection, int exitCode, Path report, Path cliLog, boolean keepReport) {
    var out = json.createObjectNode();
    out.put("collection", collection);
    out.put("exitCode", exitCode);
    out.put("passed", exitCode == 0);
    if (keepReport) {
      out.put("reportPath", report.toString());
    }

    boolean haveDetail = false;
    try {
      if (Files.size(report) > 0) {
        JsonNode root = json.readTree(report.toFile());
        JsonNode run = root.path("run");

        JsonNode stats = run.path("stats");
        var statsOut = out.putObject("stats");
        for (String group : List.of("iterations", "requests", "assertions", "testScripts")) {
          JsonNode g = stats.path(group);
          if (!g.isMissingNode()) {
            statsOut
                .putObject(group)
                .put("total", g.path("total").asInt())
                .put("failed", g.path("failed").asInt());
          }
        }

        JsonNode timings = run.path("timings");
        if (timings.has("started") && timings.has("completed")) {
          out.put("durationMs", timings.path("completed").asLong() - timings.path("started").asLong());
        }

        // Per-request breakdown, grouped like the Postman Runner view (folder > request).
        Map<String, String> folders = new HashMap<>();
        buildFolderMap(root.path("collection").path("item"), new ArrayList<>(), folders);
        var requests = out.putArray("requests");
        for (JsonNode ex : run.path("executions")) {
          var rq = requests.addObject();
          rq.put("folder", folders.getOrDefault(ex.path("item").path("id").asText(""), ""));
          rq.put("name", ex.path("item").path("name").asText(""));
          rq.put("method", ex.path("request").path("method").asText(""));
          rq.put("url", url(ex.path("request").path("url")));
          JsonNode resp = ex.path("response");
          if (!resp.isMissingNode()) {
            rq.put("code", resp.path("code").asInt());
            rq.put("status", resp.path("status").asText(""));
            rq.put("timeMs", resp.path("responseTime").asInt());
            rq.put("sizeB", resp.path("responseSize").asInt());
          }
          var asserts = rq.putArray("assertions");
          for (JsonNode a : ex.path("assertions")) {
            boolean failed = a.has("error") && !a.path("error").isNull();
            boolean skipped = a.path("skipped").asBoolean(false);
            var ao = asserts.addObject();
            ao.put("name", a.path("assertion").asText(""));
            ao.put("result", skipped ? "SKIP" : failed ? "FAIL" : "PASS");
            if (failed) {
              ao.put("error", a.path("error").path("message").asText(""));
            }
          }
          haveDetail = true;
        }
      }
    } catch (Exception e) {
      out.put("reportParseError", rootMessage(e));
    }

    // Only fall back to the raw CLI tail when we couldn't parse the structured breakdown.
    if (!haveDetail) {
      out.put("cliOutput", truncateTail(readQuietly(cliLog)));
    }

    // One concise outcome line in the server log (the >>> / <<< tracer doesn't log the body).
    Log.infof(
        "run_postman_collection result: passed=%s exitCode=%d stats=%s%s",
        exitCode == 0,
        exitCode,
        out.has("stats") ? out.get("stats").toString() : "(no report)",
        keepReport ? " report=" + report : "");
    return out;
  }

  /**
   * Adds an {@code analysis} field: a short, advisory natural-language summary/triage of the run
   * from the local Ollama model. The structured result stays the source of truth; the model is fed
   * a compact, ground-truth digest (not the raw report) and told to use only that data.
   */
  private void addAnalysis(ObjectNode out, String model) {
    String prompt =
        "You are a senior QA engineer. Analyse this API test run using ONLY the data provided; do"
            + " not invent results. In 4-6 short bullet points cover: the overall verdict, any"
            + " FAILED assertions and their likely cause, any notably slow requests (>1000ms) and"
            + " which endpoint, and one recommended next step. Be concise.\n\nTEST RUN DATA:\n"
            + buildDigest(out);
    String analysis = ollama.generate(prompt, model);
    if (analysis == null) {
      analysis = "";
    }
    // Treat an OllamaService error payload as an analysis error rather than the analysis itself.
    if (analysis.startsWith("{\"error\"")) {
      out.put("analysisError", analysis);
      return;
    }
    // qwen3-style models may emit <think>…</think> reasoning; drop it for a clean summary.
    out.put("analysis", analysis.replaceAll("(?s)<think>.*?</think>", "").strip());
  }

  /** A compact one-line-per-request digest of the structured result, for the analysis prompt. */
  private static String buildDigest(ObjectNode out) {
    StringBuilder sb = new StringBuilder();
    JsonNode st = out.path("stats");
    sb.append("verdict=").append(out.path("passed").asBoolean() ? "PASS" : "FAIL")
        .append(" exitCode=").append(out.path("exitCode").asInt())
        .append(" durationMs=").append(out.path("durationMs").asLong(-1))
        .append(" requests=").append(st.path("requests").path("total").asInt())
        .append("/").append(st.path("requests").path("failed").asInt()).append("failed")
        .append(" assertions=").append(st.path("assertions").path("total").asInt())
        .append("/").append(st.path("assertions").path("failed").asInt()).append("failed\n");
    for (JsonNode r : out.path("requests")) {
      sb.append(r.path("folder").asText("")).append(" | ")
          .append(r.path("method").asText("")).append(" ").append(r.path("name").asText(""))
          .append(" -> ").append(r.path("code").asInt()).append(" ").append(r.path("status").asText(""))
          .append(" ").append(r.path("timeMs").asInt()).append("ms");
      List<String> fails = new ArrayList<>();
      for (JsonNode a : r.path("assertions")) {
        if ("FAIL".equals(a.path("result").asText())) {
          fails.add(a.path("name").asText("") + (a.has("error") ? ": " + a.path("error").asText() : ""));
        }
      }
      if (!fails.isEmpty()) {
        sb.append(" FAILED[").append(String.join("; ", fails)).append("]");
      }
      sb.append("\n");
    }
    return sb.toString();
  }

  /** Walks the collection item tree, mapping each request's id to its "Folder > Subfolder" path. */
  private static void buildFolderMap(JsonNode items, List<String> path, Map<String, String> map) {
    if (!items.isArray()) {
      return;
    }
    for (JsonNode it : items) {
      if (it.has("item")) {
        List<String> next = new ArrayList<>(path);
        next.add(it.path("name").asText(""));
        buildFolderMap(it.path("item"), next, map);
      } else {
        map.put(it.path("id").asText(""), String.join(" > ", path));
      }
    }
  }

  /** Reconstructs a URL string from Newman's structured url object (no {@code raw} is emitted). */
  private static String url(JsonNode u) {
    if (u.isMissingNode() || u.isNull()) {
      return "";
    }
    if (u.isTextual()) {
      return u.asText();
    }
    StringBuilder sb = new StringBuilder();
    String protocol = u.path("protocol").asText("");
    if (!protocol.isEmpty()) {
      sb.append(protocol).append("://");
    }
    List<String> host = new ArrayList<>();
    u.path("host").forEach(h -> host.add(h.asText()));
    sb.append(String.join(".", host));
    String port = u.path("port").asText("");
    if (!port.isEmpty()) {
      sb.append(":").append(port);
    }
    List<String> segments = new ArrayList<>();
    u.path("path").forEach(p -> segments.add(p.asText()));
    if (!segments.isEmpty()) {
      sb.append("/").append(String.join("/", segments));
    }
    return sb.toString();
  }

  private static String readQuietly(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (Exception e) {
      return "";
    }
  }

  /** Keep the last ~4000 chars of CLI output (the summary table lives at the end). */
  private static String truncateTail(String s) {
    int max = 4000;
    if (s.length() <= max) {
      return s;
    }
    return "…(+" + (s.length() - max) + " chars)\n" + s.substring(s.length() - max);
  }

  private static void cleanup(Path... files) {
    for (Path f : files) {
      try {
        Files.deleteIfExists(f);
      } catch (Exception ignored) {
        // best-effort temp cleanup
      }
    }
  }

  private static String rootMessage(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  private static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
  }
}
