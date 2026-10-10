package com.dbts.glyahhaigeneratecode.projectimport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 仅平台调用：独立恢复授权源码并计算摘要，不接收候选自报的恢复结果。 */
public final class ImportAgentSecretRestorer {
    public record Binding(String marker,byte[] value,int count){
        public Binding{
            if(marker==null||!marker.matches("__GLYAHH_SECRET_[0-9a-f]{32}__")||value==null||value.length==0||count<1)
                throw new IllegalArgumentException("秘密映射无效。");
            value=value.clone();
        }
        @Override public byte[] value(){return value.clone();}
        @Override public String toString(){return "Binding[private]";}
    }
    private static final java.util.regex.Pattern MARKER=java.util.regex.Pattern.compile("__GLYAHH_SECRET_[0-9a-f]{32}__");

    /** 从独立原字节重建去秘密基线，与Python只替换代码字符串的规则一致。 */
    public byte[] baseline(byte[] original,String path,List<Binding> bindings)throws IOException{
        try{
            String text=new String(original,StandardCharsets.ISO_8859_1);
            var byValue=new HashMap<String,String>();
            for(var binding:bindings){
                String value=new String(binding.value(),StandardCharsets.ISO_8859_1);
                if(byValue.putIfAbsent(value,binding.marker())!=null)throw rejected();
            }
            if(byValue.isEmpty())return original.clone();
            var values=byValue.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
            var pattern=java.util.regex.Pattern.compile(values.stream().map(value->{
                String quoted=java.util.regex.Pattern.quote(value);
                return new String(value.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8).codePointCount(0,
                        new String(value.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8).length())>=6
                        ?quoted:"(?<![A-Za-z0-9_])"+quoted+"(?![A-Za-z0-9_])";
            }).collect(java.util.stream.Collectors.joining("|")));
            String extension=path.substring(path.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            if(Set.of("py","java","js","ts","jsx","tsx","vue","go","rs","c","cpp","cs","kt").contains(extension)){
                var strings=java.util.regex.Pattern.compile("(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')",java.util.regex.Pattern.DOTALL).matcher(text);
                var result=new StringBuffer();
                while(strings.find())strings.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(substitute(strings.group(),pattern,byValue)));
                strings.appendTail(result);text=result.toString();
            }else text=substitute(text,pattern,byValue);
            return text.getBytes(StandardCharsets.ISO_8859_1);
        }catch(RuntimeException error){throw rejected();}
    }
    private static String substitute(String text,java.util.regex.Pattern pattern,Map<String,String> values){
        var matcher=pattern.matcher(text);var output=new StringBuffer();
        while(matcher.find())matcher.appendReplacement(output,java.util.regex.Matcher.quoteReplacement(values.get(matcher.group())));
        matcher.appendTail(output);return output.toString();
    }

    public ImportAgentCandidateVerifier.FileStamp restore(byte[] original,byte[] baseline,byte[] edited,
            ImportAgentCandidateVerifier.FileStamp sourceStamp,ImportAgentCandidateVerifier.FileStamp baselineStamp,
            ImportAgentCandidateVerifier.FileStamp authorized,List<Binding> bindings)throws IOException{
        try{
            // 1. 所有字节均先对独立冻结摘要，不能以候选清单为原内容依据。
            if(!matches(original,sourceStamp)||!matches(baseline,baselineStamp)||!matches(edited,authorized))throw rejected();
            var names=new HashSet<String>();
            byte[] restoredBaseline=baseline.clone(),restoredEdited=edited.clone();
            for(var binding:bindings){
                if(!names.add(binding.marker()))throw rejected();
                byte[] marker=binding.marker().getBytes(StandardCharsets.US_ASCII);
                if(count(baseline,marker)!=binding.count()||count(edited,marker)!=binding.count())throw rejected();
                restoredBaseline=replace(restoredBaseline,marker,binding.value());
                restoredEdited=replace(restoredEdited,marker,binding.value());
            }
            // 2. 原内容必须完整重建；秘密所在整行保持，防止换字段或复制到新位置。
            if(!Arrays.equals(original,restoredBaseline)||!markers(baseline).equals(names)||!markers(edited).equals(names)
                    ||!boundLines(baseline,names).equals(boundLines(edited,names)))throw rejected();
            return new ImportAgentCandidateVerifier.FileStamp(hash(restoredEdited),restoredEdited.length,authorized.mode());
        }catch(RuntimeException error){throw rejected();}
    }
    private static Map<String,Integer> boundLines(byte[] raw,Set<String> names){
        var result=new HashMap<String,Integer>();
        for(String line:new String(raw,StandardCharsets.ISO_8859_1).split("\n",-1))
            if(names.stream().anyMatch(line::contains))result.merge(line,1,Integer::sum);
        return result;
    }
    private static Set<String> markers(byte[] raw){
        var result=new HashSet<String>();var matcher=MARKER.matcher(new String(raw,StandardCharsets.ISO_8859_1));
        while(matcher.find())result.add(matcher.group());return result;
    }
    private static boolean matches(byte[] raw,ImportAgentCandidateVerifier.FileStamp stamp){
        return raw!=null&&stamp!=null&&raw.length==stamp.bytes()&&hash(raw).equals(stamp.sha256());
    }
    private static int count(byte[] raw,byte[] value){int total=0;for(int at=0;at<=raw.length-value.length;at++)if(at(raw,value,at)){total++;at+=value.length-1;}return total;}
    private static boolean at(byte[] raw,byte[] value,int offset){for(int i=0;i<value.length;i++)if(raw[offset+i]!=value[i])return false;return true;}
    private static byte[] replace(byte[] raw,byte[] marker,byte[] value){
        var output=new ByteArrayOutputStream();
        for(int offset=0;offset<raw.length;){
            if(offset<=raw.length-marker.length&&at(raw,marker,offset)){output.writeBytes(value);offset+=marker.length;}
            else output.write(raw[offset++]);
        }
        return output.toByteArray();
    }
    private static String hash(byte[] raw){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("摘要不可用。");}
    }
    private static IOException rejected(){return new IOException("SECRET_RESTORE_NOT_VERIFIED");}
}
