package com.dbts.glyahhaigeneratecode.projectimport;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** 只连接受管 askpass 的私有回环通道；能力值和秘密都不进入命令参数。 */
public final class ImportCredentialBroker implements AutoCloseable {
    public record Secret(String username,@com.fasterxml.jackson.annotation.JsonProperty(access=com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String token) {
        @Override public String toString(){return "GitCredential[secret omitted]";}
    }
    private final HttpServer server;
    private final ExecutorService executor;
    private final String capability;
    private final URI repository;
    private final BooleanSupplier eligible;
    private final Supplier<Secret> secret;
    private volatile boolean stoppedAccepting;

    public ImportCredentialBroker(ImportRepositorySource source,BooleanSupplier eligible,Supplier<Secret> secret) throws IOException {
        this.repository=URI.create(source.cloneUrl()); this.eligible=eligible; this.secret=secret;
        byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
        this.capability=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); Arrays.fill(bytes,(byte)0);
        executor=Executors.newSingleThreadExecutor(runnable->{Thread thread=new Thread(runnable,"import-credential-broker");thread.setDaemon(true);return thread;});
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),4);
        server.setExecutor(executor); server.createContext("/credential",this::respond); server.start();
    }
    public String endpoint(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/credential";}
    public String capability(){return capability;}
    public boolean confirmedStopped(){return stoppedAccepting && executor.isTerminated();}

    private void respond(HttpExchange request) throws IOException {
        byte[] response=null;
        try {
            // 1. 不接受浏览器或 URL 携带能力值，只接受私有 helper 的固定请求。
            String supplied=request.getRequestHeaders().getFirst("X-Import-Capability");
            if(!request.getRequestMethod().equals("POST") || !request.getRequestURI().toString().equals("/credential")
                    || request.getRequestHeaders().containsKey("Origin") || supplied==null
                    || !MessageDigest.isEqual(capability.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII))) {
                reply(request,403,new byte[0]);return;
            }
            byte[] input=request.getRequestBody().readNBytes(4097);
            if(input.length>4096){reply(request,413,new byte[0]);return;}
            String prompt=new String(input,StandardCharsets.UTF_8);
            boolean password=prompt.startsWith("Password for '");
            if(!(password || prompt.startsWith("Username for '")) || !matchesRepository(prompt)) {
                reply(request,403,new byte[0]);return;
            }
            if(!eligible.getAsBoolean()){reply(request,409,new byte[0]);return;}
            // 2. 回调必须核对当前租约；不缓存或在失败时换凭据。
            Secret current=secret.get();
            String value=password?current.token():current.username();
            if(value==null || value.isBlank() || value.length()>4096 || value.chars().anyMatch(c->c<33 || c>126)) throw new IllegalStateException();
            if(!eligible.getAsBoolean()){reply(request,409,new byte[0]);return;}
            response=value.getBytes(StandardCharsets.UTF_8);
            reply(request,200,response);
        } catch(RuntimeException error){reply(request,503,new byte[0]);}
        finally{if(response!=null)Arrays.fill(response,(byte)0);request.close();}
    }

    private boolean matchesRepository(String prompt) {
        try {
            int first=prompt.indexOf('\''); int last=prompt.indexOf('\'',first+1);
            if(first<0 || last<0 || !prompt.substring(last).trim().equals("':")) return false;
            URI actual=URI.create(prompt.substring(first+1,last));
            return "https".equals(actual.getScheme()) && repository.getHost().equalsIgnoreCase(actual.getHost())
                    && actual.getPort()==-1 && repository.getRawPath().equals(actual.getRawPath())
                    && actual.getRawQuery()==null && actual.getRawFragment()==null;
        } catch(RuntimeException error){return false;}
    }

    private static void reply(HttpExchange exchange,int status,byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.sendResponseHeaders(status,body.length==0?-1:body.length);
        if(body.length!=0)exchange.getResponseBody().write(body);
    }

    @Override public void close() {
        server.stop(0); stoppedAccepting=true;executor.shutdownNow();
        try {
            if(!executor.awaitTermination(5,TimeUnit.SECONDS)) throw new StopFailure();
        } catch(InterruptedException error){Thread.currentThread().interrupt();throw new StopFailure();}
    }
    public static final class StopFailure extends com.dbts.glyahhaigeneratecode.exception.MyException {
        private StopFailure(){super(com.dbts.glyahhaigeneratecode.exception.ErrorCode.OPERATION_ERROR,"凭据通道停止未确认。");}
    }
}
