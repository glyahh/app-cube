package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** 应用内的手动声明；校验与保存不授予 Shell、安装或运行权限。 */
public final class ImportStartupConfiguration {
    private ImportStartupConfiguration(){ }
    public record Manual(UUID id,String name,String directory,String command,String install,String build,Integer port){
        public Manual(UUID id,String name,String directory,String command,String install,String build){this(id,name,directory,command,install,build,null);}
    }
    public record State(long appId,long ownerId,UUID importTaskId,long configRevision,long contextEpoch,String scope,List<Manual> services){
        public State{services=List.copyOf(services);}
        public View view(){return new View(Long.toString(appId),importTaskId,Long.toString(configRevision),Long.toString(contextEpoch),scope,services);}
    }
    public record View(String appId,UUID importTaskId,String configRevision,String contextEpoch,String scope,List<Manual> services){ }

    public static List<Manual> validate(Path workspace,List<Manual> services,List<Manual> previous)throws IOException{
        if(services==null||services.size()>20||previous==null)throw new IllegalArgumentException("服务配置数量无效。");
        var known=new HashSet<UUID>();for(var entry:previous)known.add(entry.id());
        var ids=new HashSet<UUID>();var names=new HashSet<String>();var result=new ArrayList<Manual>();
        // 1. 服务身份只能保留当前应用已有项，新项由服务端分配。
        for(Manual service:services){
            if(service==null)throw new IllegalArgumentException("服务配置缺失。");
            UUID id=service.id()==null?UUID.randomUUID():service.id();
            if((service.id()!=null&&!known.contains(id))||!ids.add(id))throw new IllegalArgumentException("服务身份无效，请重新读取配置。");
            String name=required(service.name(),80);
            if(!names.add(name))throw new IllegalArgumentException("服务名称重复。");
            validateDirectory(workspace,service.directory());
            Integer port=service.port();
            if(port!=null&&(port<1||port>65535))throw new IllegalArgumentException("端口无效。");
            result.add(new Manual(id,name,service.directory(),required(service.command(),1024),optional(service.install()),optional(service.build()),port));
        }
        return List.copyOf(result);
    }

    public static void validateDirectory(Path workspace,String directory)throws IOException{
        if(directory==null)throw new IllegalArgumentException("目录缺失。");
        Path root=workspace.toAbsolutePath().normalize();
        if(!directory.equals("."))ProjectImportManifest.validate(List.of(new ProjectImportManifest.FileEntry(directory+"/scope-check",0)),
            new ProjectImportManifest.Options(false,false,true),new ProjectImportManifest.Limits(1,0));
        Path target=directory.equals(".")?root:root.resolve(directory).normalize();
        if(!target.startsWith(root)||!Files.isDirectory(target,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("请选择工程内有效目录。");
        for(String part:directory.split("/"))if(Set.of(".glyahh",".git").contains(part.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("该目录不能作为手动服务范围。");
        ProjectImportStaging.guardManagedPath(target);
    }

    private static String required(String value,int limit){
        if(value==null||value.isBlank()||value.length()>limit||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("名称或命令无效。");
        return value;
    }
    private static String optional(String value){return value==null||value.isEmpty()?null:required(value,1024);}
}
