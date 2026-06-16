package nz.co.ksktech.ollamamcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import nz.co.ksktech.ollamamcp.service.OllamaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The tools are intentionally thin pass-throughs, so these tests just confirm each {@code @Tool}
 * method delegates to {@link OllamaService} with the right arguments and returns its result.
 */
class OllamaToolsTest {

  private OllamaService service;
  private OllamaTools tools;

  @BeforeEach
  void setUp() {
    service = mock(OllamaService.class);
    tools = new OllamaTools();
    tools.ollama = service; // package-private @Inject field
  }

  @Test
  void listDelegates() {
    when(service.listModels()).thenReturn("[\"a\"]");
    assertThat(tools.ollama_list()).isEqualTo("[\"a\"]");
    verify(service).listModels();
  }

  @Test
  void showDelegates() {
    when(service.show("m")).thenReturn("{}");
    assertThat(tools.ollama_show("m")).isEqualTo("{}");
    verify(service).show("m");
  }

  @Test
  void psDelegates() {
    when(service.ps()).thenReturn("{\"models\":[]}");
    assertThat(tools.ollama_ps()).isEqualTo("{\"models\":[]}");
    verify(service).ps();
  }

  @Test
  void generateDelegates() {
    when(service.generate("p", "m")).thenReturn("text");
    assertThat(tools.ollama_generate("p", "m")).isEqualTo("text");
    verify(service).generate("p", "m");
  }

  @Test
  void chatDelegates() {
    when(service.chat("[]", null)).thenReturn("reply");
    assertThat(tools.ollama_chat("[]", null)).isEqualTo("reply");
    verify(service).chat(eq("[]"), eq(null));
  }

  @Test
  void embedDelegates() {
    when(service.embed("hi", "m")).thenReturn("{\"embeddings\":[]}");
    assertThat(tools.ollama_embed("hi", "m")).isEqualTo("{\"embeddings\":[]}");
    verify(service).embed("hi", "m");
  }
}
