package nz.co.ksktech.ollamamcp.logging;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Establishes a per-request correlation id at the HTTP entry point so the MCP message log, the
 * {@code @Logged} tool interceptor and the outbound Ollama client log all share one id.
 *
 * <p>The id is stashed on the {@code RoutingContext}; the tool interceptor recovers it off the
 * worker thread via the CDI request scope ({@code CurrentVertxRequest}), which Quarkus propagates
 * across the event-loop → worker hop. (A Vert.x duplicated context does <em>not</em> carry it — the
 * worker gets its own empty local map — so the RoutingContext is the propagation channel.)
 *
 * <p>Correlation only — no reflection and no body capture. Request/response bodies are logged
 * natively by {@code quarkus.mcp.server.traffic-logging}. An inbound {@code X-Correlation-ID}
 * header is honoured when it is well-formed (so an upstream caller's id flows through) and echoed
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
              String cid = sanitize(rc.request().getHeader(LogSupport.CORRELATION_HEADER));

              // MDC here covers event-loop log lines (e.g. MCP "message received"). The interceptor
              // sets it again on the worker thread for the tool/Ollama/response lines.
              MDC.put(InvocationLoggingInterceptor.CORRELATION_ID, cid);
              // The interceptor reads this back off the worker thread via CurrentVertxRequest.
              rc.put(InvocationLoggingInterceptor.CORRELATION_ID, cid);

              rc.response()
                  .headersEndHandler(v -> rc.response().putHeader(LogSupport.CORRELATION_HEADER, cid));
              // Clears the event-loop thread's MDC at request end. The worker thread is not cleared
              // here (this runs on the event-loop), but the interceptor reads the request scope
              // first, so a stale id left on a pooled worker can never win for the next request.
              rc.addEndHandler(ar -> MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID));
              rc.next();
            });
  }

  /**
   * Returns a well-formed inbound id (UUID-ish: short, alphanumeric/dash) or a fresh UUID. Rejecting
   * arbitrary header values keeps untrusted input out of the logs (no flooding / layout injection).
   */
  private static String sanitize(String headerValue) {
    if (headerValue != null
        && !headerValue.isBlank()
        && headerValue.length() <= 64
        && headerValue.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-')) {
      return headerValue;
    }
    return UUID.randomUUID().toString();
  }
}
