# Postman — MCP Request

Postman talks to this server through its **MCP Request** feature (a config-based connection
to the MCP endpoint), **not** a saved HTTP collection. There is no plain-HTTP REST surface to
import — this server speaks only the MCP protocol.

## Steps

1. Start the server: `./mvnw quarkus:dev` (listens on `http://localhost:8085`).
2. In Postman: **New → MCP Request**.
3. Choose a transport + URL:
   - **HTTP (Streamable HTTP):** `http://localhost:8085/mcp`
   - **SSE:** `http://localhost:8085/mcp/sse`

   Use whichever your Postman version's MCP client expects; both are served by this one process.
4. Click **Connect**. The six `ollama_*` tools appear.
5. Invoke **`ollama_list`** first — it confirms the remote Ollama host is reachable and lists the
   installed models. Then try **`ollama_generate`** with a `prompt`.

## Troubleshooting

- **Tools list is empty?** Confirm the server is running (`./mvnw quarkus:dev`) and that
  `curl http://192.168.1.5:11434/api/tags` succeeds **from this machine**.
- **`ollama_list` returns `"can't reach Ollama at http://192.168.1.5:11434"`?** The remote box
  isn't reachable. Ensure Ollama there is bound to all interfaces
  (`OLLAMA_HOST=0.0.0.0:11434`) and not just loopback. See the README "Remote Ollama" section.

<!-- Screenshot placeholder: Postman MCP Request connected, six ollama_* tools listed.
     docs/mcp-clients/postman-connected.png -->
