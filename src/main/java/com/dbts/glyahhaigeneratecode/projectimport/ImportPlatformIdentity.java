package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 平台身份只读认证；秘密仅进入受管 curl stdin，不读取宿主 curl 配置。 */
public final class ImportPlatformIdentity {
    private ImportPlatformIdentity(){ }
    public static String authenticate(Path curl,Path directory,String provider,String token,ImportGitNetworkPolicy network,
                                      BooleanSupplier eligible,Consumer<ImportProcessRunner.Resource> journal) {
        String host=provider.equals("GitHub")?"api.github.com":provider.equals("Gitee")?"gitee.com":null;
        if(host==null || token==null || token.isEmpty() || token.length()>4096 || token.chars().anyMatch(c->c<33 || c>126))throw denied("AUTH_INPUT_INVALID");
        var command=new ArrayList<String>();command.add(curl.toAbsolutePath().toString());
        command.addAll(java.util.List.of("--disable","--silent","--show-error","--proto","=https","--proto-redir","=https",
                "--connect-timeout","10","--max-time","30","--max-redirs","0","--config","-","--write-out","\n%{http_code}"));
        if(network.evidenceMode().equals("PINNED_PUBLIC_DNS_TLS_HOST")) {
            String prefix="http.curloptResolve="+host+":443:";
            String pin=network.gitConfig().stream().filter(value->value.startsWith(prefix)).findFirst().orElseThrow(()->denied("AUTH_NETWORK_DENIED"));
            command.addAll(java.util.List.of("--proxy","","--resolve",pin.substring("http.curloptResolve=".length())));
        }else if(network.evidenceMode().equals("EXPLICIT_PROXY_TLS_HOST")) {
            String proxy=network.gitConfig().stream().filter(value->value.startsWith("http.proxy=http://127.0.0.1:")).findFirst().orElseThrow(()->denied("AUTH_NETWORK_DENIED"));
            ImportGitNetworkPolicy.proxy(proxy.substring("http.proxy=".length()));
            command.addAll(java.util.List.of("--proxy",proxy.substring("http.proxy=".length())));
        }else throw denied("AUTH_NETWORK_DENIED");
        command.add("https://"+host+(provider.equals("GitHub")?"/user":"/api/v5/user"));
        String escaped=token.replace("\\","\\\\").replace("\"","\\\"");
        byte[] configuration=("header = \"Authorization: Bearer "+escaped+"\"\nheader = \"Accept: application/json\"\nuser-agent = \"app-cube-import\"\n").getBytes(StandardCharsets.US_ASCII);
        byte[] result=null;
        try {
            Map<String,String> environment=System.getenv("SystemRoot")==null?Map.of():Map.of("SystemRoot",System.getenv("SystemRoot"));
            result=ImportProcessRunner.run(command,directory,environment,Duration.ofSeconds(35),1024*1024,65536,eligible,journal,
                    configuration,input->input.readAllBytes()).value();
            if(!eligible.getAsBoolean())throw denied("AUTH_REVOKED");
            return login(result);
        }finally{Arrays.fill(configuration,(byte)0);if(result!=null)Arrays.fill(result,(byte)0);}
    }

    static String login(byte[] response) {
        try {
            if(response.length<4 || response[response.length-4]!='\n')throw denied("AUTH_RESPONSE_INVALID");
            String status=new String(response,response.length-3,3,StandardCharsets.US_ASCII);
            if(!status.equals("200"))throw denied(switch(status){case "401"->"AUTH_INVALID";case "403"->"AUTH_FORBIDDEN";case "429"->"AUTH_RATE_LIMITED";
                default->status.startsWith("3")?"AUTH_REDIRECT_BLOCKED":"AUTH_PLATFORM_FAILED";});
            var body=new ObjectMapper().readTree(response,0,response.length-4);
            if(body==null || !body.isObject() || !body.path("login").isTextual() || !body.path("id").isIntegralNumber() || !body.path("id").canConvertToLong() || body.path("id").longValue()<=0)
                throw denied("AUTH_RESPONSE_INVALID");
            String login=body.path("login").textValue();
            if(!login.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}"))throw denied("AUTH_RESPONSE_INVALID");
            return login;
        }catch(Failure error){throw error;}catch(Exception error){throw denied("AUTH_RESPONSE_INVALID");}
    }
    public static String errorCode(Throwable error){return error instanceof ImportCredentialBroker.StopFailure?"STOP_FAILED":error instanceof Failure known?known.reason:ImportProcessRunner.errorCode(error);}
    private static Failure denied(String code){return new Failure(code);}
    private static final class Failure extends MyException {
        final String reason;
        Failure(String code){super(ErrorCode.OPERATION_ERROR,"平台认证失败："+code+"。");reason=code;}
    }
}
