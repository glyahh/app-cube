package com.dbts.glyahhaigeneratecode.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectImportManifestTest {
    private final Limits limits = new Limits(100, 1_000);

    private Manifest inspect(List<FileEntry> files, Options options) {
        return validate(files, options, limits);
    }

    @Test
    void acceptsNestedChineseSpacesAndZeroBytes() {
        var files = List.of(new FileEntry("工程/src/首页 文件.html", 7), new FileEntry("工程/empty", 0));
        var result = inspect(files, Options.defaults());
        assertEquals(files, result.included());
        assertEquals(7, result.totalBytes());
        assertTrue(result.excluded().isEmpty());
    }

    @Test
    void retainsDependenciesAndBuildOutputsByDefault() {
        var files = List.of(new FileEntry("node_modules/a/index.js", 4), new FileEntry(".venv/a", 3),
                new FileEntry("venv/b", 2), new FileEntry("dist/a", 1), new FileEntry("target/a", 1), new FileEntry("build/a", 1));
        assertEquals(files, inspect(files, Options.defaults()).included());
    }

    @Test
    void appliesEveryFilterCombinationWithoutChangingInput() {
        var files = List.of(new FileEntry("src/a", 1), new FileEntry("sub/node_modules/b", 2),
                new FileEntry("sub/dist/c", 3), new FileEntry(".env", 4));
        for (boolean dependencies : List.of(false, true)) {
            for (boolean builds : List.of(false, true)) {
                for (boolean sensitive : List.of(false, true)) {
                    var result = inspect(files, new Options(dependencies, builds, sensitive));
                    assertEquals(1 + (dependencies ? 0 : 1) + (builds ? 0 : 1) + (sensitive ? 1 : 0), result.included().size());
                    assertEquals(1 + (dependencies ? 0 : 2) + (builds ? 0 : 3) + (sensitive ? 4 : 0), result.totalBytes());
                    assertEquals(4, result.included().size() + result.excluded().size());
                }
            }
        }
        assertEquals(4, files.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {".env", ".ENV.production", "application-local.yml", "application-local-test.yaml",
            "application-local.properties", "a.pem", "a.KEY", "a.p12", "a.pfx", "a.jks", "a.keystore",
            "id_rsa", "id_ed25519", "credentials", "credentials.json", ".npmrc", ".pypirc", ".netrc"})
    void excludesSensitiveNames(String path) {
        var result = inspect(List.of(new FileEntry("sub/" + path, 1), new FileEntry("src/main", 1)), Options.defaults());
        assertEquals(List.of(new FileEntry("src/main", 1)), result.included());
        assertEquals(ExclusionReason.SENSITIVE, result.excluded().getFirst().reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {".env.example", ".env.local.sample", ".ENV.template", "application.yml", "src/node_modules.txt", "dist.txt"})
    void preservesExamplesAndNonMatchingNames(String path) {
        var file = new FileEntry(path, 1);
        assertEquals(List.of(file), inspect(List.of(file), new Options(true, true, false)).included());
    }

    @Test
    void sensitiveReasonTakesPrecedenceOverDirectoryFilters() {
        var result = inspect(List.of(new FileEntry("node_modules/.env", 1), new FileEntry("target/x", 2),
                new FileEntry(".venv/x", 3), new FileEntry("src/main", 1)), new Options(true, true, false));
        assertEquals(List.of(ExclusionReason.SENSITIVE, ExclusionReason.BUILD_OUTPUT, ExclusionReason.DEPENDENCY),
                result.excluded().stream().map(ExcludedFile::reason).toList());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"/etc/passwd", "C:/test", "../a", "a/../b", "a/./b", "a//b", "a/", "a\\b",
            "a\u0000b", "a\u007fb", "a:b", "a?b", "a*b", "a|b", "a<b", "a>b", "a\"b", "a. ", "a.",
            "CON", "con.txt", "a/NUL", "COM1.log", "LPT9", "a/AUX", "PRN.txt"})
    void rejectsUnsafePaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry(path, 1)), Options.defaults()));
    }

    @Test
    void rejectsDuplicatesCaseAliasesAndFileDirectoryCollisionsInEitherOrder() {
        for (var paths : List.of(List.of("a", "a"), List.of("src/A", "src/a"),
                List.of("Src/a", "src/b"), List.of("a", "a/b"), List.of("a/b", "a"))) {
            assertThrows(IllegalArgumentException.class, () -> inspect(paths.stream().map(p -> new FileEntry(p, 1)).toList(), Options.defaults()));
        }
    }

    @Test
    void validatesExcludedFilesTooAndDoesNotSilentlyTruncate() {
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("src/a", 1),
                new FileEntry("node_modules/../a", 1)), new Options(true, false, false)));
        assertThrows(IllegalArgumentException.class, () -> validate(List.of(new FileEntry("a", 1), new FileEntry("b", 1)),
                Options.defaults(), new Limits(1, 10)));
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a", 1), new FileEntry(".env", 1_000)), Options.defaults()));
    }

    @Test
    void rejectsEmptyRangeNullInputInvalidSizesAndInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry(".env", 1)), Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(), Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(null, Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(java.util.Arrays.asList((FileEntry) null), Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a", -1)), Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a", 1)), null));
        assertThrows(IllegalArgumentException.class, () -> validate(List.of(new FileEntry("a", 1)), Options.defaults(), null));
        assertThrows(IllegalArgumentException.class, () -> new Limits(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Limits(1, -1));
    }

    @Test
    void acceptsExactQuotaAndRejectsLongOverflow() {
        assertEquals(10, validate(List.of(new FileEntry("a", 10)), Options.defaults(), new Limits(1, 10)).totalBytes());
        assertEquals(0, validate(List.of(new FileEntry("a", 0)), Options.defaults(), new Limits(1, 0)).totalBytes());
        assertThrows(IllegalArgumentException.class, () -> validate(List.of(new FileEntry("a", Long.MAX_VALUE),
                new FileEntry("b", 1)), Options.defaults(), new Limits(2, Long.MAX_VALUE)));
    }

    @Test
    void boundsPathLengthAndComponentLength() {
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a".repeat(256), 1)), Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a/".repeat(2048) + "b", 1)), Options.defaults()));
        assertEquals(1, inspect(List.of(new FileEntry("a".repeat(255), 1)), Options.defaults()).included().size());
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a\uD800", 1)), Options.defaults()));
        assertThrows(IllegalArgumentException.class, () -> inspect(List.of(new FileEntry("a\uDC00", 1)), Options.defaults()));
        assertEquals(1, inspect(List.of(new FileEntry("a\uD83D\uDE00", 1)), Options.defaults()).included().size());
    }

    @Test
    void outputIsImmutableAndDetachedFromCallerList() {
        var files = new ArrayList<>(List.of(new FileEntry("a", 1), new FileEntry(".env", 2)));
        var result = inspect(files, Options.defaults());
        files.clear();
        assertEquals(1, result.included().size());
        assertThrows(UnsupportedOperationException.class, () -> result.included().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.excluded().clear());
    }
}
