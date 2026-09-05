package com.apurva.onboarding.extraction;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class GeminiFieldExtractorTest {
    HttpServer server;
    @TempDir Path dir;
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    GeminiFieldExtractor provider(int status,String body) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/model",exchange->{
            String request=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            assertThat(request).contains("responseJsonSchema","systemInstruction","untrusted data");
            exchange.getResponseHeaders().add("Retry-After","2");
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        var settings=new ExtractionSettings(new MockEnvironment().withProperty("EXTRACTION_DATA_DIR",dir.toString()).withProperty("EXTRACTION_GEMINI_API_KEY","test-only"));
        return new GeminiFieldExtractor(settings,HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/model"));
    }
    @Test void rateLimitIsRetryableAndCarriesBackoff() throws Exception {
        var provider=provider(429,"{}");
        assertThatThrownBy(()->provider.extract("sample")).isInstanceOfSatisfying(FieldExtractor.CallFailure.class,
                e->{assertThat(e.retryable).isTrue();assertThat(e.retryAfterMillis).isEqualTo(2000);});
    }
    @Test void invalidCredentialsAreNotRetried() throws Exception {
        var provider=provider(403,"{}");
        assertThatThrownBy(()->provider.extract("sample")).isInstanceOfSatisfying(FieldExtractor.CallFailure.class,e->assertThat(e.retryable).isFalse());
    }
    @Test void truncatedOutputIsRejected() throws Exception {
        var provider=provider(200,"{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}");
        assertThatThrownBy(()->provider.extract("sample")).hasMessage("MODEL_INCOMPLETE_RESPONSE");
    }
    @Test void successfulStructuredResponsePassesThroughTheActualTransport() throws Exception {
        String extracted=new LocalFieldExtractor().extract(String.join("\n",ExtractionPipelineTest.concat(ExtractionPipelineTest.FIRST,ExtractionPipelineTest.SECOND)));
        var json=new tools.jackson.databind.ObjectMapper();
        String envelope=json.writeValueAsString(java.util.Map.of("candidates",java.util.List.of(java.util.Map.of("finishReason","STOP",
                "content",java.util.Map.of("parts",java.util.List.of(java.util.Map.of("text",extracted)))))));
        assertThat(provider(200,envelope).extract("sample")).isEqualTo(extracted);
    }
}
