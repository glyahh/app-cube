"""专属真实框架工程，固定版本；不是平台业务路径或生产样例。"""
import json
from pathlib import Path

VUE = {
    # 固定锁文件和实际 Vue/Vite，浏览器只访问批准构建后的 dist。
    "package.json": json.dumps({"name": "import-vue-fixture", "version": "1.0.0", "private": True, "type": "module",
        "dependencies": {"vue": "3.5.27"}, "devDependencies": {"vite": "7.3.1"},
        "scripts": {"start": "python3 -m http.server 8082 --bind 0.0.0.0 --directory dist", "build": "vite build"}}).encode(),
    "package-lock.json": (Path(__file__).with_name("fixtures") / "import-vue-package-lock.json").read_bytes(),
    "index.html": '<meta charset="utf-8"><div id="app"></div><script type="module" src="/main.js"></script>\n'.encode(),
    "main.js": r"""import {createApp, h, ref, version} from 'vue';
createApp({setup() {
  const api = ref(''), value = ref(''), result = ref('');
  async function save() {
    try {
      const response = await fetch(api.value.replace(/\/$/, '') + '/records', {
        method: 'POST', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({value: value.value})
      });
      const data = await response.json();
      result.value = response.ok ? JSON.stringify(data) : '输入不能为空';
    } catch { result.value = '连接失败'; }
  }
  return () => h('main', {'data-vue-version': version}, [
    h('h1', '记录'),
    h('input', {id:'api', placeholder:'接口地址', value:api.value, onInput:event => api.value=event.target.value}),
    h('input', {id:'value', placeholder:'内容', value:value.value, onInput:event => value.value=event.target.value}),
    h('button', {id:'save', onClick:save}, '保存'),
    h('output', {id:'result'}, result.value)
  ]);
}}).mount('#app');
""".encode(),
}

FLASK_BUSINESS = {
    "requirements.txt": b"Flask==3.1.2\n",
    "app.py": """from flask import Flask, jsonify, request
from pathlib import Path
import json
app=Flask(__name__)
path=Path('records.json')
def values():
    return json.loads(path.read_text()) if path.exists() else []
@app.after_request
def cors(response):
    response.headers['Access-Control-Allow-Origin']='*'
    response.headers['Access-Control-Allow-Headers']='Content-Type'
    response.headers['Access-Control-Allow-Methods']='GET,POST,OPTIONS'
    return response
@app.get('/')
def health():
    return jsonify(kind='flask', ok=True)
@app.route('/records',methods=['GET','POST','OPTIONS'])
def records():
    if request.method=='OPTIONS': return ('',204)
    data=values()
    if request.method=='POST':
        value=(request.get_json() or {}).get('value','')
        if not isinstance(value,str) or not value.strip(): return (jsonify(error='empty'),400)
        data.append(value)
        path.write_text(json.dumps(data))
    return jsonify(values=data)
app.run(host='0.0.0.0',port=8081)
""".encode(),
}

SPRING_BOOT = {
    "pom.xml": b'''<project xmlns="http://maven.apache.org/POM/4.0.0">
<modelVersion>4.0.0</modelVersion>
<parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.5.10</version></parent>
<groupId>fixture</groupId><artifactId>import-fixture</artifactId><version>1.0.0</version>
<properties><java.version>21</java.version></properties>
<dependencies><dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency></dependencies>
<build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
</project>\n''',
    "src/main/resources/application.properties": b"server.port=8084\nserver.address=0.0.0.0\n",
    "src/main/java/fixture/Application.java": b'''package fixture;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.nio.file.*;
import java.util.*;
@SpringBootApplication
@RestController
@CrossOrigin(origins="*")
public class Application {
  public static void main(String[] args){SpringApplication.run(Application.class,args);}
  private final Path file=Path.of("records.txt");
  @GetMapping("/") public Map<String,Object> health(){return Map.of("kind","springboot","ok",true);}
  @GetMapping("/records") public synchronized Map<String,Object> read() throws Exception {
    return Map.of("values",Files.exists(file)?Files.readAllLines(file):List.of());
  }
  @PostMapping("/records") public synchronized ResponseEntity<?> save(@RequestBody Map<String,String> input) throws Exception {
    String value=input.get("value");
    if(value==null || value.isBlank())return ResponseEntity.badRequest().body(Map.of("error","empty"));
    var values=new ArrayList<String>(Files.exists(file)?Files.readAllLines(file):List.of());
    values.add(value);Files.write(file,values);return ResponseEntity.ok(Map.of("values",values));
  }
}\n''',
}

VUE_SERVICE = {"id": None, "name": "Vue页面", "directory": ".", "command": "npm run start",
               "install": "npm ci --no-audit --no-fund --registry=https://registry.npmjs.org", "build": "npm run build", "port": 8082}
FLASK_SERVICE = {"id": None, "name": "Python接口", "directory": ".", "command": ".venv/bin/python app.py",
                 "install": "python3 -m venv .venv && .venv/bin/pip install --no-cache-dir -r requirements.txt", "build": None, "port": 8081}
SPRING_SERVICE = {"id": None, "name": "Java接口", "directory": ".", "command": "java -jar target/import-fixture-1.0.0.jar",
                  "install": "mvn -B -Dmaven.repo.local=.m2/repository dependency:go-offline",
                  "build": "mvn -B -Dmaven.repo.local=.m2/repository package", "port": 8084}
