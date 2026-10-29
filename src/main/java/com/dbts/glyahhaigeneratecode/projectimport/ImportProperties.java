package com.dbts.glyahhaigeneratecode.projectimport;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/** 导入额度越限直接失败，不自动排除依赖或截断清单。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "project-import")
public class ImportProperties {
    @org.springframework.context.annotation.Bean
    public ImportDiskVolume importDiskVolume(com.dbts.glyahhaigeneratecode.runtime.DockerCli docker){
        return new ImportDiskVolume(ImportExecutionSandbox.docker(docker),Path.of("scripts/import_disk_volume.py"));
    }
    private Path stagingRoot = Path.of("temp/project_import_private");
    private Path credentialKeyRingFile;
    private Path gitExecutable;
    private Path credentialCurl = System.getenv("SystemRoot")==null?Path.of("/usr/bin/curl"):Path.of(System.getenv("SystemRoot"),"System32","curl.exe");
    private Path credentialPython = Path.of("agent", ".venv",System.getProperty("os.name").toLowerCase().contains("win")?"Scripts":"bin",System.getProperty("os.name").toLowerCase().contains("win")?"python.exe":"python");
    private Path credentialHelper = Path.of("scripts","import-git-askpass.py");
    private String gitProxy;
    private long maxGitBytes = 256L * 1024 * 1024;
    private Duration sourceTimeout = Duration.ofMinutes(15);
    private int maxFiles = 10_000;
    private long maxSourceBytes = 1024L * 1024 * 1024;
    /** 每个工作区保留的合入快照条数；超出的已收口快照按时间清理。 */
    private int snapshotRetain = 20;
    private long maxMetadataBytes = 8L * 1024 * 1024;
    private int maxPendingPerUser = 2;
    private int maxPendingGlobal = 20;
    private long minFreeBytes = 256L * 1024 * 1024;
    private Duration uploadTimeout = Duration.ofMinutes(5);
    private Duration stopTimeout = Duration.ofSeconds(5);
    private Set<String> allowedOrigins = Set.of("http://localhost:5173", "http://127.0.0.1:5173");

    /** venv 在 Linux 常用符号链接指向真实解释器；配置边界先解析，避免被受管路径守卫误判。 */
    public Path getCredentialPython() {
        Path configured = credentialPython.toAbsolutePath().normalize();
        if (!Files.isRegularFile(configured)) return configured;
        try {
            return configured.toRealPath();
        } catch (IOException error) {
            return configured;
        }
    }
}
