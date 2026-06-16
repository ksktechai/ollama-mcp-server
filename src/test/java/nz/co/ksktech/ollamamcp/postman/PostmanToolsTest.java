package nz.co.ksktech.ollamamcp.postman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import nz.co.ksktech.ollamamcp.service.OllamaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link PostmanTools}. Newman is replaced by a tiny stub shell script (passed via
 * {@code app.newman.command}) that writes a canned Newman JSON report — so the whole run + report
 * parsing + analysis path runs offline and deterministically, with no real Newman and no API.
 */
class PostmanToolsTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @TempDir Path tmp;

  private OllamaService ollama;
  private Path stub;
  private Path collection;
  private Path passReport;
  private Path failReport;

  @BeforeEach
  void setUp() throws Exception {
    ollama = mock(OllamaService.class);

    // A stub that mimics `newman run …`: copy a fixture report to the --reporter-json-export path,
    // then exit with the requested code. Invoked as: bash stub <fixture> <exit> run <collection> …
    stub = tmp.resolve("stub-newman.sh");
    Files.writeString(
        stub,
        """
        #!/usr/bin/env bash
        FIXTURE="$1"; EXIT="$2"; shift 2
        OUT=""
        while [ $# -gt 0 ]; do
          if [ "$1" = "--reporter-json-export" ]; then OUT="$2"; fi
          shift
        done
        [ -n "$OUT" ] && cp "$FIXTURE" "$OUT"
        exit "$EXIT"
        """);

    collection = tmp.resolve("collection.json");
    Files.writeString(collection, "{}");

    passReport = tmp.resolve("pass.json");
    Files.writeString(passReport, report(0, false));
    failReport = tmp.resolve("fail.json");
    Files.writeString(failReport, report(1, true));
  }

  /** A minimal but Newman-shaped report: one folder, two requests, three assertions. */
  private static String report(int failedAssertions, boolean withFailure) {
    String secondAssertion =
        withFailure
            ? "{\"assertion\":\"body ok\",\"error\":{\"name\":\"AssertionError\",\"test\":\"body ok\",\"message\":\"expected X to equal Y\"}}"
            : "{\"assertion\":\"body ok\",\"skipped\":false}";
    return """
        {
          "collection": { "item": [
            { "name": "Folder A", "item": [
              { "id": "req1", "name": "create" },
              { "id": "req2", "name": "fetch" }
            ]}
          ]},
          "run": {
            "stats": {
              "iterations": {"total":1,"failed":0},
              "requests": {"total":2,"failed":0},
              "assertions": {"total":3,"failed":%d},
              "testScripts": {"total":2,"failed":0}
            },
            "timings": {"started":1000,"completed":3500},
            "executions": [
              { "item": {"id":"req1","name":"create"},
                "request": {"method":"POST","url":{"protocol":"http","port":"8080","host":["localhost"],"path":["x","create"]}},
                "response": {"code":201,"status":"Created","responseTime":12,"responseSize":100},
                "assertions": [ {"assertion":"201 Created","skipped":false} ] },
              { "item": {"id":"req2","name":"fetch"},
                "request": {"method":"GET","url":{"protocol":"http","port":"8080","host":["localhost"],"path":["x","fetch"]}},
                "response": {"code":200,"status":"OK","responseTime":5,"responseSize":80},
                "assertions": [ {"assertion":"status 200","skipped":false}, %s ] }
            ],
            "failures": []
          }
        }
        """
        .formatted(failedAssertions, secondAssertion);
  }

  private PostmanTools tool(Path fixture, int exit, Optional<String> reportPath) {
    PostmanTools t = new PostmanTools();
    t.json = mapper;
    t.ollama = ollama;
    t.defaultCollection = collection.toString();
    t.newmanCommand = "bash " + stub + " " + fixture + " " + exit;
    t.requestTimeoutMs = 120000;
    t.runTimeoutSeconds = 60;
    t.reportPath = reportPath;
    return t;
  }

  private JsonNode run(PostmanTools t, Boolean analyze, String model) throws Exception {
    return mapper.readTree(t.run_postman_collection(null, analyze, model));
  }

  // --- happy path: pass --------------------------------------------------------------------------

  @Test
  void passingRunParsesIntoRunnerStyleBreakdown() throws Exception {
    JsonNode o = run(tool(passReport, 0, Optional.empty()), false, null);

    assertThat(o.path("passed").asBoolean()).isTrue();
    assertThat(o.path("exitCode").asInt()).isZero();
    assertThat(o.path("durationMs").asLong()).isEqualTo(2500);
    assertThat(o.path("stats").path("assertions").path("total").asInt()).isEqualTo(3);
    assertThat(o.path("stats").path("assertions").path("failed").asInt()).isZero();

    JsonNode requests = o.path("requests");
    assertThat(requests).hasSize(2);
    JsonNode first = requests.get(0);
    assertThat(first.path("folder").asText()).isEqualTo("Folder A");
    assertThat(first.path("method").asText()).isEqualTo("POST");
    assertThat(first.path("url").asText()).isEqualTo("http://localhost:8080/x/create");
    assertThat(first.path("code").asInt()).isEqualTo(201);
    assertThat(first.path("assertions").get(0).path("result").asText()).isEqualTo("PASS");
    assertThat(o.has("analysis")).isFalse();
  }

  // --- failing run -------------------------------------------------------------------------------

  @Test
  void failingRunSurfacesExitCodeAndFailedAssertion() throws Exception {
    JsonNode o = run(tool(failReport, 1, Optional.empty()), false, null);

    assertThat(o.path("passed").asBoolean()).isFalse();
    assertThat(o.path("exitCode").asInt()).isEqualTo(1);
    JsonNode fetchAsserts = o.path("requests").get(1).path("assertions");
    assertThat(fetchAsserts.get(1).path("result").asText()).isEqualTo("FAIL");
    assertThat(fetchAsserts.get(1).path("error").asText()).contains("expected X to equal Y");
  }

  // --- analyze=true ------------------------------------------------------------------------------

  @Test
  void analyzeAddsModelSummaryAndStripsThinkTags() throws Exception {
    when(ollama.generate(any(), eq("qwen3:8b")))
        .thenReturn("All good.<think>secret reasoning</think> Ship it.");

    JsonNode o = run(tool(passReport, 0, Optional.empty()), true, "qwen3:8b");

    assertThat(o.path("analysis").asText()).isEqualTo("All good. Ship it.");
    assertThat(o.has("analysisError")).isFalse();
  }

  @Test
  void analyzeRecordsAnalysisErrorWhenModelCallFails() throws Exception {
    when(ollama.generate(any(), any()))
        .thenReturn("{\"error\":\"can't reach Ollama at http://x\"}");

    JsonNode o = run(tool(passReport, 0, Optional.empty()), true, null);

    assertThat(o.has("analysis")).isFalse();
    assertThat(o.path("analysisError").asText()).contains("can't reach Ollama");
  }

  // --- report retention --------------------------------------------------------------------------

  @Test
  void keepsReportAtConfiguredPath() throws Exception {
    Path kept = tmp.resolve("kept-report.json");
    JsonNode o = run(tool(passReport, 0, Optional.of(kept.toString())), false, null);

    assertThat(o.path("reportPath").asText()).isEqualTo(kept.toString());
    assertThat(Files.exists(kept)).isTrue();
  }

  // --- validation + failure-to-launch branches ---------------------------------------------------

  @Test
  void missingCollectionReturnsReadableError() throws Exception {
    PostmanTools t = tool(passReport, 0, Optional.empty());
    JsonNode o = mapper.readTree(t.run_postman_collection("/no/such/file.json", false, null));
    assertThat(o.path("error").asText()).isEqualTo("collection not found");
  }

  @Test
  void nonJsonCollectionReturnsReadableError() throws Exception {
    Path txt = tmp.resolve("notacollection.txt");
    Files.writeString(txt, "x");
    PostmanTools t = tool(passReport, 0, Optional.empty());
    JsonNode o = mapper.readTree(t.run_postman_collection(txt.toString(), false, null));
    assertThat(o.path("error").asText()).isEqualTo("not a .json collection");
  }

  @Test
  void newmanNotOnPathReturnsReadableError() throws Exception {
    PostmanTools t = tool(passReport, 0, Optional.empty());
    t.newmanCommand = "this-binary-does-not-exist-zzz";
    JsonNode o = mapper.readTree(t.run_postman_collection(null, false, null));
    assertThat(o.path("error").asText()).contains("could not run newman");
  }
}
