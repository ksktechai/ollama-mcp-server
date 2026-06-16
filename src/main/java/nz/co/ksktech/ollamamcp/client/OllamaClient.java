package nz.co.ksktech.ollamamcp.client;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ChatRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ChatResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.EmbedRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.EmbedResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.GenerateRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.GenerateResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ListModelsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.PsResponse;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ShowRequest;
import nz.co.ksktech.ollamamcp.client.dto.OllamaDtos.ShowResponse;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * Typed REST client for the (remote) Ollama HTTP API.
 *
 * <p>Configured by {@code configKey = "ollama-api"}: the base URL and read timeout live in
 * {@code application.properties} under {@code quarkus.rest-client.ollama-api.*}, defaulting to the
 * remote box {@code http://192.168.1.5:11434}. Nothing here hard-codes a host.
 *
 * <p>Every request maps to the Ollama endpoint of the same name. Requests always carry
 * {@code "stream": false} (see the DTO constructors) so each call returns one JSON object.
 */
@RegisterRestClient(configKey = "ollama-api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public interface OllamaClient {

  /** GET /api/tags — models available locally on the Ollama host. */
  @GET
  @Path("/api/tags")
  ListModelsResponse listModels();

  /** POST /api/show — details (license, parameters, template, info) for one model. */
  @POST
  @Path("/api/show")
  ShowResponse show(ShowRequest request);

  /** GET /api/ps — models currently loaded into memory. */
  @GET
  @Path("/api/ps")
  PsResponse ps();

  /** POST /api/generate — single-prompt completion. */
  @POST
  @Path("/api/generate")
  GenerateResponse generate(GenerateRequest request);

  /** POST /api/chat — multi-message chat completion. */
  @POST
  @Path("/api/chat")
  ChatResponse chat(ChatRequest request);

  /** POST /api/embed — embeddings for one or more input strings. */
  @POST
  @Path("/api/embed")
  EmbedResponse embed(EmbedRequest request);
}
