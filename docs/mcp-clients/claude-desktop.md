# Claude Desktop

Claude Desktop reads MCP servers from its `claude_desktop_config.json`:

- **macOS:** `~/Library/Application Support/Claude/claude_desktop_config.json`
- **Windows:** `%APPDATA%\Claude\claude_desktop_config.json`

Claude Desktop launches stdio servers itself, but this server is a **remote HTTP** server, so it
connects via a URL. Make sure `./mvnw quarkus:dev` is running first, then add:

```json
{
  "mcpServers": {
    "ollama-mcp-server": {
      "type": "streamable-http",
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

Prefer the legacy SSE transport instead? Use:

```json
{
  "mcpServers": {
    "ollama-mcp-server": {
      "type": "sse",
      "url": "http://localhost:8085/mcp/sse"
    }
  }
}
```

Restart Claude Desktop. The six `ollama_*` tools become available in chat; ask it to call
`ollama_list` to confirm the remote host is reachable.
