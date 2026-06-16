# Quarkus MCP client

Connect to this server from another Quarkus app using the Quarkiverse MCP **client** extension.

## 1. Add the client extension

```xml
<dependency>
  <groupId>io.quarkiverse.mcp</groupId>
  <artifactId>quarkus-mcp-client</artifactId>
  <version>1.12.1</version>
</dependency>
```

## 2. Point it at this server

In the consuming app's `application.properties` (start this server with `./mvnw quarkus:dev`
first):

```properties
# Streamable HTTP transport
quarkus.mcp.client.ollama.transport-type=streamable-http
quarkus.mcp.client.ollama.url=http://localhost:8085/mcp

# --- or the legacy HTTP/SSE transport ---
# quarkus.mcp.client.ollama.transport-type=http-sse
# quarkus.mcp.client.ollama.url=http://localhost:8085/mcp/sse
```

## 3. Use the tools

Inject the generated client (named `ollama` above) and call the tools, e.g. `ollama_list` to
confirm the remote host is reachable, then `ollama_generate`. The exact injection API follows the
`quarkus-mcp-client` version you pin — see its reference guide:
<https://docs.quarkiverse.io/quarkus-mcp-server/dev/>.
