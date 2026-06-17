package nz.co.ksktech.ollamamcp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Unit tests for the {@code >>> / <<<} tool-invocation logger. We drive it with a hand-rolled
 * {@link InvocationContext} so the success/error paths, argument formatting (null / empty / values)
 * and body truncation all run without any CDI container.
 */
class InvocationLoggingInterceptorTest {

  private InvocationLoggingInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor = new InvocationLoggingInterceptor();
    interceptor.maxBody = 5; // tiny, so long arg strings exercise the truncation branch
  }

  @Test
  void logsAndReturnsResultOnSuccess() throws Exception {
    Object result = interceptor.log(ctx(new Object[] {"short"}, () -> "result-value"));
    assertThat(result).isEqualTo("result-value");
  }

  @Test
  void logsLongTruncatedArgsAndNullValues() throws Exception {
    // Two params incl. a null and a string longer than maxBody -> truncation branch.
    Object result =
        interceptor.log(ctx(new Object[] {"a-very-long-argument-value", null}, () -> "ok"));
    assertThat(result).isEqualTo("ok");
  }

  @Test
  void handlesNullAndEmptyParameterArrays() throws Exception {
    assertThat(interceptor.log(ctx(null, () -> "n"))).isEqualTo("n");
    assertThat(interceptor.log(ctx(new Object[] {}, () -> "e"))).isEqualTo("e");
  }

  @Test
  void logsAndRethrowsOnFailure() {
    RuntimeException boom = new RuntimeException("kaboom");
    assertThatThrownBy(() -> interceptor.log(ctx(new Object[] {"x"}, () -> {
              throw boom;
            })))
        .isSameAs(boom);
  }

  @Test
  void reusesExistingCorrelationId() throws Exception {
    MDC.put(InvocationLoggingInterceptor.CORRELATION_ID, "pre-existing-id");
    try {
      Object result = interceptor.log(ctx(new Object[] {"short"}, () -> {
        assertThat(MDC.get(InvocationLoggingInterceptor.CORRELATION_ID)).isEqualTo("pre-existing-id");
        return "val";
      }));
      assertThat(result).isEqualTo("val");
      assertThat(MDC.get(InvocationLoggingInterceptor.CORRELATION_ID)).isEqualTo("pre-existing-id");
    } finally {
      MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID);
    }
  }

  /** Minimal {@link InvocationContext} exposing only what the interceptor reads. */
  private static InvocationContext ctx(Object[] params, Callable<Object> body) throws Exception {
    Method method = String.class.getMethod("length");
    return new InvocationContext() {
      @Override
      public Object getTarget() {
        return null;
      }

      @Override
      public Object getTimer() {
        return null;
      }

      @Override
      public Method getMethod() {
        return method;
      }

      @Override
      public Constructor<?> getConstructor() {
        return null;
      }

      @Override
      public Object[] getParameters() {
        return params;
      }

      @Override
      public void setParameters(Object[] p) {}

      @Override
      public Map<String, Object> getContextData() {
        return Map.of();
      }

      @Override
      public Object proceed() throws Exception {
        return body.call();
      }
    };
  }
}
