package nz.co.ksktech.ollamamcp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RequestBody;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ApiAccessLoggingFilterTest {

  private ApiAccessLoggingFilter filter;

  @BeforeEach
  void setUp() {
    filter = new ApiAccessLoggingFilter();
    MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID);
  }

  @Test
  void testInitRegistersHandlers() {
    Router router = mock(Router.class);
    Route route = mock(Route.class);
    when(router.route(anyString())).thenReturn(route);
    when(route.order(anyInt())).thenReturn(route);

    filter.init(router);

    verify(route).order(-200);
    verify(route).order(-100);
    verify(route, times(2)).handler(any());
  }

  @SuppressWarnings("unchecked")
  @Test
  void testRequestFilterHandler() {
    Router router = mock(Router.class);
    Route route = mock(Route.class);
    when(router.route(anyString())).thenReturn(route);
    when(route.order(anyInt())).thenReturn(route);

    ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
    when(route.handler(handlerCaptor.capture())).thenReturn(route);

    filter.init(router);

    // Grab the logging handler (order -100) which is the second registered handler
    Handler<RoutingContext> loggingHandler = handlerCaptor.getAllValues().get(1);

    RoutingContext rc = mock(RoutingContext.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    HttpServerResponse response = mock(HttpServerResponse.class);
    RequestBody requestBody = mock(RequestBody.class);
    when(rc.request()).thenReturn(request);
    when(rc.response()).thenReturn(response);
    when(rc.body()).thenReturn(requestBody);
    when(request.path()).thenReturn("/mcp");
    when(request.getHeader(LogSupport.CORRELATION_HEADER)).thenReturn("custom-corr-id");
    when(requestBody.asString()).thenReturn("{\"jsonrpc\":\"2.0\"}");

    Map<String, Object> contextMap = new HashMap<>();
    doAnswer(invocation -> {
      contextMap.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(rc).put(anyString(), any());
    doAnswer(invocation -> contextMap.get(invocation.getArgument(0))).when(rc).get(anyString());

    loggingHandler.handle(rc);

    assertThat(MDC.get(InvocationLoggingInterceptor.CORRELATION_ID)).isEqualTo("custom-corr-id");
    verify(rc).next();

    // Capture and invoke headersEndHandler
    ArgumentCaptor<Handler<Void>> headersEndHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
    verify(response).headersEndHandler(headersEndHandlerCaptor.capture());
    headersEndHandlerCaptor.getValue().handle(null);
    verify(response).putHeader(LogSupport.CORRELATION_HEADER, "custom-corr-id");

    // Capture and invoke endHandler
    ArgumentCaptor<Handler<Void>> endHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
    verify(response).endHandler(endHandlerCaptor.capture());
    endHandlerCaptor.getValue().handle(null);
  }

  @Test
  void testProxyInterception() throws Exception {
    io.vertx.ext.web.impl.RoutingContextImpl rc = mock(io.vertx.ext.web.impl.RoutingContextImpl.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    HttpServerResponse response = mock(HttpServerResponse.class);

    when(rc.request()).thenReturn(request);
    when(request.response()).thenReturn(response);

    Map<String, Object> contextMap = new HashMap<>();
    doAnswer(invocation -> {
      contextMap.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(rc).put(anyString(), any());
    doAnswer(invocation -> contextMap.get(invocation.getArgument(0))).when(rc).get(anyString());

    // Inject proxy request
    java.lang.reflect.Method createReqMethod = ApiAccessLoggingFilter.class.getDeclaredMethod("createProxyRequest", HttpServerRequest.class, RoutingContext.class);
    createReqMethod.setAccessible(true);
    HttpServerRequest proxyReq = (HttpServerRequest) createReqMethod.invoke(filter, request, rc);

    java.lang.reflect.Method injectReqMethod = ApiAccessLoggingFilter.class.getDeclaredMethod("injectProxyRequest", RoutingContext.class, HttpServerRequest.class);
    injectReqMethod.setAccessible(true);
    injectReqMethod.invoke(filter, rc, proxyReq);

    // Retrieve field to verify injection
    java.lang.reflect.Field requestField = io.vertx.ext.web.impl.RoutingContextImpl.class.getDeclaredField("request");
    requestField.setAccessible(true);
    assertThat((HttpServerRequest) requestField.get(rc)).isSameAs(proxyReq);

    // Call response() on the proxy request and check that it returns the proxy response
    HttpServerResponse proxyResp = proxyReq.response();
    assertThat(proxyResp).isNotSameAs(response);

    // Test fluent return value mapping on request proxy
    when(request.resume()).thenReturn(request);
    assertThat(proxyReq.resume()).isSameAs(proxyReq);

    // Test fluent return value mapping on response proxy
    when(response.putHeader(anyString(), anyString())).thenReturn(response);
    assertThat(proxyResp.putHeader("X-Test", "value")).isSameAs(proxyResp);

    // Write some body to proxy response
    proxyResp.write(io.vertx.core.buffer.Buffer.buffer("hello "));
    proxyResp.end("world");

    // Verify response body captured in routing context
    String bodyResult = rc.get("apiLog.responseBody");
    assertThat(bodyResult).isEqualTo("hello world");
  }

  @Test
  void testProxyInterceptionExceptionsAndReflections() throws Exception {
    io.vertx.ext.web.impl.RoutingContextImpl rc = mock(io.vertx.ext.web.impl.RoutingContextImpl.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    HttpServerResponse response = mock(HttpServerResponse.class);

    when(rc.request()).thenReturn(request);
    when(request.response()).thenReturn(response);

    // Reflectively get proxy creation methods
    java.lang.reflect.Method createReqMethod = ApiAccessLoggingFilter.class.getDeclaredMethod("createProxyRequest", HttpServerRequest.class, RoutingContext.class);
    createReqMethod.setAccessible(true);
    HttpServerRequest proxyReq = (HttpServerRequest) createReqMethod.invoke(filter, request, rc);

    HttpServerResponse proxyResp = proxyReq.response();

    // 1. Response proxy: mock setStatusCode to throw RuntimeException
    when(response.setStatusCode(anyInt())).thenThrow(new RuntimeException("resp error"));
    try {
      proxyResp.setStatusCode(500);
    } catch (RuntimeException e) {
      assertThat(e.getMessage()).isEqualTo("resp error");
    }

    // 2. Request proxy: mock method to throw RuntimeException
    when(request.method()).thenThrow(new RuntimeException("req error"));
    try {
      proxyReq.method();
    } catch (RuntimeException e) {
      assertThat(e.getMessage()).isEqualTo("req error");
    }

    java.lang.reflect.Method injectReqMethod = ApiAccessLoggingFilter.class.getDeclaredMethod("injectProxyRequest", RoutingContext.class, HttpServerRequest.class);
    injectReqMethod.setAccessible(true);

    // 3. injectProxyRequest called with mock RoutingContext (trigger NoSuchFieldException and class traversal)
    injectReqMethod.invoke(filter, mock(RoutingContext.class), proxyReq);

    // 4. injectProxyRequest called with null (trigger general Exception catch)
    injectReqMethod.invoke(filter, null, proxyReq);
  }

  @Test
  void testRequestHandlerWithBodyException() {
    Router router = mock(Router.class);
    Route route = mock(Route.class);
    when(router.route(anyString())).thenReturn(route);
    when(route.order(anyInt())).thenReturn(route);

    ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
    when(route.handler(handlerCaptor.capture())).thenReturn(route);

    filter.init(router);
    Handler<RoutingContext> loggingHandler = handlerCaptor.getAllValues().get(1);

    RoutingContext rc = mock(RoutingContext.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    HttpServerResponse response = mock(HttpServerResponse.class);
    RequestBody requestBody = mock(RequestBody.class);
    when(rc.request()).thenReturn(request);
    when(rc.response()).thenReturn(response);
    when(rc.body()).thenReturn(requestBody);
    when(request.path()).thenReturn("/mcp");
    when(requestBody.asString()).thenThrow(new RuntimeException("body error"));

    Map<String, Object> contextMap = new HashMap<>();
    doAnswer(invocation -> {
      contextMap.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(rc).put(anyString(), any());
    doAnswer(invocation -> contextMap.get(invocation.getArgument(0))).when(rc).get(anyString());

    loggingHandler.handle(rc);
    verify(rc).next();
  }

  @SuppressWarnings("unchecked")
  @Test
  void testRequestHandlerWithQueryAndSkip() {
    Router router = mock(Router.class);
    Route route = mock(Route.class);
    when(router.route(anyString())).thenReturn(route);
    when(route.order(anyInt())).thenReturn(route);

    ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = ArgumentCaptor.forClass(Handler.class);
    when(route.handler(handlerCaptor.capture())).thenReturn(route);

    filter.init(router);
    Handler<RoutingContext> loggingHandler = handlerCaptor.getAllValues().get(1);

    // Test case 1: Path with query to cover query != null path
    RoutingContext rc1 = mock(RoutingContext.class);
    HttpServerRequest request1 = mock(HttpServerRequest.class);
    HttpServerResponse response1 = mock(HttpServerResponse.class);
    when(rc1.request()).thenReturn(request1);
    when(rc1.response()).thenReturn(response1);
    when(request1.path()).thenReturn("/mcp");
    when(request1.query()).thenReturn("token=mysecret");
    when(request1.method()).thenReturn(io.vertx.core.http.HttpMethod.POST);

    Map<String, Object> contextMap1 = new HashMap<>();
    doAnswer(invocation -> {
      contextMap1.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(rc1).put(anyString(), any());
    doAnswer(invocation -> contextMap1.get(invocation.getArgument(0))).when(rc1).get(anyString());

    loggingHandler.handle(rc1);
    verify(rc1).next();

    // Trigger endHandler with query to cover end response path with query
    ArgumentCaptor<Handler<Void>> endHandlerCaptor = ArgumentCaptor.forClass(Handler.class);
    verify(response1).endHandler(endHandlerCaptor.capture());
    endHandlerCaptor.getValue().handle(null);


    // Test case 2: Path starts with /q/ to cover skip(rc) == true path
    RoutingContext rc2 = mock(RoutingContext.class);
    HttpServerRequest request2 = mock(HttpServerRequest.class);
    HttpServerResponse response2 = mock(HttpServerResponse.class);
    when(rc2.request()).thenReturn(request2);
    when(rc2.response()).thenReturn(response2);
    when(request2.path()).thenReturn("/q/health");
    when(request2.method()).thenReturn(io.vertx.core.http.HttpMethod.GET);

    Map<String, Object> contextMap2 = new HashMap<>();
    doAnswer(invocation -> {
      contextMap2.put(invocation.getArgument(0), invocation.getArgument(1));
      return null;
    }).when(rc2).put(anyString(), any());
    doAnswer(invocation -> contextMap2.get(invocation.getArgument(0))).when(rc2).get(anyString());

    loggingHandler.handle(rc2);
    verify(rc2).next();

    // Trigger endHandler for skipped path
    ArgumentCaptor<Handler<Void>> endHandlerCaptor2 = ArgumentCaptor.forClass(Handler.class);
    verify(response2).endHandler(endHandlerCaptor2.capture());
    endHandlerCaptor2.getValue().handle(null);
  }

  @Test
  void testResponseBodyCappedAtMaxBody() throws Exception {
    io.vertx.ext.web.impl.RoutingContextImpl rc = mock(io.vertx.ext.web.impl.RoutingContextImpl.class);
    HttpServerRequest request = mock(HttpServerRequest.class);
    HttpServerResponse response = mock(HttpServerResponse.class);
    when(rc.request()).thenReturn(request);
    when(request.response()).thenReturn(response);

    Map<String, Object> contextMap = new HashMap<>();
    doAnswer(i -> {
      contextMap.put(i.getArgument(0), i.getArgument(1));
      return null;
    }).when(rc).put(anyString(), any());
    doAnswer(i -> contextMap.get(i.getArgument(0))).when(rc).get(anyString());

    java.lang.reflect.Method createReqMethod = ApiAccessLoggingFilter.class.getDeclaredMethod(
        "createProxyRequest", HttpServerRequest.class, RoutingContext.class);
    createReqMethod.setAccessible(true);
    HttpServerRequest proxyReq = (HttpServerRequest) createReqMethod.invoke(filter, request, rc);
    HttpServerResponse proxyResp = proxyReq.response();

    // Write far more than max-body across multiple chunks; the accumulator must stop at the cap.
    int cap = LogSupport.maxBody();
    proxyResp.write(io.vertx.core.buffer.Buffer.buffer("x".repeat(cap + 500)));
    proxyResp.end("trailing-bytes-past-the-cap");

    String captured = rc.get("apiLog.responseBody");
    assertThat(captured.length()).isEqualTo(cap);
  }
}

