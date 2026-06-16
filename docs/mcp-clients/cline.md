# Cline (VS Code)

Cline stores MCP servers in `cline_mcp_settings.json` (open the **MCP Servers** panel →
**Configure MCP Servers**). This server is remote HTTP, so connect by URL — start
`./mvnw quarkus:dev` first.

Streamable HTTP (recommended):

```json
{
  "mcpServers": {
    "ollama-mcp-server": {
      "type": "streamableHttp",
      "url": "http://localhost:8085/mcp",
      "disabled": false,
      "autoApprove": ["ollama_list", "ollama_ps", "ollama_show"]
    }
  }
}
```

SSE alternative:

```json
{
  "mcpServers": {
    "ollama-mcp-server": {
      "type": "sse",
      "url": "http://localhost:8085/mcp/sse",
      "disabled": false
    }
  }
}
```

`autoApprove` lists the read-only tools that are safe to run without a prompt; leave
`ollama_generate` / `ollama_chat` / `ollama_embed` off it so Cline asks before running them.
