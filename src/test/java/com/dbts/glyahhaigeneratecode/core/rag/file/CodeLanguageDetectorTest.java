package com.dbts.glyahhaigeneratecode.core.rag.file;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeLanguageDetectorTest {

    private final CodeLanguageDetector detector = new CodeLanguageDetector();

    @ParameterizedTest
    @CsvSource({
            "src/App.JAVA, java",
            "App.vue, vue",
            "index.tsx, typescript",
            "index.jsx, javascript",
            "index.htm, html",
            "theme.scss, css",
            "data.json, json",
            "pom.xml, xml",
            "README.md, markdown",
            "schema.sql, sql",
            "application.yaml, yaml",
            "application.properties, properties"
    })
    void detect_preservesExistingExtensionMapping(String path, String expected) {
        assertEquals(expected, detector.detect(path));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"README", "file.unknown"})
    void detect_returnsTextForUnknownOrNullPath(String path) {
        assertEquals("text", detector.detect(path));
    }
}
