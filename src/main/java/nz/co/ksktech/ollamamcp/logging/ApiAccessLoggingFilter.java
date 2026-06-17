package nz.co.ksktech.ollamamcp.logging;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.slf4j.MDC;

/**
 * Inbound HTTP logging filter using Vert.x Router. Intercepts all incoming HTTP calls
 * (including Vert.x Web Routes like /mcp and /mcp/sse) to:
 *
 * <ul>
 *   <li>assign/read a correlation ID and track it in MDC and RoutingContext;
 *   <li>log the request method, path, and request body payload;
 *   <li>log the response status, path, and duration.
 * </ul>
 */
@ApplicationScoped
public class ApiAccessLoggingFilter {

  private static final Logger LOG = Logger.getLogger("nz.co.ksktech.ollamamcp.http");
  private static final String START_NANOS = "apiLog.startNanos";

  public void init(@Observes Router router) {
    // Register Vert.x BodyHandler early for MCP routes to buffer the request body
    router.route("/mcp*").order(-200).handler(BodyHandler.create());

    // Handler at order -100: sets up MDC, logs request line and body, and registers response handlers
    router.route("/mcp*").order(-100).handler(rc -> {
      HttpServerRequest origReq = rc.request();
      if (origReq != null) {
        HttpServerRequest proxyReq = createProxyRequest(origReq, rc);
        injectProxyRequest(rc, proxyReq);
      }

      String correlationId = rc.request().getHeader(LogSupport.CORRELATION_HEADER);
      if (correlationId == null || correlationId.isBlank()) {
        correlationId = UUID.randomUUID().toString();
      }
      MDC.put(InvocationLoggingInterceptor.CORRELATION_ID, correlationId);
      rc.put(InvocationLoggingInterceptor.CORRELATION_ID, correlationId);
      rc.put(START_NANOS, System.nanoTime());

      // Echo correlation ID back in response headers
      rc.response().headersEndHandler(v -> {
        String corrId = rc.get(InvocationLoggingInterceptor.CORRELATION_ID);
        if (corrId != null) {
          rc.response().putHeader(LogSupport.CORRELATION_HEADER, corrId);
        }
      });

      rc.response().endHandler(v -> {
        try {
          if (!skip(rc)) {
            Long start = rc.get(START_NANOS);
            long ms = (start != null) ? (System.nanoTime() - start) / 1_000_000 : -1;
            LOG.infof(
                "<-- %d %s %s (%dms)",
                rc.response().getStatusCode(),
                rc.request().method(),
                pathWithQuery(rc),
                ms);
            String respBody = rc.get("apiLog.responseBody");
            if (respBody != null && !respBody.isEmpty()) {
              LOG.infof("<-- response body: %s", LogSupport.previewText(respBody));
            }
          }
        } finally {
          MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID);
        }
      });

      if (!skip(rc)) {
        LOG.infof("--> %s %s", rc.request().method(), pathWithQuery(rc));
        try {
          String body = rc.body().asString();
          if (body != null && !body.isEmpty()) {
            LOG.infof("--> request body: %s", LogSupport.previewText(body));
          }
        } catch (Exception ignored) {
          // body not read or not available
        }
      }
      rc.next();
    });
  }

  private boolean skip(RoutingContext rc) {
    return rc.request().path().startsWith("/q/");
  }

  private String pathWithQuery(RoutingContext rc) {
    String query = rc.request().query();
    String path = rc.request().path();
    return query == null ? path : LogSupport.maskUrl(path + "?" + query);
  }

  private HttpServerRequest createProxyRequest(HttpServerRequest originalRequest, RoutingContext rc) {
    return (HttpServerRequest) Proxy.newProxyInstance(
        HttpServerRequest.class.getClassLoader(),
        new Class<?>[]{HttpServerRequest.class},
        new InvocationHandler() {
          private HttpServerResponse proxyResponse;

          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("response".equals(method.getName()) && (args == null || args.length == 0)) {
              if (proxyResponse == null) {
                proxyResponse = createProxyResponse(originalRequest.response(), rc);
              }
              return proxyResponse;
            }
            try {
              Object result = method.invoke(originalRequest, args);
              if (result == originalRequest) {
                return proxy;
              }
              return result;
            } catch (InvocationTargetException e) {
              throw e.getCause();
            }
          }
        });
  }

  private HttpServerResponse createProxyResponse(HttpServerResponse originalResponse, RoutingContext rc) {
    StringBuilder bodyAccumulator = new StringBuilder();
    final int cap = LogSupport.maxBody();

    return (HttpServerResponse) Proxy.newProxyInstance(
        HttpServerResponse.class.getClassLoader(),
        new Class<?>[]{HttpServerResponse.class},
        new InvocationHandler() {
          @Override
          public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();

            if (("write".equals(name) || "end".equals(name))
                && cap > 0
                && bodyAccumulator.length() < cap
                && args != null
                && args.length > 0) {
              Object firstArg = args[0];
              String chunk = null;
              if (firstArg instanceof io.vertx.core.buffer.Buffer buf) {
                chunk = buf.toString(java.nio.charset.StandardCharsets.UTF_8);
              } else if (firstArg instanceof String str) {
                chunk = str;
              }
              if (chunk != null) {
                // Cap the buffer: a long-lived SSE stream must not grow it without bound.
                int remaining = cap - bodyAccumulator.length();
                bodyAccumulator.append(chunk, 0, Math.min(chunk.length(), remaining));
              }
            }

            if ("end".equals(name)) {
              rc.put("apiLog.responseBody", bodyAccumulator.toString());
            }

            try {
              Object result = method.invoke(originalResponse, args);
              if (result == originalResponse) {
                return proxy;
              }
              return result;
            } catch (InvocationTargetException e) {
              throw e.getCause();
            }
          }
        });
  }

  private void injectProxyRequest(RoutingContext rc, HttpServerRequest proxyRequest) {
    try {
      Class<?> clazz = rc.getClass();
      while (clazz != null) {
        try {
          java.lang.reflect.Field field = clazz.getDeclaredField("request");
          field.setAccessible(true);
          field.set(rc, proxyRequest);
          return;
        } catch (NoSuchFieldException e) {
          clazz = clazz.getSuperclass();
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to inject logging request proxy into RoutingContext", e);
    }
  }
}
