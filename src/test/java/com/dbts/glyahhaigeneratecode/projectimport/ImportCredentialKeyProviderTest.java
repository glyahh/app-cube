package com.dbts.glyahhaigeneratecode.projectimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ImportCredentialKeyProviderTest {
    @TempDir Path directory;
    private ImportCredentialKeyProvider provider(Path file) {
        var properties = new ImportProperties(); properties.setCredentialKeyRingFile(file);
        return new ImportCredentialKeyProvider(properties,new ObjectMapper());
    }
    @Test void missingOrUnknownKeyCannotUseDefault() throws Exception {
        assertThrows(RuntimeException.class,()->provider(null).cipher());
        Path file=directory.resolve("keys.json"); Files.writeString(file,"{\"activeKeyId\":\"absent\",\"keys\":{}}");
        assertThrows(RuntimeException.class,()->provider(file).cipher());
    }
    @Test void validExternalKeyFileSupportsCipherWithoutReturningKeyMaterial() throws Exception {
        Path file=directory.resolve("keys.json");
        Files.writeString(file,"{\"activeKeyId\":\"v1\",\"keys\":{\"v1\":\""+java.util.Base64.getEncoder().encodeToString(new byte[32])+"\"}}");
        var cipher=provider(file).cipher(); var id=java.util.UUID.randomUUID();
        var secret=cipher.encrypt(12,"GitHub",id,1,"FAKE_TEST_VALUE");
        assertEquals("FAKE_TEST_VALUE",cipher.decrypt(12,"GitHub",id,1,secret));
    }
    @Test void repositoryLocationAndOversizedOrMalformedFileFailClosed() throws Exception {
        assertThrows(RuntimeException.class,()->provider(Path.of("pom.xml")).cipher());
        Path file=directory.resolve("keys.json"); Files.writeString(file,"x".repeat(16385));
        assertThrows(RuntimeException.class,()->provider(file).cipher());
        Files.writeString(file,"not-json"); assertThrows(RuntimeException.class,()->provider(file).cipher());
    }
}
