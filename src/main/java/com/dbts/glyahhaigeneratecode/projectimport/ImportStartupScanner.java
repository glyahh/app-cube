package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 只解析已取得的不可变文本输入；不访问磁盘、网络、模型或执行器。 */
public final class ImportStartupScanner {
    public record Candidate(String id,String name,String directory,String command,String build,String source,String evidence,Integer port,String healthPath){
        public Candidate(String id,String name,String directory,String command,String build,String source,String evidence,Integer port){this(id,name,directory,command,build,source,evidence,port,null);}
        public Candidate(String id,String name,String directory,String command,String build,String source,String evidence){this(id,name,directory,command,build,source,evidence,null);}
    }
    public record Scope(String directory,String phase,String status,String diagnostic){ }
    public record Result(String inputHash,List<Candidate> candidates,List<Scope> scopes){
        public Result{candidates=List.copyOf(candidates);scopes=List.copyOf(scopes);}
    }
    private static final Set<String> IGNORED=Set.of("node_modules","vendor",".venv","venv","dist","target","build","out",".git",".glyahh",".cache","coverage","examples","example","test","tests","fixtures","__pycache__");
    private final ObjectMapper json=new ObjectMapper(JsonFactory.builder().streamReadConstraints(
        StreamReadConstraints.builder().maxNestingDepth(64).build()).build()).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final int maxFiles,maxConfigBytes;
    public ImportStartupScanner(){this(10_000,256*1024);}
    public ImportStartupScanner(int maxFiles,int maxConfigBytes){
        if(maxFiles<=0||maxConfigBytes<=0)throw new IllegalArgumentException("扫描限额无效。");
        this.maxFiles=maxFiles;this.maxConfigBytes=maxConfigBytes;
    }

    public Result scan(Map<String,String> input,String selectedDirectory){
        Objects.requireNonNull(input,"扫描输入缺失。");
        Map<String,String> files=Map.copyOf(input);
        String scope=directory(selectedDirectory);
        if(!exists(files,scope)||ignored(scope))throw new IllegalArgumentException("请选择工程内有效目录。");
        if(files.size()>maxFiles)return new Result(null,List.of(),List.of(new Scope(scope,"DISCOVERY","INCOMPLETE","FILE_LIMIT")));
        // 1. 复用导入路径规则；输入摘要只标识本解析输入，不冒充完整工作区快照。
        if(!files.isEmpty())ProjectImportManifest.validate(files.entrySet().stream()
                .map(row->new ProjectImportManifest.FileEntry(row.getKey(),row.getValue().getBytes(StandardCharsets.UTF_8).length)).toList(),
                ProjectImportManifest.Options.defaults(),new ProjectImportManifest.Limits(maxFiles,Long.MAX_VALUE));
        String hash=hash(files);
        if(files.containsKey(".glyahh/run.json"))return declared(files,scope,hash);
        // 2. 每个工程分别诊断，不把首个可识别结果当作全工程结果。
        var candidates=new ArrayList<Candidate>();var diagnostics=new ArrayList<Scope>();
        var directories=new TreeSet<String>();
        for(String path:files.keySet())if(!ignored(path)&&within(parent(path),scope)
                && Set.of("package.json","pom.xml","pyproject.toml","requirements.txt","index.html").contains(path.substring(path.lastIndexOf('/')+1)))directories.add(parent(path));
        for(String dir:directories){
            // 同目录不同语言独立解析；一个配置损坏不能吞掉另一语言的合法候选。
            for(String manifest:List.of("package.json","pom.xml"))if(files.containsKey(path(dir,manifest))){
                try{
                    if(manifest.equals("package.json"))node(files,dir,candidates,diagnostics);
                    else maven(files,dir,candidates,diagnostics);
                }catch(Limit error){diagnostics.add(new Scope(dir,"FIXED","INCOMPLETE","CONFIG_LIMIT"));}
                catch(Exception error){diagnostics.add(new Scope(dir,"FIXED","FAILED","CONFIG_INVALID"));}
            }
                if(files.containsKey(path(dir,"pyproject.toml"))||files.containsKey(path(dir,"requirements.txt")))diagnostics.add(new Scope(dir,"ALGORITHM","INCOMPLETE","PYTHON_RULES_NOT_IMPLEMENTED"));
                if(!files.containsKey(path(dir,"package.json"))&&!files.containsKey(path(dir,"pom.xml"))
                    &&!files.containsKey(path(dir,"pyproject.toml"))&&!files.containsKey(path(dir,"requirements.txt"))) {
                    candidates.add(new Candidate(path(dir,"index.html"),name(dir),dir,null,null,"STATIC",path(dir,"index.html")));
                    diagnostics.add(new Scope(dir,"FIXED","IDENTIFIED",null));
                }
        }
        if(diagnostics.isEmpty())diagnostics.add(new Scope(scope,"ALGORITHM","UNIDENTIFIED","NO_ENTRY"));
        return new Result(hash,candidates,diagnostics);
    }

