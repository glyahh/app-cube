package com.dbts.glyahhaigeneratecode.projectimport;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ImportHttpReadinessTest {
    @Test void invalidPathCannotChangeOriginOrSilentlyUseRoot(){
        assertEquals("/health/ready",ImportHttpReadiness.path("/health/ready"));
        for(String path:new String[]{"", "health", "//example.com", "http://example.com/", "/a/../health", "/health?key=x", "/health#x", "/\\host", "/a\n"})
            assertThrows(IllegalArgumentException.class,()->ImportHttpReadiness.path(path),path);
    }

    @Test void onlyFrozenHealthPathIsRequestedAndRedirectIsNotReady()throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var rootCalls=new AtomicInteger();var healthCalls=new AtomicInteger();var targetCalls=new AtomicInteger();
        server.createContext("/",exchange->{rootCalls.incrementAndGet();exchange.sendResponseHeaders(404,-1);exchange.close();});
        server.createContext("/health",exchange->{healthCalls.incrementAndGet();exchange.sendResponseHeaders(204,-1);exchange.close();});
        server.createContext("/redirect",exchange->{exchange.getResponseHeaders().add("Location","/target");exchange.sendResponseHeaders(302,-1);exchange.close();});
        server.createContext("/target",exchange->{targetCalls.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
        try{
            String base="http://127.0.0.1:"+server.getAddress().getPort()+"/";
            assertTrue(ImportHttpReadiness.ready(base,"/health",Duration.ofMillis(600)));
            assertEquals(1,healthCalls.get());assertEquals(0,rootCalls.get());
            assertFalse(ImportHttpReadiness.ready(base,"/redirect",Duration.ofMillis(600)));
            assertEquals(0,targetCalls.get());
            assertFalse(ImportHttpReadiness.ready(base,"/",Duration.ofMillis(600)));
            assertThrows(IllegalArgumentException.class,()->ImportHttpReadiness.ready("http://example.com/","/",Duration.ofMillis(600)));
        }finally{server.stop(0);}
    }
}
