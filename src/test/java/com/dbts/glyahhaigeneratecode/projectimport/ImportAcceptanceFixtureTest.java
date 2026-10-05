package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 固定样本只证明识别结果和写明的端口，不证明这些工程已经在容器里跑起来。 */
class ImportAcceptanceFixtureTest {
    private final ImportStartupScanner scanner=new ImportStartupScanner();

    @Test void vueSpringPythonStaticAndDualAreRecognizedSeparately()throws IOException{
        var vue=scan("vue");
        assertTrue(vue.candidates().stream().anyMatch(row->"npm run dev".equals(row.command())&&row.port()==null));
        var spring=scan("spring");
        assertTrue(spring.candidates().stream().anyMatch(row->row.command().contains("spring-boot:run")&&row.port()==null));
        var python=scan("python-web");
        assertEquals(8080,python.candidates().getFirst().port());
        assertEquals("python3 app.py",python.candidates().getFirst().command());
        var page=scan("static-page");
        assertNull(page.candidates().getFirst().command());
        assertEquals("STATIC",page.candidates().getFirst().source());
        var dual=scan("dual");
        assertEquals(2,dual.candidates().size());
        assertTrue(dual.candidates().stream().anyMatch(row->"web".equals(row.directory())&&Integer.valueOf(4173).equals(row.port())));
        assertTrue(dual.candidates().stream().anyMatch(row->"api".equals(row.directory())&&Integer.valueOf(8080).equals(row.port())));
        assertEquals("print(\"pang\")\n",Files.readString(Path.of("src/test/resources/import-acceptance/defect/answer.py")));
    }

    private ImportStartupScanner.Result scan(String name)throws IOException{
        Path root=Path.of("src/test/resources/import-acceptance",name);
        var files=new HashMap<String,String>();
        try(var walk=Files.walk(root)){
            walk.filter(Files::isRegularFile).forEach(file->{
                try{files.put(root.relativize(file).toString().replace('\\','/'),Files.readString(file));}
                catch(IOException error){throw new IllegalStateException(error);}
            });
        }
        return scanner.scan(files,".");
    }
}
