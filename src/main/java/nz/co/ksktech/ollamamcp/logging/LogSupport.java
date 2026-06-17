package nz.co.ksktech.ollamamcp.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Writer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Shared helpers for request/response logging: secret masking in URLs, the correlation-id MDC key,
 * and bounded (CPU- and memory-capped) body previews.
 */
public final class LogSupport {

  /** Inbound header that, if present, is reused as the correlation id. */
  public static final String CORRELATION_HEADER = "X-Correlation-ID";

  /** Query-param names whose values must never be logged. */
  private static final Set<String> SECRET_PARAMS =
      Set.of("token", "apikey", "api_key", "key", "password", "secret", "access_token");

  private static volatile int maxBody = -1;

  private LogSupport() {}

  /** Max characters of a request/response body to log; 0 disables body logging. */
  public static int maxBody() {
    int cached = maxBody;
    if (cached < 0) {
      cached =
          ConfigProvider.getConfig()
              .getOptionalValue("app.logging.max-body", Integer.class)
              .orElse(2000);
      maxBody = cached;
    }
    return cached;
  }

  /**
   * Masks the values of sensitive query parameters (e.g. {@code token}, {@code apikey}) so
   * credentials never reach the logs.
   */
  public static String maskUrl(String url) {
    if (url == null) {
      return null;
    }
    int q = url.indexOf('?');
    if (q < 0) {
      return url;
    }
    String base = url.substring(0, q);
    String masked =
        Arrays.stream(url.substring(q + 1).split("&"))
            .map(LogSupport::maskParam)
            .collect(Collectors.joining("&"));
    return base + "?" + masked;
  }

  private static String maskParam(String pair) {
    int eq = pair.indexOf('=');
    if (eq <= 0) {
      return pair;
    }
    String key = pair.substring(0, eq);
    return SECRET_PARAMS.contains(key.toLowerCase(Locale.ROOT)) ? key + "=***" : pair;
  }

  /**
   * Serialises {@code entity} to JSON, capping both the work done and the bytes kept at {@link
   * #maxBody()}.
   */
  public static String preview(ObjectMapper mapper, Object entity) {
    int cap = maxBody();
    if (cap <= 0 || entity == null) {
      return "";
    }
    CappedWriter writer = new CappedWriter(cap);
    try {
      mapper.writeValue(writer, entity);
    } catch (Exception e) {
      // Jackson may wrap our Stop signal in a JsonMappingException, so detect
      // the cap via the writer's own flag rather than the exception type.
      if (!writer.truncated()) {
        return "<unserializable: " + e.getMessage() + ">";
      }
    }
    return writer.truncated() ? writer + " …(truncated)" : writer.toString();
  }

  /** Caps a String preview (for already-serialised bodies such as HTTP responses). */
  public static String previewText(String body) {
    int cap = maxBody();
    if (cap <= 0 || body == null || body.isEmpty()) {
      return "";
    }
    return body.length() <= cap ? body : body.substring(0, cap) + " …(truncated)";
  }

  /**
   * A {@link Writer} that keeps at most {@code cap} characters and aborts serialisation (via {@link
   * Stop}) once full, so large payloads are never fully built just to be truncated.
   */
  private static final class CappedWriter extends Writer {
    private final StringBuilder sb = new StringBuilder();
    private final int cap;
    private boolean truncated;

    CappedWriter(int cap) {
      this.cap = cap;
    }

    @Override
    public void write(char[] cbuf, int off, int len) {
      int remaining = cap - sb.length();
      if (remaining <= 0) {
        truncated = true;
        throw STOP;
      }
      if (len <= remaining) {
        sb.append(cbuf, off, len);
      } else {
        sb.append(cbuf, off, remaining);
        truncated = true;
        throw STOP;
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    boolean truncated() {
      return truncated;
    }

    @Override
    public String toString() {
      return sb.toString();
    }

    static final class Stop extends RuntimeException {
      Stop() {
        super(null, null, false, false);
      }
    }

    private static final Stop STOP = new Stop();
  }
}
