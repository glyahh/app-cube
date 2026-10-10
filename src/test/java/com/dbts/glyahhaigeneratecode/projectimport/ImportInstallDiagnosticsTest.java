package com.dbts.glyahhaigeneratecode.projectimport;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ImportInstallDiagnosticsTest {
    @Test void recordsIdentityStagesAndNoCommandOrExceptionBody() throws Exception {
        var logger=(Logger)LoggerFactory.getLogger(ImportInstallDiagnostics.class);
        var events=new ListAppender<ILoggingEvent>(); events.start(); logger.addAppender(events);
        try {
            try(var scope=ImportInstallDiagnostics.open(73)) {
                assertThrows(IOException.class,()->ImportInstallDiagnostics.call("ADMISSION",()->{throw new IOException("private-token-password-source");}));
                ImportInstallDiagnostics.execution(UUID.fromString("00000000-0000-0000-0000-000000000001"));
                ImportInstallDiagnostics.call("VERIFY",()->true);
                scope.result("FAILED","ADMISSION_FAILED");
            }
            var json=new ObjectMapper();
            boolean beforeLedger=false,afterLedger=false,notStarted=false;
            for(var event:events.list) {
                String text=event.getFormattedMessage(); assertFalse(text.contains("private-token-password-source"));
                var row=json.readTree(text.substring(text.indexOf('{')));
                for(String field:new String[]{"stage","startTime","endTime","durationMs","executionId","appId","commandType","exitCode","status","failureReason"}) assertTrue(row.has(field),field);
                assertEquals("73",row.path("appId").asText());
                if("ADMISSION".equals(row.path("stage").asText()))beforeLedger|=row.path("executionId").isNull();
                if("VERIFY".equals(row.path("stage").asText()))afterLedger|=!row.path("executionId").isNull();
                if("PIP_INSTALL".equals(row.path("stage").asText()))notStarted|="NOT_STARTED".equals(row.path("status").asText())&&row.path("durationMs").isNull();
            }
            assertTrue(beforeLedger&&afterLedger&&notStarted);
            int count=events.list.size(); ImportInstallDiagnostics.call("VERIFY",()->true); assertEquals(count,events.list.size());
        }finally{logger.detachAppender(events);events.stop();}
    }
    @Test void classifiesOnlyManagerType() {
        assertEquals("NPM_INSTALL",ImportInstallDiagnostics.commandType("npm install --no-audit --no-fund"));
        assertEquals("PIP_INSTALL",ImportInstallDiagnostics.commandType("python3 -m venv .venv && .venv/bin/pip install -r requirements.txt"));
        assertEquals("OTHER_INSTALL",ImportInstallDiagnostics.commandType("echo private-source"));
    }
}