    private Result declared(Map<String,String> files,String scope,String hash){
        try {
            JsonNode root=read(files.get(".glyahh/run.json"));JsonNode processes=root.get("processes");
            if(root.isObject()&&root.has("kind")&&"STATIC".equals(text(root.get("kind")))){
                if(processes!=null&&(!processes.isArray()||!processes.isEmpty()))throw new IllegalArgumentException();
                if(!files.containsKey(path(scope,"index.html")))return new Result(hash,List.of(),List.of(new Scope(scope,"FIXED","INCOMPLETE","STATIC_ENTRY_MISSING")));
                if(root.hasNonNull("buildCommand"))return new Result(hash,List.of(),List.of(new Scope(scope,"FIXED","INCOMPLETE","STATIC_BUILD_OUTPUT_NOT_CONFIRMED")));
                return new Result(hash,List.of(new Candidate("declared:static",name(scope),scope,null,null,"DECLARED",".glyahh/run.json + "+path(scope,"index.html"))),
                    List.of(new Scope(scope,"FIXED","IDENTIFIED",null)));
            }
            if(!root.isObject()||processes==null||!processes.isArray()||processes.isEmpty()||processes.size()>20)throw new IllegalArgumentException();
            String build=root.hasNonNull("buildCommand")?text(root.get("buildCommand")):null;
            if(build!=null&&(build.length()>1024||build.chars().anyMatch(Character::isISOControl)))throw new IllegalArgumentException();
            var result=new ArrayList<Candidate>();var names=new HashSet<String>();
            for(JsonNode row:processes){
                if(!row.isObject())throw new IllegalArgumentException();
                String title=text(row.get("name")),command=text(row.get("command"));
                String dir=row.has("workDir")&&!row.get("workDir").asText().isEmpty()?directory(text(row.get("workDir"))):".";
                if(title.length()>80||command.length()>1024||command.chars().anyMatch(Character::isISOControl)||!names.add(title)||!exists(files,dir)||ignored(dir))throw new IllegalArgumentException();
                // 只接受声明里写明的端口，不从命令文本猜测。
                Integer port=null;
                if(row.has("port")&&!row.get("port").isNull()){
                    if(!row.get("port").isInt())throw new IllegalArgumentException();
                    port=row.get("port").intValue();
                    if(port<1||port>65535)throw new IllegalArgumentException();
                }
                String healthPath=null;
                if(row.has("healthPath")){
                    if(port==null||!row.get("healthPath").isTextual())throw new IllegalArgumentException();
                    healthPath=ImportHttpReadiness.path(row.get("healthPath").textValue());
                }
                if(within(dir,scope))result.add(new Candidate("declared:"+title,title,dir,command,build,"DECLARED",".glyahh/run.json",port,healthPath));
            }
            return new Result(hash,result,List.of(new Scope(scope,"FIXED",result.isEmpty()?"UNIDENTIFIED":"IDENTIFIED",result.isEmpty()?"NO_ENTRY_IN_SCOPE":null)));
        }catch(Limit error){return new Result(hash,List.of(),List.of(new Scope(scope,"FIXED","INCOMPLETE","CONFIG_LIMIT")));}
        catch(Exception error){return new Result(hash,List.of(),List.of(new Scope(scope,"FIXED","FAILED","DECLARATION_INVALID")));}
    }

