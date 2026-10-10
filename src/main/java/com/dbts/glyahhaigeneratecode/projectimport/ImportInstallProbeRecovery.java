package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.runtime.DockerCli;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/** 只按安装任务登记的探针身份核对资源；不扫描或删除其他任务。 */
@Component
public class ImportInstallProbeRecovery {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ImportExecutionSandbox.Engine engine;

    @org.springframework.beans.factory.annotation.Autowired
    public ImportInstallProbeRecovery(DockerCli docker) {
        this(ImportExecutionSandbox.docker(docker));
    }

    ImportInstallProbeRecovery(ImportExecutionSandbox.Engine engine) {
        this.engine = engine;
    }

    public boolean confirmed(ImportStartupInstallJob job, boolean remove) {
        for (var probe : job.probes()) {
            if (!confirmProbe(job, probe, remove)) {
                return false;
            }
        }
        return true;
    }

    private boolean confirmProbe(
            ImportStartupInstallJob job, ImportStartupInstallJob.Probe probe, boolean remove) {
        // 1. 查询登记名称；查询失败不能当作资源不存在。
        var listed = engine.run(List.of(
                "ps", "-aq", "--no-trunc", "--filter", "name=^/" + probe.name() + "$"), 30);
        if (!listed.ok()) {
            return false;
        }
        String id = listed.stdout().strip();
        if (id.isEmpty()) {
            return true;
        }

        // 2. 删除前核对实际 ID、名称和任务标签。
        if (!id.matches("[0-9a-f]{64}")
                || probe.containerId() != null && !id.equals(probe.containerId())) {
            return false;
        }
        var identity = engine.run(List.of(
                "inspect", "--format", "{{.Name}}|{{json .Config.Labels}}", id), 30);
        if (!identity.ok() || !sameProbe(job, probe, identity.stdout())) {
            return false;
        }

        // 3. 只有获准删除且读回为空，才确认清理完成。
        if (!remove || !engine.run(List.of("rm", "-f", id), 60).ok()) {
            return false;
        }
        var remaining = engine.run(List.of("ps", "-aq", "--no-trunc", "--filter", "id=" + id), 30);
        return remaining.ok() && remaining.stdout().isBlank();
    }

    private static boolean sameProbe(
            ImportStartupInstallJob job, ImportStartupInstallJob.Probe probe, String identity) {
        String[] parts = identity.strip().split("\\|", 2);
        if (parts.length != 2 || !parts[0].equals("/" + probe.name())) {
            return false;
        }

        try {
            var labels = JSON.readTree(parts[1]);
            return job.jobId().toString().equals(labels.path("glyahh.import.install-job").asText())
                    && "pinprobe".equals(labels.path("glyahh.import.execution").asText());
        } catch (JsonProcessingException failure) {
            return false;
        }
    }
}
