package nz.co.ksktech.ollamamcp.logging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LogSupport} — URL secret-masking and the bounded (cap-aware) body previews
 * used by the request/response logging filters.
 */
class LogSupportTest {

  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void maskUrlLeavesNullAndQuerylessUrlsUntouched() {
    assertThat(LogSupport.maskUrl(null)).isNull();
    assertThat(LogSupport.maskUrl("http://host/api/tags")).isEqualTo("http://host/api/tags");
  }

  @Test
  void maskUrlMasksOnlySecretQueryParams() {
    String out = LogSupport.maskUrl("http://h/x?token=abc&model=qwen3&apikey=zzz&flag&=novalue");
    assertThat(out)
        .contains("token=***") // secret masked
        .contains("apikey=***") // secret masked (case-insensitive key match)
        .contains("model=qwen3") // non-secret preserved
        .contains("flag") // pair without '=' preserved
        .contains("=novalue") // pair with empty key (eq<=0) preserved
        .doesNotContain("abc")
        .doesNotContain("zzz");
  }

  @Test
  void previewReturnsEmptyForNullEntity() {
    assertThat(LogSupport.preview(mapper, null)).isEmpty();
  }

  @Test
  void previewSerialisesSmallEntity() {
    assertThat(LogSupport.preview(mapper, Map.of("a", 1)))
        .contains("\"a\":1")
        .doesNotContain("truncated");
  }

  @Test
  void previewTruncatesLargeEntityViaCappedWriter() {
    Map<String, Object> big = new HashMap<>();
    big.put("blob", "x".repeat(5000));
    String out = LogSupport.preview(mapper, big);
    assertThat(out).endsWith("…(truncated)");
    assertThat(out.length()).isLessThan(5000); // never built the full payload
  }

  @Test
  void previewReportsUnserialisableEntity() {
    // A getter that throws makes Jackson fail fast (before the cap), exercising the catch path.
    assertThat(LogSupport.preview(mapper, new Bomb())).startsWith("<unserializable");
  }

  /** Serialisation always fails on this — its only property getter throws. */
  public static class Bomb {
    public String getValue() {
      throw new IllegalStateException("boom");
    }
  }
}