    private void node(Map<String,String> files,String dir,List<Candidate> candidates,List<Scope> diagnostics)throws Exception{
        String evidence=path(dir,"package.json");JsonNode root=read(files.get(evidence));
        if(!root.isObject())throw new IllegalArgumentException();
        JsonNode scripts=root.get("scripts");
        if(scripts!=null&&!scripts.isObject())throw new IllegalArgumentException();
        var locks=new HashSet<String>();
        for(var lock:Map.of("package-lock.json","npm","npm-shrinkwrap.json","npm","pnpm-lock.yaml","pnpm","yarn.lock","yarn","bun.lock","bun","bun.lockb","bun").entrySet())
            if(files.containsKey(path(dir,lock.getKey())))locks.add(lock.getValue());
        String manager=null;
        if(root.has("packageManager")){
            String declaration=text(root.get("packageManager"));
            if(!declaration.matches("(npm|pnpm|yarn|bun)@[0-9][A-Za-z0-9.+_-]{0,100}"))throw new IllegalArgumentException();
            manager=declaration.substring(0,declaration.indexOf('@'));
        }
        if(locks.size()>1||(manager!=null&&!locks.isEmpty()&&!locks.contains(manager))){diagnostics.add(new Scope(dir,"FIXED","AMBIGUOUS","PACKAGE_MANAGER_CONFLICT"));return;}
        if(manager==null&&locks.size()==1)manager=locks.iterator().next();
        if(manager==null){diagnostics.add(new Scope(dir,"FIXED","UNIDENTIFIED","PACKAGE_MANAGER_UNKNOWN"));return;}
        var entries=new ArrayList<String>();
        if(scripts!=null)for(String key:List.of("dev","start"))if(scripts.has(key)){text(scripts.get(key));entries.add(key);}
        if(entries.isEmpty()){diagnostics.add(new Scope(dir,"FIXED","UNIDENTIFIED","NO_START_SCRIPT"));return;}
        String build=scripts.has("build")?manager+" run build":null;
        if(scripts.has("build"))text(scripts.get("build"));
        for(String key:entries)candidates.add(new Candidate(evidence+":"+key,name(dir)+(entries.size()>1?" · "+key:""),dir,manager+" run "+key,build,"STATIC",evidence+"#scripts."+key));
        diagnostics.add(new Scope(dir,"FIXED",entries.size()>1?"AMBIGUOUS":"IDENTIFIED",entries.size()>1?"MULTIPLE_START_SCRIPTS":null));
    }

