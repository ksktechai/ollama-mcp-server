package nz.co.ksktech.ollamamcp.logging;

import io.quarkus.logging.Log;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.MDC;

/**
 * Logs every {@link Logged} method invocation — used on the MCP {@code @Tool} methods — using the
 * same {@code >>>} (inbound) / {@code <<<} (outbound) convention and per-call {@code correlationId}
 * (a UUID stored in the SLF4J MDC) as the sibling fundlens / bian-fraud-detection services.
 *
 * <p>One log line in, one out: tool name, arguments (truncated to {@code app.logging.max-body}),
 * duration in ms, and the outcome (OK / ERROR). This is the single, clearly-named place tool
 * traffic is traced — no per-method logging clutters the tool bean.
 *
 * <p>Bodies are NOT redacted today (synthetic/dev traffic); {@link #redact(String)} is the
 * clearly-marked hook to add masking before pointing this at sensitive prompts.
 */
@Logged
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class InvocationLoggingInterceptor {

  /** MDC key carrying the per-call correlation id (mirrors fundlens / bian). */
  public static final String CORRELATION_ID = "correlationId";

  @ConfigProperty(name = "app.logging.max-body", defaultValue = "2000")
  int maxBody;

  @jakarta.inject.Inject io.quarkus.vertx.http.runtime.CurrentVertxRequest currentVertxRequest;

  @AroundInvoke
  Object log(InvocationContext ctx) throws Exception {
    // Prefer the id established at the HTTP entry (CorrelationFilter), recovered off the worker
    // thread via the CDI request scope; else this thread's MDC; else generate. Reading the request
    // scope FIRST means a stale id left on a pooled worker thread can never win.
    String correlationId = correlationIdFromContext();
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = MDC.get(CORRELATION_ID);
    }
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    // Left on the MDC on purpose so the framework's response lines (MCP traffic "sent", access log)
    // emitted on this same worker thread after the tool returns also carry the id. The worker's MDC
    // is not explicitly cleared (CorrelationFilter's end-handler runs on the event-loop), but the
    // request-scope-first read above means a stale id on a pooled worker can never win for the next
    // request. Note: a worker-dispatched bean that is NOT a @Tool would log under the prior id —
    // there are none today; keep it that way or clear MDC here if that changes.
    MDC.put(CORRELATION_ID, correlationId);
    String tool = ctx.getMethod().getName();
    long start = System.nanoTime();
    Log.infof(">>> %s args=%s", tool, truncate(formatArgs(ctx.getParameters())));
    try {
      Object result = ctx.proceed();
      long millis = (System.nanoTime() - start) / 1_000_000;
      Log.infof("<<< %s -> OK (%d ms)", tool, millis);
      if (result != null) {
        Log.infof("<<< tool response: %s", truncate(String.valueOf(result)));
      }
      return result;
    } catch (Exception failure) {
      long millis = (System.nanoTime() - start) / 1_000_000;
      Log.errorf("<<< %s -> ERROR (%d ms): %s", tool, millis, failure.toString());
      throw failure;
    }
  }

  /**
   * The correlation id established at the HTTP entry (CorrelationFilter), recovered off the worker
   * thread via the CDI request scope ({@code CurrentVertxRequest} → RoutingContext), which Quarkus
   * propagates across the event-loop → worker hop. Null when there is no active HTTP request (e.g.
   * unit tests) so the caller generates one.
   */
  private String correlationIdFromContext() {
    try {
      if (currentVertxRequest != null && currentVertxRequest.getCurrent() != null) {
        Object fromRequest = currentVertxRequest.getCurrent().get(CORRELATION_ID);
        if (fromRequest != null) {
          return fromRequest.toString();
        }
      }
    } catch (RuntimeException ignored) {
      // no active request context on this thread — fall through to generate
    }
    return null;
  }

  private String formatArgs(Object[] args) {
    if (args == null || args.length == 0) {
      return "()";
    }
    return Arrays.stream(args)
        .map(a -> a == null ? "null" : String.valueOf(a))
        .collect(Collectors.joining(", ", "(", ")"));
  }

  /**
   * Redaction hook. No-op today (synthetic/dev traffic); mask secrets/PII in tool arguments here
   * before logging real data.
   */
  private static String redact(String body) {
    // TODO(redaction): mask any sensitive prompt/content here before logging real traffic.
    return body;
  }

  private String truncate(String body) {
    String single = redact(body);
    if (single.length() <= maxBody) {
      return single;
    }
    return single.substring(0, maxBody) + "…(+" + (single.length() - maxBody) + " chars)";
  }
}
