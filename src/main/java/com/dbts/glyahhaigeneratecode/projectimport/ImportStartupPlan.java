package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

/** 运行计划的纯计算：只读已复核识别与封存文本，生成不可变内容与 planRevision；不安装、不构建、不启动。 */
public final class ImportStartupPlan {
    public static final int SCHEMA=1;
    public record Readiness(String method,String detail,String path){
        public Readiness(String method,String detail){this(method,detail,"HTTP".equals(method)?"/":null);}
        public Readiness{if("HTTP".equals(method))path=ImportHttpReadiness.path(path==null?"/":path);}
    }
    public record Service(String id,String name,String directory,String source,String evidence,String command,List<String> expansion,List<String> lifecycle,
        String build,List<String> dependencies,Readiness readiness,List<String> unknowns,Integer port){
        public Service(String id,String name,String directory,String source,String evidence,String command,List<String> expansion,List<String> lifecycle,
            String build,List<String> dependencies,Readiness readiness,List<String> unknowns){
            this(id,name,directory,source,evidence,command,expansion,lifecycle,build,dependencies,readiness,unknowns,null);
        }
    }
    public record Step(String id,String directory,String command,List<String> expansion,List<String> lifecycle,List<String> services){ }
    public record Dependency(String id,String directory,String ecosystem,String manager,List<String> declarations,List<String> installs,String status,
        List<String> lifecycle,List<String> unknowns,List<String> services){ }
    public record Aggregate(String id,List<String> reasons,List<String> unselected){ }
    public record Available(String id,String name,String directory,String source,boolean selected){ }
    public record Content(int schema,String appId,UUID scanId,UUID importTaskId,String contentHash,String configRevision,String contextEpoch,String rulesVersion,
        List<Service> services,List<Dependency> dependencies,List<Step> builds,List<String> shared,List<Aggregate> aggregates,List<Available> available){ }
    public record Change(String path,String before,String after){ }

    private static final ObjectMapper JSON=JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
    private static final Pattern MANAGER_SCRIPT=Pattern.compile("^(npm|pnpm|yarn|bun)\\s+(?:(run|run-script)\\s+)?([A-Za-z0-9:@/_.-]+)(?:\\s.*)?$",Pattern.DOTALL);
    private static final Map<String,Pattern> AGGREGATE=new LinkedHashMap<>();
    static {
        AGGREGATE.put("MULTI_SCRIPT_RUNNER",Pattern.compile("(^|[\\s\"'(;&|])(concurrently|npm-run-all|run-p|run-s|wireit)(\\s|$)"));
        // 工作区参数只在包管理器命令内判定，避免把 node -r 预加载等误当作多包执行。
        AGGREGATE.put("WORKSPACE_RUNNER",Pattern.compile("(^|[\\s\"'(;&|])(turbo|lerna|nx)(\\s|$)|(^|[\\s\"'(;&|])(npm|pnpm|yarn|bun)\\s[^;&|\\n]*?(--workspaces?|-ws|-w|-r|--recursive|--filter|-F)(\\s|=|$)|yarn\\s+workspaces?(\\s|$)"));
        AGGREGATE.put("BACKGROUND_PROCESS",Pattern.compile("(?<![&>|])&(?![&>])"));
        AGGREGATE.put("DIRECTORY_CHANGE",Pattern.compile("(^|[\\s;&|(])(cd|pushd)\\s+|--prefix(\\s|=)|--cwd(\\s|=)|--dir(\\s|=)|(^|\\s)-C\\s"));
        AGGREGATE.put("COMPOSE",Pattern.compile("docker(-|\\s+)compose(\\s|$)|podman-compose(\\s|$)"));
    }
    private static final int MAX_EXPANSION=20,MAX_BYTES=256*1024;
    private ImportStartupPlan(){ }

