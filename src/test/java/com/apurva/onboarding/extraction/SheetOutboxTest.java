package com.apurva.onboarding.extraction;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SheetOutboxTest {
    @TempDir Path dir;
    @Test void lostWriteAcknowledgementRetriesTheSameRowWithoutDuplicates() throws Exception {
        var settings=new ExtractionSettings(new MockEnvironment().withProperty("EXTRACTION_DATA_DIR",dir.toString()));
        Map<String,String> remote=new HashMap<>(); AtomicInteger rowWrites=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String range=exchange.getRequestURI().getPath();int status=200;String body;
            if(exchange.getRequestMethod().equals("GET")) body=remote.getOrDefault(range,"{}");
            else {
                assertThat(exchange.getRequestURI().getQuery()).contains("valueInputOption=RAW");
                body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);remote.put(range,body);
                if(range.contains("A2:R2") && rowWrites.incrementAndGet()==1) status=500; // applied, acknowledgement lost
            }
            byte[] response=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,response.length);
            exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try(var resource=new StoreResource(new ExtractionStore(settings))) {
            var store=resource.store;
            var pipeline=new ExtractionPipeline(new DocumentReader(),image->{throw new AssertionError();},new LocalFieldExtractor(),store,settings);
            byte[] bytes=ExtractionPipelineTest.pdf(ExtractionPipelineTest.concat(ExtractionPipelineTest.FIRST,ExtractionPipelineTest.SECOND));
            pipeline.process(bytes,"one");pipeline.process(bytes,"two");
            var env=new MockEnvironment().withProperty("EXTRACTION_SHEETS_ENABLED","true").withProperty("EXTRACTION_DEMO_SPREADSHEET_ID","fictional")
                    .withProperty("EXTRACTION_SHEETS_ACCESS_TOKEN","test-only");
            var sync=new SheetOutbox(store,env,"http://127.0.0.1:"+server.getAddress().getPort()+"/");
            assertThat(sync.sync().get("written")).isEqualTo(1);
            assertThat(rowWrites.get()).isEqualTo(2);
            assertThat(remote).hasSize(2); // one header plus one operational row
            assertThat(store.pending()).isEmpty();
            assertThat(sync.sync().get("written")).isEqualTo(0);
        } finally {server.stop(0);}
    }
    record StoreResource(ExtractionStore store) implements AutoCloseable {public void close() throws Exception {store.close();}}
}
