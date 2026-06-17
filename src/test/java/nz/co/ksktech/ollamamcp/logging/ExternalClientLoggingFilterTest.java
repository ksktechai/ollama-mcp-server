package nz.co.ksktech.ollamamcp.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientResponseContext;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.slf4j.MDC;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExternalClientLoggingFilterTest {

  private ExternalClientLoggingFilter filter;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    filter = new ExternalClientLoggingFilter();
    objectMapper = new ObjectMapper();
    filter.objectMapper = objectMapper;
    MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID);
  }

  @AfterEach
  void tearDown() {
    MDC.remove(InvocationLoggingInterceptor.CORRELATION_ID);
  }

  @Test
  void testFilterRequest_forwardsCorrelationId() throws Exception {
    ClientRequestContext request = mock(ClientRequestContext.class);
    URI uri = new URI("http://localhost:11434/api/generate");
    when(request.getUri()).thenReturn(uri);
    when(request.getMethod()).thenReturn("POST");
    when(request.hasEntity()).thenReturn(true);
    when(request.getEntity()).thenReturn(Map.of("prompt", "hi"));

    var headers = mock(jakarta.ws.rs.core.MultivaluedMap.class);
    when(request.getHeaders()).thenReturn(headers);

    MDC.put(InvocationLoggingInterceptor.CORRELATION_ID, "ext-corr-id");

    filter.filter(request);

    verify(headers).add(LogSupport.CORRELATION_HEADER, "ext-corr-id");
  }

  @Test
  void testFilterResponse_buffersResponseBody() throws Exception {
    ClientRequestContext request = mock(ClientRequestContext.class);
    ClientResponseContext response = mock(ClientResponseContext.class);
    URI uri = new URI("http://localhost:11434/api/generate");
    when(request.getUri()).thenReturn(uri);
    when(request.getMethod()).thenReturn("POST");

    when(response.getStatus()).thenReturn(200);
    when(response.hasEntity()).thenReturn(true);

    String rawResponse = "{\"response\":\"completed\"}";
    ByteArrayInputStream stream = new ByteArrayInputStream(rawResponse.getBytes(StandardCharsets.UTF_8));
    when(response.getEntityStream()).thenReturn(stream);

    filter.filter(request, response);

    ArgumentCaptor<InputStream> captor = ArgumentCaptor.forClass(InputStream.class);
    verify(response).setEntityStream(captor.capture());

    InputStream replacedStream = captor.getValue();
    assertThat(replacedStream).isNotNull();
    String content = new String(replacedStream.readAllBytes(), StandardCharsets.UTF_8);
    assertThat(content).isEqualTo(rawResponse);
  }
}
