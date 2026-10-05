package com.dbts.glyahhaigeneratecode.projectimport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 就绪检测只请求已声明的本机服务路径，不携带凭据、不跟随跳转。 */
final class ImportHttpReadiness {
    private ImportHttpReadiness(){ }

    static String path(String value){
        if(value==null||value.length()>512||!value.startsWith("/")||value.startsWith("//")||value.contains("\\")
            ||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("健康路径无效。");
        var uri=URI.create(value);
        if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null
            ||!uri.normalize().equals(uri))throw new IllegalArgumentException("健康路径无效。");
        return value;
    }

    static boolean ready(String address,String healthPath,Duration timeout){
        // 1. 地址必须来自回环端口映射，路径不能改写来源。
        var base=URI.create(address);
        if(!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1||base.getRawUserInfo()!=null
            ||!"/".equals(base.getRawPath())||base.getRawQuery()!=null||base.getRawFragment()!=null)
            throw new IllegalArgumentException("健康检测地址无效。");
        var target=URI.create(address.substring(0,address.length()-1)+path(healthPath));
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        long deadline=System.nanoTime()+timeout.toNanos();
        // 2. 只接受该路径的 2xx；认证跳转、404 和网络失败都不能算就绪。
        while(System.nanoTime()<deadline){
            try{
                var response=client.send(HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(2)).GET().build(),HttpResponse.BodyHandlers.discarding());
                if(response.statusCode()>=200&&response.statusCode()<300)return true;
            }catch(IOException error){
                // 在本次检测期限内等待服务，不改成检测根路径。
            }catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
            try{Thread.sleep(200);}catch(InterruptedException error){Thread.currentThread().interrupt();return false;}
        }
        return false;
    }
}