    private void maven(Map<String,String> files,String dir,List<Candidate> candidates,List<Scope> diagnostics)throws Exception{
        String content=bounded(files.get(path(dir,"pom.xml")));
        var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();builder.setErrorHandler(new DefaultHandler(){
            @Override public void error(SAXParseException error)throws SAXParseException{throw error;}
            @Override public void fatalError(SAXParseException error)throws SAXParseException{throw error;}
        });
        Element root=builder.parse(new InputSource(new StringReader(content))).getDocumentElement();
        if(!"project".equals(root.getLocalName()))throw new IllegalArgumentException();
        Element build=child(root,"build"),plugins=child(build,"plugins"),spring=null;
        if(plugins!=null)for(var item=plugins.getFirstChild();item!=null;item=item.getNextSibling())
            if(item instanceof Element plugin&&"plugin".equals(plugin.getLocalName())
                &&"org.springframework.boot".equals(value(child(plugin,"groupId")))
                &&"spring-boot-maven-plugin".equals(value(child(plugin,"artifactId")))){
                if(spring!=null)throw new IllegalArgumentException();spring=plugin;
            }
        if(spring==null){diagnostics.add(new Scope(dir,"ALGORITHM","UNIDENTIFIED","NO_SUPPORTED_JAVA_PLUGIN"));return;}
        String sourceRoot=path(dir,"src/main/java/");
        var sources=new ArrayList<JavaFileObject>();
        for(String file:new TreeSet<>(files.keySet()))if(file.startsWith(sourceRoot)&&file.endsWith(".java")){
            String javaContent=bounded(files.get(file));
            sources.add(new SimpleJavaFileObject(new java.net.URI("string",null,"/"+file,null),JavaFileObject.Kind.SOURCE){
                @Override public CharSequence getCharContent(boolean ignoreEncodingErrors){return javaContent;}
            });
        }
        if(sources.isEmpty()){diagnostics.add(new Scope(dir,"ALGORITHM","INCOMPLETE","JAVA_SOURCE_MISSING_OR_UNSUPPORTED"));return;}
        var compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null){diagnostics.add(new Scope(dir,"ALGORITHM","INCOMPLETE","JAVA_PARSER_UNAVAILABLE"));return;}
        var errors=new DiagnosticCollector<JavaFileObject>();var entries=new TreeSet<String>();
        // 1. 只解析语法树，不分析依赖、生成字节码或运行注解处理器。
        try(var manager=compiler.getStandardFileManager(errors,Locale.ROOT,StandardCharsets.UTF_8)){
            var task=(JavacTask)compiler.getTask(new java.io.StringWriter(),manager,errors,List.of("-proc:none"),null,sources);
            for(var unit:task.parse())for(var type:unit.getTypeDecls())if(type instanceof ClassTree clazz){
                boolean annotated=clazz.getModifiers().getAnnotations().stream().anyMatch(annotation->{
                    String name=annotation.getAnnotationType().toString();
                    return name.equals("org.springframework.boot.autoconfigure.SpringBootApplication")
                        ||(name.equals("SpringBootApplication")&&unit.getImports().stream().anyMatch(imported->!imported.isStatic()
                            &&Set.of("org.springframework.boot.autoconfigure.SpringBootApplication","org.springframework.boot.autoconfigure.*").contains(imported.getQualifiedIdentifier().toString())));
                });
                boolean main=clazz.getMembers().stream().anyMatch(member->member instanceof MethodTree method
                    &&method.getName().contentEquals("main")&&method.getModifiers().getFlags().containsAll(Set.of(Modifier.PUBLIC,Modifier.STATIC))
                    &&method.getReturnType()!=null&&method.getReturnType().toString().equals("void")&&method.getParameters().size()==1
                    &&Set.of("String[]","java.lang.String[]").contains(method.getParameters().getFirst().getType().toString()));
                if(annotated&&main)entries.add((unit.getPackageName()==null?"":unit.getPackageName()+".")+clazz.getSimpleName());
            }
        }
        if(errors.getDiagnostics().stream().anyMatch(error->error.getKind()==Diagnostic.Kind.ERROR))throw new IllegalArgumentException();
        String declared=value(child(child(spring,"configuration"),"mainClass"));
        if(declared!=null){
            if(!entries.contains(declared))throw new IllegalArgumentException();
            entries.retainAll(Set.of(declared));
        }
        // 2. 候选只表示入口推断；工具、依赖和就绪必须在执行计划中另行验证。
        String runner=files.containsKey(path(dir,"mvnw"))?"sh ./mvnw":"mvn";
        for(String entry:entries){
            if(!entry.matches("[A-Za-z_$][A-Za-z0-9_$.]*"))throw new IllegalArgumentException();
            String suffix=entries.size()>1||declared!=null?" -Dspring-boot.run.main-class="+entry:"";
            candidates.add(new Candidate(path(dir,"pom.xml")+":"+entry,name(dir)+(entries.size()>1?" · "+entry:""),dir,
                runner+" spring-boot:run"+suffix,runner+" package",declared==null?"HEURISTIC":"DECLARED",path(dir,"pom.xml")+" + "+entry));
        }
        diagnostics.add(new Scope(dir,"ALGORITHM",entries.size()>1?"AMBIGUOUS":entries.isEmpty()?"UNIDENTIFIED":"IDENTIFIED",
            entries.size()>1?"MULTIPLE_JAVA_ENTRIES":entries.isEmpty()?"NO_SUPPORTED_JAVA_ENTRY":null));
    }

    private static Element child(Element parent,String name){
        if(parent!=null)for(var node=parent.getFirstChild();node!=null;node=node.getNextSibling())
            if(node instanceof Element element&&name.equals(element.getLocalName()))return element;
        return null;
    }
    private static String value(Element node){return node==null?null:node.getTextContent().strip();}

    private JsonNode read(String content)throws Exception{return json.readTree(bounded(content));}
    private String bounded(String content){if(content.getBytes(StandardCharsets.UTF_8).length>maxConfigBytes)throw new Limit();return content;}
    private static String text(JsonNode node){if(node==null||!node.isTextual()||node.textValue().isBlank())throw new IllegalArgumentException();return node.textValue();}
    private static String directory(String dir){
        if(dir==null)throw new IllegalArgumentException("目录无效。");if(dir.equals("."))return dir;
        if(dir.isBlank()||dir.startsWith("/")||dir.endsWith("/")||dir.matches(".*[\\\\:\\x00-\\x1f].*"))throw new IllegalArgumentException("目录无效。");
        for(String part:dir.split("/",-1))if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IllegalArgumentException("目录无效。");return dir;
    }
    private static boolean exists(Map<String,String> files,String dir){return dir.equals(".")||files.keySet().stream().anyMatch(file->file.startsWith(dir+"/"));}
    private static boolean within(String dir,String scope){return scope.equals(".")||dir.equals(scope)||dir.startsWith(scope+"/");}
    private static String parent(String file){int slash=file.lastIndexOf('/');return slash<0?".":file.substring(0,slash);}
    private static String path(String dir,String file){return dir.equals(".")?file:dir+"/"+file;}
    private static String name(String dir){return dir.equals(".")?"工程":dir.substring(dir.lastIndexOf('/')+1);}
    private static boolean ignored(String path){
        if(path.startsWith(".glyahh/"))return true;
        for(String part:path.split("/"))if(IGNORED.contains(part.toLowerCase(Locale.ROOT)))return true;return false;
    }
    private static String hash(Map<String,String> files){
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            for(String path:new TreeSet<>(files.keySet()))for(byte[] value:List.of(path.getBytes(StandardCharsets.UTF_8),files.get(path).getBytes(StandardCharsets.UTF_8))){
                digest.update(ByteBuffer.allocate(4).putInt(value.length).array());digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        }catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("输入摘要不可用。");}
    }
    private static final class Limit extends RuntimeException{ }
}
