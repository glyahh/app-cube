package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 只解析受管 Git 输出，不 checkout，不执行工程、filter 或子模块。 */
public final class ImportGitMetadata {
    private ImportGitMetadata() { }
    public record Branch(String name,String commit) { }
    public record Blob(String path,String objectId,long size,boolean executable) { }

    public static List<Branch> branches(byte[] output) {
        if(output==null || output.length>8*1024*1024)throw invalid();
        try{return branches(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output)).toString());}
        catch(java.nio.charset.CharacterCodingException error){throw invalid();}
    }

    public static List<Branch> branches(String output) {
        if(output==null || output.length()>8*1024*1024) throw invalid();
        var result=new ArrayList<Branch>(); var names=new HashSet<String>();
        for(String line:output.split("\r?\n")) {
            if(line.isEmpty()) continue;
            String[] parts=line.split("\t",-1);
            if(parts.length!=2 || !parts[0].matches("[0-9a-f]{40}") || !parts[1].startsWith("refs/heads/")) throw invalid();
            String name=ImportRepositorySource.branch(parts[1].substring("refs/heads/".length()));
            if(!names.add(name) || result.size()>=10000) throw invalid();
            result.add(new Branch(name,parts[0]));
        }
        return List.copyOf(result);
    }

    public static List<Blob> tree(byte[] output,int maxFiles,long maxBytes) {
        if(output==null || output.length>8*1024*1024 || maxFiles<0 || maxBytes<0) throw invalid();
        var blobs=new ArrayList<Blob>(); long total=0; int start=0;
        try {
            // 1. 按 NUL 解析路径，严格 UTF-8 避免替换字符掩盖原路径。
            for(int end=0;end<output.length;end++) {
                if(output[end]!=0) continue;
                String row=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output,start,end-start)).toString();
                start=end+1; int tab=row.indexOf('\t'); if(tab<0) throw invalid();
                String[] header=row.substring(0,tab).trim().split(" +");
                if(header.length!=4 || !header[2].matches("[0-9a-f]{40}")) throw invalid();
                if(header[0].equals("120000") || header[0].equals("160000")) {
                    throw new MyException(ErrorCode.OPERATION_ERROR,"仓库包含链接或子模块，尚未获取完整内容，已阻止导入。");
                }
                if(!(header[0].equals("100644") || header[0].equals("100755")) || !header[1].equals("blob") || !header[3].matches("[0-9]+")) throw invalid();
                long size=Long.parseLong(header[3]); total=Math.addExact(total,size);
                if(blobs.size()>=maxFiles || total>maxBytes) throw invalid();
                blobs.add(new Blob(row.substring(tab+1),header[2],size,header[0].equals("100755")));
            }
            if(start!=output.length) throw invalid();
            // 2. 复用已有全清单校验，坏路径和碰撞不能部分接入。
            ProjectImportManifest.validate(blobs.stream().map(blob->new ProjectImportManifest.FileEntry(blob.path(),blob.size())).toList(),
                    new ProjectImportManifest.Options(false,false,true),new ProjectImportManifest.Limits(maxFiles,maxBytes));
            return List.copyOf(blobs);
        } catch(MyException error) {throw error;} catch(Exception error) {throw invalid();}
    }

    public static void requireMaterializedContent(byte[] content) {
        if(content==null) throw invalid();
        byte[] prefix="version https://git-lfs.github.com/spec/v1".getBytes(StandardCharsets.US_ASCII);
        if(content!=null && content.length>=prefix.length) {
            boolean matches=true; for(int i=0;i<prefix.length;i++) if(content[i]!=prefix[i]) {matches=false;break;}
            if(matches) throw new MyException(ErrorCode.OPERATION_ERROR,"仓库包含未获取的 Git LFS 资源，已阻止导入。");
        }
    }
    private static MyException invalid(){return new MyException(ErrorCode.PARAMS_ERROR,"Git 内容清单无效或超过资源限制。");}
}
