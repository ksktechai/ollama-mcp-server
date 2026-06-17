package nz.co.ksktech.ollamamcp.logging;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Establishes a per-request correlation id at the HTTP entry point and propagates it across the
 * event-loop → worker thread boundary via the Vert.x <em>duplicated context</em>, so the MCP
 * message log (event-loop), the {@code @Logged} tool interceptor (worker) and the outbound Ollama
 * client log (worker) all share one id.
 *
 * <p>Correlation only — no reflection and no body capture. Request/response bodies are logged
 * natively by {@code quarkus.mcp.server.traffic-logging}. The id is read from an inbound
 * {@code X-Correlation-ID} header when present (so an upstream caller's id is honoured) and echoed
 * back on the response.
 */
@ApplicationScoped
public class CorrelationFilter {

  void init(@Observes Router router) {
    router
        .route("/mcp*")
        .order(-100)
        .handler(
            rc -> {
              String correlationId = rc.request().getHeader(LogSupport.CORRELATION_HEADER);
              if (correlationId == null || correlationId.isBlank()) {
                correlationId = UUID.randomUUID().toString();
              }
              final String cid = correlationId;

              MDC.put(InvocationLoggingInterceptor.CORRELATION_ID, cid);
              // Stash on both the RoutingContext (read back via CurrentVertxRequest within the CDI
              // request scope, which Quarkus propagates to the worker thread) and the Vert.x
              // duplicated context, so the tool interceptor can recover the same id off-thread.
              rc.put(InvocationLoggingInterceptor.CORRELATION_ID, cid);
              Context ctx = Vertx.currentContext();
              if (ctx != null) {
                ctx.putLocal(InvocationLoggingInterceptor.CORRELATION_ID, cid);
              }

              rc.response()
                  .headersEndHandler(v -> rc.response().putHeader(LogSupport.CORRELATION_HEADER, cid));
              rc.addEndHandler(ar -> MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID));
              rc.next();
            });
  }
}
