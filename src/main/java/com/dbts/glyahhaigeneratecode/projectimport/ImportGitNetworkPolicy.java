package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import java.net.InetAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 直连固定已核验地址；显式本机代理与直连 DNS 证明分开记录。 */
public record ImportGitNetworkPolicy(List<String> gitConfig,String evidenceMode) {
    private static final Set<String> HOSTS=Set.of("github.com","gitee.com","api.github.com");
    public ImportGitNetworkPolicy {gitConfig=List.copyOf(gitConfig);}

    public void requireSource(ImportRepositorySource source) {
        String host=URI.create(ImportRepositorySource.parse(source.provider(),source.cloneUrl()).cloneUrl()).getHost();
        if("PINNED_PUBLIC_DNS_TLS_HOST".equals(evidenceMode)) {
            if(gitConfig.stream().noneMatch(value->value.startsWith("http.curloptResolve="+host+":443:")))throw denied();
        } else if(!"EXPLICIT_PROXY_TLS_HOST".equals(evidenceMode))throw denied();
    }

    public static ImportGitNetworkPolicy direct(String host,List<InetAddress> addresses) {
        if(!HOSTS.contains(host) || addresses==null || addresses.isEmpty() || addresses.size()>16 || addresses.stream().anyMatch(address->!publicAddress(address))) throw denied();
        var config=base(); config.add("http.proxy=");
        String pinned=addresses.stream().map(address->address.getAddress().length==16?"["+address.getHostAddress()+"]":address.getHostAddress()).collect(Collectors.joining(","));
        config.add("http.curloptResolve="+host+":443:"+pinned);
        return new ImportGitNetworkPolicy(config,"PINNED_PUBLIC_DNS_TLS_HOST");
    }

    public static ImportGitNetworkPolicy proxy(String configured) {
        try {
            if(configured==null || configured.length()>128) throw denied();
            URI proxy=URI.create(configured);
            if(!"http".equals(proxy.getScheme()) || !"127.0.0.1".equals(proxy.getHost()) || proxy.getPort()<1
                    || proxy.getRawUserInfo()!=null || proxy.getRawQuery()!=null || proxy.getRawFragment()!=null
                    || !(proxy.getRawPath().isEmpty() || proxy.getRawPath().equals("/"))) throw denied();
            var config=base(); config.add("http.proxy=http://127.0.0.1:"+proxy.getPort());
            return new ImportGitNetworkPolicy(config,"EXPLICIT_PROXY_TLS_HOST");
        } catch(RuntimeException error){throw denied();}
    }

    public static ImportGitNetworkPolicy offline() {
        var config=base();config.add("protocol.https.allow=never");config.add("http.proxy=");
        return new ImportGitNetworkPolicy(config,"OFFLINE_FIXED_OBJECTS");
    }

    private static ArrayList<String> base() {
        return new ArrayList<>(List.of("http.sslVerify=true","http.followRedirects=false","http.extraHeader=",
                "http.cookieFile=","http.saveCookies=false","credential.helper=","credential.useHttpPath=true",
                "protocol.allow=never","protocol.https.allow=always"));
    }

    private static boolean publicAddress(InetAddress address) {
        if(address==null || address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes=address.getAddress();
        if(bytes.length==4) {
            int a=bytes[0]&255,b=bytes[1]&255,c=bytes[2]&255;
            return !(a==0 || a==10 || a==127 || a>=224 || (a==100 && b>=64 && b<=127)
                    || (a==169 && b==254) || (a==172 && b>=16 && b<=31) || (a==192 && b==168)
                    || (a==192 && b==0 && (c==0 || c==2)) || (a==198 && (b==18 || b==19 || (b==51 && c==100)))
                    || (a==203 && b==0 && c==113));
        }
        if(bytes.length!=16 || ((bytes[0]&255)&0xe0)!=0x20 || address.getHostAddress().contains("%")) return false;
        // 不将文档、隧道或嵌入私网地址的过渡范围当真实平台地址。
        return !((bytes[0]&255)==0x20 && (bytes[1]&255)==0x02)
                && !((bytes[0]&255)==0x20 && (bytes[1]&255)==0x01
                && (((bytes[2]&255)==0x0d && (bytes[3]&255)==0xb8) || (bytes[2]==0 && bytes[3]==0)));
    }
    private static MyException denied(){return new MyException(ErrorCode.OPERATION_ERROR,"Git 网络目的地或代理配置不被允许，已阻止读取。");}
}
