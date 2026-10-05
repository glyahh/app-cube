package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import java.net.URI;

/** 规范来源只允许双平台 HTTPS；分支是单个引用，不是任意 Git 表达式。 */
public record ImportRepositorySource(String provider,String owner,String repository,String cloneUrl) {
    public static ImportRepositorySource parse(String provider,String input) {
        try {
            String host=switch(provider){case "GitHub"->"github.com";case "Gitee"->"gitee.com";default->throw invalid();};
            if(input==null || input.length()>512 || input.chars().anyMatch(c->c<=32 || c>126)) throw invalid();
            URI url=URI.create(input);
            if(!"https".equals(url.getScheme()) || !host.equalsIgnoreCase(url.getHost()) || url.getRawUserInfo()!=null
                    || url.getRawQuery()!=null || url.getRawFragment()!=null || (url.getPort()!=-1 && url.getPort()!=443)) throw invalid();
            String path=url.getRawPath(); if(path.endsWith("/")) path=path.substring(0,path.length()-1);
            String[] parts=path.split("/",-1);
            if(parts.length!=3 || !parts[0].isEmpty() || !parts[1].matches("[A-Za-z0-9][A-Za-z0-9_-]{0,99}")) throw invalid();
            String repo=parts[2].endsWith(".git")?parts[2].substring(0,parts[2].length()-4):parts[2];
            if(!repo.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}") || repo.contains("..")) throw invalid();
            return new ImportRepositorySource(provider,parts[1],repo,"https://"+host+"/"+parts[1]+"/"+repo+".git");
        } catch(Exception error){throw invalid();}
    }

    public static String branch(String value) {
        if(value==null || value.isBlank() || value.length()>255 || value.startsWith("-") || value.startsWith("/") || value.endsWith("/")
                || value.endsWith(".") || value.contains("..") || value.contains("@{") || value.contains("//")
                || value.chars().anyMatch(c->c<=32 || c==127 || "~^:?*[\\".indexOf(c)>=0)) throw invalid();
        for(String part:value.split("/")) if(part.startsWith(".") || part.endsWith(".lock")) throw invalid();
        if(value.equals("@")) throw invalid();
        return value;
    }
    private static MyException invalid(){return new MyException(ErrorCode.PARAMS_ERROR,"仓库地址或分支无效。");}
}