    /** 由当前识别、封存解析文本和手动配置生成计划；选择无效时抛出 IllegalArgumentException，不回退到其他候选。 */
    public static Content build(ImportStartupService.Inspection inspection,Map<String,String> files,List<ImportStartupConfiguration.Manual> manual,List<String> selected){
        if(inspection==null||inspection.result()==null||files==null||manual==null||selected==null||selected.isEmpty()||selected.size()>20
            ||selected.stream().anyMatch(Objects::isNull)||new HashSet<>(selected).size()!=selected.size())throw new IllegalArgumentException("所选服务无效。");
        var candidates=new TreeMap<String,ImportStartupScanner.Candidate>();
        for(var candidate:inspection.result().candidates())if(candidates.put(candidate.id(),candidate)!=null)throw new IllegalArgumentException("候选身份重复。");
        if(!candidates.keySet().containsAll(selected))throw new IllegalArgumentException("所选服务不在当前识别结果中。");
        var chosen=new TreeSet<>(selected);
        var unselected=candidates.values().stream().filter(candidate->!chosen.contains(candidate.id())).toList();
        var services=new ArrayList<Service>();var aggregates=new TreeMap<String,Aggregate>();
        var dependencies=new TreeMap<String,Dependency>();var builds=new TreeMap<String,Step>();
        // 1. 逐个服务展开启动脚本；能静态看到的生命周期和聚合迹象全部列入计划。
        for(String id:chosen){
            var candidate=candidates.get(id);String dir=candidate.directory();
            var unknowns=new TreeSet<String>();var start=expand(files,dir,candidate.command(),unknowns);
            for(var scope:inspection.result().scopes())if(scope.directory().equals(dir)&&!"IDENTIFIED".equals(scope.status()))
                unknowns.add("SCOPE_"+scope.phase()+"_"+scope.status()+(scope.diagnostic()==null?"":"_"+scope.diagnostic()));
            if("AGENT".equals(candidate.source()))unknowns.add("AGENT_INFERRED");
            if("MANUAL".equals(candidate.source()))unknowns.add("MANUAL_COMMAND_UNVERIFIED");
            var install=id.startsWith("manual:")?manual.stream().filter(entry->id.equals("manual:"+entry.id())).map(ImportStartupConfiguration.Manual::install).filter(Objects::nonNull).findFirst().orElse(null):null;
            if(id.startsWith("manual:")&&manual.stream().noneMatch(entry->id.equals("manual:"+entry.id())))throw new IllegalArgumentException("手动服务已变化。");
            // 2. 依赖范围按目录和生态登记；状态只是未检查，不代表已就绪。
            var dependencyIds=new ArrayList<String>();
            for(String ecosystem:ecosystems(files,dir,candidate.command())){
                String key="dependency:"+ecosystem+":"+dir;dependencyIds.add(key);
                var previous=dependencies.get(key);
                dependencies.put(key,previous==null?dependency(files,key,dir,ecosystem,candidate.command(),install,id):withService(previous,install,id));
            }
            if(dependencyIds.isEmpty()&&candidate.command()!=null)unknowns.add("DEPENDENCY_SCOPE_UNKNOWN");
            // 3. 只列入候选已写明的构建；相同目录和命令合并为共享步骤。
            String buildId=null;
            if(candidate.build()!=null){
                buildId="build:"+sha(dir+"\0"+candidate.build()).substring(0,16);
                var previous=builds.get(buildId);
                if(previous==null){
                    var buildUnknowns=new TreeSet<String>();var expansion=expand(files,dir,candidate.build(),buildUnknowns);
                    builds.put(buildId,new Step(buildId,dir,candidate.build(),expansion.lines(),expansion.lifecycle(),List.of(id)));
                    aggregate(aggregates,buildId,expansion,affected(unselected,dir,expansion));
                }else builds.put(buildId,new Step(previous.id(),previous.directory(),previous.command(),previous.expansion(),previous.lifecycle(),sorted(previous.services(),id)));
            }
            Integer port=candidate.port();
            if(id.startsWith("manual:"))port=manual.stream().filter(entry->id.equals("manual:"+entry.id())).map(ImportStartupConfiguration.Manual::port).filter(Objects::nonNull).findFirst().orElse(port);
            Readiness readiness;
            if(candidate.command()==null)readiness=new Readiness("STATIC_ENTRY",candidate.evidence());
            else if(port!=null){
                String healthPath=candidate.healthPath()==null?"/":candidate.healthPath();
                readiness=new Readiness("HTTP","端口 "+port+" · "+healthPath,healthPath);
            }
            else{readiness=new Readiness("PROCESS_ALIVE","未声明端口或健康检查；进程存活不代表业务就绪。");unknowns.add("READINESS_PORT_UNDECLARED");}
            services.add(new Service(id,candidate.name(),dir,candidate.source(),candidate.evidence(),candidate.command(),start.lines(),start.lifecycle(),buildId,List.copyOf(dependencyIds),readiness,List.copyOf(unknowns),port));
            aggregate(aggregates,"service:"+id,start,affected(unselected,dir,start));
        }
        var shared=new TreeSet<String>();
        dependencies.values().forEach(row->{if(row.services().size()>1)shared.add(row.id());});
        builds.values().forEach(row->{if(row.services().size()>1)shared.add(row.id());});
        var available=candidates.values().stream().map(row->new Available(row.id(),row.name(),row.directory(),row.source(),chosen.contains(row.id()))).toList();
        var content=new Content(SCHEMA,inspection.appId(),inspection.scanId(),inspection.importTaskId(),inspection.contentHash(),inspection.configRevision(),inspection.contextEpoch(),
            ImportStartupInspectionRepository.RULES_VERSION,List.copyOf(services),List.copyOf(dependencies.values()),List.copyOf(builds.values()),List.copyOf(shared),List.copyOf(aggregates.values()),available);
        if(encode(content).length>MAX_BYTES)throw new IllegalArgumentException("计划内容超限。");
        return content;
    }

