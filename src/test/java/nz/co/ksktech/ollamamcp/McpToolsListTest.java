package nz.co.ksktech.ollamamcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpSseTestClient;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Connects an in-process MCP client over the SSE transport and asserts the advertised tool surface:
 * the six {@code ollama_*} tools plus the {@code run_postman_collection} runner are present with the
 * expected names, and the key tools expose the expected argument names in their input schema. This
 * is what a Postman / Claude Desktop user sees when they connect and the tool list populates.
 */
@QuarkusTest
class McpToolsListTest {

  private static final List<String> EXPECTED_TOOLS =
      List.of(
          "ollama_list",
          "ollama_show",
          "ollama_ps",
          "ollama_generate",
          "ollama_chat",
          "ollama_embed",
          "run_postman_collection");

  @Test
  void listsAllSixToolsWithExpectedArgs() {
    McpSseTestClient client = McpAssured.newConnectedSseClient();

    client
        .when()
        .toolsList(
            page -> {
              List<String> names = page.tools().stream().map(t -> t.name()).toList();
              assertThat(names).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);

              // The input schemas carry the @ToolArg names that clients render as form fields.
              assertThat(schemaOf(page, "ollama_generate")).contains("prompt").contains("model");
              assertThat(schemaOf(page, "ollama_chat")).contains("messagesJson").contains("model");
              assertThat(schemaOf(page, "ollama_embed")).contains("input").contains("model");
            })
        .thenAssertResults();

    client.disconnect();
  }

  /** The named tool's JSON input schema, as text (defensive against the concrete schema type). */
  private static String schemaOf(McpAssured.ToolsPage page, String tool) {
    return page.findByName(tool).inputSchema().encode();
  }
}
