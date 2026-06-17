package nz.co.ksktech.ollamamcp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

/**
 * Unit tests for {@link CorrelationFilter}: it generates (or honours an inbound) correlation id,
 * stashes it for off-thread recovery, echoes it on the response, and clears MDC at request end —
 * all via mocked Vert.x Router types, no container.
 */
class CorrelationFilterTest {

  private static final String CID = InvocationLoggingInterceptor.CORRELATION_ID;
  private CorrelationFilter filter;

  @BeforeEach
  void setUp() {
    filter = new CorrelationFilter();
    MDC.remove(CID);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(CID);
  }

  @SuppressWarnings("unchecked")
  private Handler<RoutingContext> captureHandler() {
    Router router = mock(Router.class);
    Route route = mock(Route.class);
    when(router.route(anyString())).thenReturn(route);
    when(route.order(anyInt())).thenReturn(route);
    ArgumentCaptor<Handler<RoutingContext>> captor = ArgumentCaptor.forClass(Handler.class);
    when(route.handler(captor.capture())).thenReturn(route);
    filter.init(router);
    verify(route).order(-100);
    return captor.getValue();
  }

  private static RoutingContext mockRc(String inboundHeader, Map<String, Object> store) {
    RoutingContext rc = mock(RoutingContext.class);
    HttpServerRequest req = mock(HttpServerRequest.class);
    HttpServerResponse resp = mock(HttpServerResponse.class);
    when(rc.request()).thenReturn(req);
    when(rc.response()).thenReturn(resp);
    when(req.getHeader(LogSupport.CORRELATION_HEADER)).thenReturn(inboundHeader);
    doAnswer(i -> {
      store.put(i.getArgument(0), i.getArgument(1));
      return null;
    }).when(rc).put(anyString(), any());
    return rc;
  }

  @Test
  @SuppressWarnings("unchecked")
  void generatesIdEchoesHeaderAndClearsMdcAtEnd() {
    Handler<RoutingContext> handler = captureHandler();
    Map<String, Object> store = new HashMap<>();
    RoutingContext rc = mockRc(null, store);

    handler.handle(rc);

    String id = (String) store.get(CID);
    assertThat(id).isNotBlank();
    assertThat(MDC.get(CID)).isEqualTo(id);
    verify(rc).next();

    ArgumentCaptor<Handler<Void>> headersEnd = ArgumentCaptor.forClass(Handler.class);
    verify(rc.response()).headersEndHandler(headersEnd.capture());
    headersEnd.getValue().handle(null);
    verify(rc.response()).putHeader(LogSupport.CORRELATION_HEADER, id);

    ArgumentCaptor<Handler<AsyncResult<Void>>> endHandler = ArgumentCaptor.forClass(Handler.class);
    verify(rc).addEndHandler(endHandler.capture());
    endHandler.getValue().handle(null);
    assertThat(MDC.get(CID)).isNull();
  }

  @Test
  void honoursInboundCorrelationHeader() {
    Handler<RoutingContext> handler = captureHandler();
    Map<String, Object> store = new HashMap<>();
    RoutingContext rc = mockRc("upstream-id", store);

    handler.handle(rc);

    assertThat(MDC.get(CID)).isEqualTo("upstream-id");
    assertThat(store.get(CID)).isEqualTo("upstream-id");
  }

  @Test
  void rejectsMalformedInboundHeaderAndGeneratesFreshId() {
    Handler<RoutingContext> handler = captureHandler();
    Map<String, Object> store = new HashMap<>();
    RoutingContext rc = mockRc("bad id with spaces", store); // spaces are not allowed

    handler.handle(rc);

    String id = (String) store.get(CID);
    assertThat(id).isNotEqualTo("bad id with spaces");
    assertThat(id).matches("[0-9a-f-]{36}"); // a freshly generated UUID
  }
}
