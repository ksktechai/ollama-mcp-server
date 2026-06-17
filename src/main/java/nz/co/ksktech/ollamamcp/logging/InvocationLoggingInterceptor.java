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

  @AroundInvoke
  Object log(InvocationContext ctx) throws Exception {
    String existingCorrelationId = MDC.get(CORRELATION_ID);
    boolean generated = false;
    if (existingCorrelationId == null || existingCorrelationId.isBlank()) {
      MDC.put(CORRELATION_ID, UUID.randomUUID().toString());
      generated = true;
    }
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
    } finally {
      if (generated) {
        MDC.remove(CORRELATION_ID);
      }
    }
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
