package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.Options;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 只保存明确提交的默认选项，不改写任何已冻结的导入任务。 */
@Service
public class ImportPreferenceService {
    private final JdbcTemplate jdbc;
    public ImportPreferenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Options get(long ownerId) {
        validateOwner(ownerId);
        var rows = jdbc.queryForList("SELECT skip_dependencies, skip_build_outputs, include_sensitive FROM biz.project_import_preference WHERE owner_id=?", ownerId);
        if (rows.isEmpty()) return Options.defaults();
        var row = rows.getFirst();
        return new Options((Boolean) row.get("skip_dependencies"), (Boolean) row.get("skip_build_outputs"), (Boolean) row.get("include_sensitive"));
    }

    public Options save(long ownerId, Options options) {
        validateOwner(ownerId);
        if (options == null) throw new MyException(ErrorCode.PARAMS_ERROR, "请选择导入选项。");
        int updated = jdbc.update("""
                INSERT INTO biz.project_import_preference(owner_id, skip_dependencies, skip_build_outputs, include_sensitive)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (owner_id) DO UPDATE SET skip_dependencies=EXCLUDED.skip_dependencies,
                    skip_build_outputs=EXCLUDED.skip_build_outputs, include_sensitive=EXCLUDED.include_sensitive, update_time=now()
                """, ownerId, options.skipDependencies(), options.skipBuildOutputs(), options.includeSensitive());
        if (updated != 1) throw new MyException(ErrorCode.OPERATION_ERROR, "偏好保存失败。");
        return options;
    }

    private static void validateOwner(long ownerId) {
        if (ownerId <= 0) throw new MyException(ErrorCode.PARAMS_ERROR, "用户标识无效。");
    }
}