    /** 计划内容的规范 JSON 摘要；内容、身份、选择或权限任一变化都会改变版本。 */
    public static String revision(Content content){return sha(new String(encode(content),StandardCharsets.UTF_8));}
    public static String json(Content content){return new String(encode(content),StandardCharsets.UTF_8);}
    public static Content parse(String value){
        try{var content=JSON.readValue(value,Content.class);if(content==null||content.schema()!=SCHEMA)throw new IllegalArgumentException("计划格式无效。");return content;}
        catch(java.io.IOException error){throw new IllegalArgumentException("计划格式无效。");}
    }
    /** 逐字段比较两份计划；列表按 id 对齐，最多返回 200 项。 */
    public static List<Change> diff(Content before,Content after){
        var changes=new ArrayList<Change>();
        compare("",before==null?null:JSON.valueToTree(before),after==null?null:JSON.valueToTree(after),changes);
        return List.copyOf(changes);
    }

    private record Expansion(String command,List<String> lines,List<String> lifecycle,Set<String> reasons,Set<String> scripts,List<String> unknowns){ }
    private static Expansion expand(Map<String,String> files,String dir,String command,Set<String> unknowns){
        var lines=new ArrayList<String>();var lifecycle=new TreeSet<String>();var reasons=new TreeSet<String>();var visited=new LinkedHashSet<String>();var local=new TreeSet<String>();
        if(command==null)return new Expansion(null,List.of(),List.of(),reasons,visited,List.of());
        scan(command,reasons);
        var matcher=MANAGER_SCRIPT.matcher(command.strip());
        JsonNode scripts=null;
        if(files.containsKey(path(dir,"package.json"))){
            try{var root=JSON.readTree(files.get(path(dir,"package.json")));scripts=root!=null&&root.get("scripts")!=null&&root.get("scripts").isObject()?root.get("scripts"):null;}
            catch(java.io.IOException error){local.add("PACKAGE_JSON_UNREADABLE");}
        }
        if(matcher.matches()){
            String manager=matcher.group(1),name=matcher.group(3);boolean explicit=matcher.group(2)!=null;
            boolean script=scripts!=null&&scripts.has(name)&&(explicit||!manager.equals("npm")||Set.of("start","test","stop","restart").contains(name));
            if(script){
                // 1. 广度展开被引用的脚本及 pre/post 生命周期，超限时记为未知并要求重新确认。
                var queue=new ArrayDeque<String>(List.of(name));
                while(!queue.isEmpty()){
                    String current=queue.poll();if(!visited.add(current))continue;
                    if(lines.size()>=MAX_EXPANSION){reasons.add("EXPANSION_LIMIT");local.add("SCRIPT_EXPANSION_LIMIT");break;}
                    String body=scripts.get(current).isTextual()?scripts.get(current).textValue():null;
                    if(body==null||body.length()>1024){local.add("SCRIPT_UNREADABLE");reasons.add("EXPANSION_LIMIT");continue;}
                    lines.add("scripts."+current+": "+body);scan(body,reasons);
                    for(String hook:List.of("pre"+current,"post"+current))if(scripts.has(hook)){lifecycle.add("scripts."+hook);queue.add(hook);}
                    queue.addAll(references(body,scripts));
                }
            }else if(explicit||!manager.equals("npm")||Set.of("start","test","stop","restart").contains(name))local.add("SCRIPT_NOT_FOUND");
        }
        // 2. Maven 聚合工程会处理全部模块，构建或运行范围不等于单一服务。
        var modules=modules(files,dir,local);
        if(!modules.isEmpty()&&command.matches("^(sh\\s+)?(\\./)?(mvn|mvnw)(\\.cmd)?(\\s.*)?$")){reasons.add("MAVEN_REACTOR");lines.add("modules: "+String.join(", ",modules));}
        unknowns.addAll(local);
        return new Expansion(command,List.copyOf(lines),List.copyOf(lifecycle),reasons,visited,List.copyOf(local));
    }
    /** 有聚合迹象或会带上未选候选时登记，确认前必须展开并由用户重新确认。 */
    private static void aggregate(Map<String,Aggregate> aggregates,String id,Expansion expansion,List<String> unselected){
        var reasons=new TreeSet<>(expansion.reasons());if(!unselected.isEmpty())reasons.add("REFERENCES_UNSELECTED");
        if(!reasons.isEmpty())aggregates.put(id,new Aggregate(id,List.copyOf(reasons),unselected));
    }
    private static void scan(String body,Set<String> reasons){AGGREGATE.forEach((reason,pattern)->{if(pattern.matcher(body).find())reasons.add(reason);});}
    private static List<String> references(String body,JsonNode scripts){
        var result=new ArrayList<String>();var tokens=body.replace("\"","").replace("'","").split("\\s+");boolean runner=false;
        for(int index=0;index<tokens.length;index++){
            String token=tokens[index];
            if(Set.of("&&","||",";","|","&").contains(token)){runner=false;continue;}
            if(Set.of("npm-run-all","run-p","run-s").contains(token)){runner=true;continue;}
            String previous=index>0?tokens[index-1]:"";
            String name=token.startsWith("npm:")?token.substring(4):token;
            boolean candidate=token.startsWith("npm:")||runner&&!token.startsWith("-")||Set.of("run","run-script","pnpm","yarn","bun").contains(previous)
                ||previous.equals("npm")&&Set.of("start","test","stop","restart").contains(token);
            if(!candidate)continue;
            if(name.contains("*")){var glob=Pattern.compile(Pattern.quote(name).replace("*","\\E[^:]*\\Q"));scripts.fieldNames().forEachRemaining(key->{if(glob.matcher(key).matches())result.add(key);});}
            else if(scripts.has(name))result.add(name);
        }
        return result;
    }
    /** 可能被一起启动或构建的未选候选：同包被引用的脚本、聚合工程内的子目录、展开文本里出现的目录。 */
    private static List<String> affected(List<ImportStartupScanner.Candidate> unselected,String dir,Expansion expansion){
        var result=new TreeSet<String>();String text=expansion.command()+"\n"+String.join("\n",expansion.lines());
        boolean nested=expansion.reasons().contains("WORKSPACE_RUNNER")||expansion.reasons().contains("MAVEN_REACTOR")||expansion.reasons().contains("COMPOSE");
        for(var other:unselected){
            String key=other.id().substring(other.id().lastIndexOf(':')+1);
            boolean sameScript=other.directory().equals(dir)&&other.id().equals(path(dir,"package.json")+":"+key)&&expansion.scripts().contains(key);
            boolean inside=!other.directory().equals(dir)&&(dir.equals(".")||other.directory().startsWith(dir+"/"));
            String last=other.directory().substring(other.directory().lastIndexOf('/')+1);
            boolean named=!other.directory().equals(dir)&&!other.directory().equals(".")&&Pattern.compile("(^|[\\s\"'=/(])"+Pattern.quote(last)+"([\\s\"'/;&|)]|$)").matcher(text).find();
            if(sameScript||nested&&inside||named)result.add(other.id());
        }
        return List.copyOf(result);
    }
    private static List<String> ecosystems(Map<String,String> files,String dir,String command){
        if(command==null)return List.of();
        String first=command.strip().split("\\s+")[0];
        String declared=Set.of("npm","pnpm","yarn","bun","node","npx").contains(first)?"node"
            :command.strip().matches("^(sh\\s+)?(\\./)?(mvn|mvnw)(\\.cmd)?(\\s.*)?$")?"maven"
            :Set.of("python","python3","py","uvicorn","gunicorn","flask","streamlit").contains(first)?"python":null;
        var present=new ArrayList<String>();
        if(files.containsKey(path(dir,"package.json")))present.add("node");
        if(files.containsKey(path(dir,"pom.xml")))present.add("maven");
        if(files.containsKey(path(dir,"pyproject.toml"))||files.containsKey(path(dir,"requirements.txt")))present.add("python");
        if(declared!=null)return present.contains(declared)?List.of(declared):List.of();
        return List.copyOf(present);
    }
    private static Dependency dependency(Map<String,String> files,String id,String dir,String ecosystem,String command,String install,String service){
        var declarations=new TreeSet<String>();var lifecycle=new TreeSet<String>();var unknowns=new TreeSet<String>();String manager=null;
        switch(ecosystem){
            case "node" -> {
                declarations.add(path(dir,"package.json"));
                for(String lock:List.of("package-lock.json","npm-shrinkwrap.json","pnpm-lock.yaml","yarn.lock","bun.lock"))if(files.containsKey(path(dir,lock)))declarations.add(path(dir,lock));
                String first=command.strip().split("\\s+")[0];
                if(Set.of("npm","pnpm","yarn","bun").contains(first))manager=first;
                try{
                    var root=JSON.readTree(files.get(path(dir,"package.json")));
                    if(manager==null&&root.hasNonNull("packageManager")&&root.get("packageManager").isTextual())manager=root.get("packageManager").textValue().split("@")[0];
                    var scripts=root.get("scripts");
                    if(scripts!=null&&scripts.isObject())for(String hook:List.of("preinstall","install","postinstall","prepare"))if(scripts.has(hook))lifecycle.add("scripts."+hook);
                    if(root.has("workspaces"))unknowns.add("NODE_WORKSPACES");
                }catch(java.io.IOException|RuntimeException error){unknowns.add("PACKAGE_JSON_UNREADABLE");}
                if(manager==null)unknowns.add("PACKAGE_MANAGER_UNKNOWN");
                unknowns.add("INSTALL_LIFECYCLE_BY_TOOL_VERSION");
            }
            case "maven" -> {
                declarations.add(path(dir,"pom.xml"));manager=files.containsKey(path(dir,"mvnw"))?"mvnw":"mvn";
                if(files.containsKey(path(dir,"mvnw")))declarations.add(path(dir,"mvnw"));
                String parent=parent(files,dir,unknowns);if(parent!=null)declarations.add(parent);
                unknowns.add("MAVEN_PLUGINS_UNRESOLVED");
            }
            default -> {
                for(String file:List.of("pyproject.toml","requirements.txt"))if(files.containsKey(path(dir,file)))declarations.add(path(dir,file));
                unknowns.add("PYTHON_ENVIRONMENT_UNCHECKED");
            }
        }
        return new Dependency(id,dir,ecosystem,manager,List.copyOf(declarations),install==null?List.of():List.of(install),"UNKNOWN",List.copyOf(lifecycle),List.copyOf(unknowns),List.of(service));
    }
    private static Dependency withService(Dependency previous,String install,String service){
        var installs=new TreeSet<>(previous.installs());if(install!=null)installs.add(install);
        return new Dependency(previous.id(),previous.directory(),previous.ecosystem(),previous.manager(),previous.declarations(),List.copyOf(installs),previous.status(),
            previous.lifecycle(),previous.unknowns(),sorted(previous.services(),service));
    }
    private static List<String> modules(Map<String,String> files,String dir,Set<String> unknowns){
        Element root=pom(files,dir,unknowns);if(root==null)return List.of();
        var result=new ArrayList<String>();Element modules=child(root,"modules");
        if(modules!=null)for(var node=modules.getFirstChild();node!=null;node=node.getNextSibling())
            if(node instanceof Element module&&"module".equals(module.getLocalName()))result.add(module.getTextContent().strip());
        return result;
    }
    private static String parent(Map<String,String> files,String dir,Set<String> unknowns){
        Element root=pom(files,dir,unknowns);Element parent=root==null?null:child(root,"parent");if(parent==null)return null;
        Element relative=child(parent,"relativePath");String value=relative==null?"../pom.xml":relative.getTextContent().strip();
        if(value.isEmpty())return null;
        if(!value.endsWith(".xml"))value=value.endsWith("/")?value+"pom.xml":value+"/pom.xml";
        var parts=new ArrayDeque<String>();if(!dir.equals("."))parts.addAll(List.of(dir.split("/")));
        for(String part:value.split("/")){
            if(part.equals("..")){if(parts.isEmpty()){unknowns.add("MAVEN_PARENT_OUTSIDE_WORKSPACE");return null;}parts.removeLast();}
            else if(!part.isEmpty()&&!part.equals("."))parts.addLast(part);
        }
        String resolved=String.join("/",parts);
        if(files.containsKey(resolved))return resolved;
        unknowns.add("MAVEN_PARENT_NOT_IN_WORKSPACE");return null;
    }
    private static Element pom(Map<String,String> files,String dir,Set<String> unknowns){
        String content=files.get(path(dir,"pom.xml"));if(content==null)return null;
        try{
            var factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
            var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
            var root=builder.parse(new InputSource(new StringReader(content))).getDocumentElement();
            return "project".equals(root.getLocalName())?root:null;
        }catch(Exception error){unknowns.add("POM_UNREADABLE");return null;}
    }
    private static Element child(Element parent,String name){
        if(parent!=null)for(var node=parent.getFirstChild();node!=null;node=node.getNextSibling())
            if(node instanceof Element element&&name.equals(element.getLocalName()))return element;
        return null;
    }
    private static void compare(String path,JsonNode before,JsonNode after,List<Change> changes){
        if(changes.size()>=200||Objects.equals(before,after))return;
        if(before!=null&&after!=null&&before.isObject()&&after.isObject()){
            var names=new TreeSet<String>();before.fieldNames().forEachRemaining(names::add);after.fieldNames().forEachRemaining(names::add);
            for(String name:names)compare(path.isEmpty()?name:path+"."+name,before.get(name),after.get(name),changes);
            return;
        }
        if(before!=null&&after!=null&&keyed(before)&&keyed(after)){
            var left=new LinkedHashMap<String,JsonNode>();before.forEach(row->left.put(row.get("id").asText(),row));
            var right=new LinkedHashMap<String,JsonNode>();after.forEach(row->right.put(row.get("id").asText(),row));
            var ids=new LinkedHashSet<>(left.keySet());ids.addAll(right.keySet());
            for(String id:ids){
                if(!left.containsKey(id)||!right.containsKey(id)){if(changes.size()<200)changes.add(new Change(path+"["+id+"]",text(left.get(id)),text(right.get(id))));}
                else compare(path+"["+id+"]",left.get(id),right.get(id),changes);
            }
            return;
        }
        changes.add(new Change(path,text(before),text(after)));
    }
    private static boolean keyed(JsonNode node){
        if(!node.isArray())return false;
        for(var row:node)if(!row.isObject()||!row.hasNonNull("id"))return false;
        return true;
    }
    private static String text(JsonNode node){return node==null||node.isNull()?null:node.isTextual()?node.textValue():node.toString();}
    private static List<String> sorted(List<String> values,String extra){var result=new TreeSet<>(values);result.add(extra);return List.copyOf(result);}
    private static String path(String dir,String file){return dir.equals(".")?file:dir+"/"+file;}
    private static byte[] encode(Content content){
        try{return JSON.writeValueAsBytes(content);}catch(java.io.IOException error){throw new IllegalArgumentException("计划内容无效。");}
    }
    private static String sha(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException("计划摘要不可用。");}
    }
}
