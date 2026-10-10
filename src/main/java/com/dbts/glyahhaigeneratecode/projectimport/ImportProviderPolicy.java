package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;

/** 新导入只支持 GitHub；历史来源解析与密文解密不受此策略影响。 */
public final class ImportProviderPolicy {
    private ImportProviderPolicy() { }

    public static boolean supported(String provider) {
        return "GitHub".equals(provider);
    }

    public static void requireSupported(String provider) {
        ThrowUtils.throwIf(!supported(provider), ErrorCode.PARAMS_ERROR, "仅支持 GitHub 仓库导入。");
    }
}
