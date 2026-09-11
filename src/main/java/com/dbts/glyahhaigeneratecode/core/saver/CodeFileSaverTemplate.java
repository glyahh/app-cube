package com.dbts.glyahhaigeneratecode.core.saver;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;

import java.io.File;

/**
 * 代码文件保存模板，按步骤完成校验、建目录、落盘、返回目录
 * 抽象类
 *
 * 大致思路: 校验入参 → 建唯一目录 → 子类写文件 → 返回目录 File
 */
public abstract class CodeFileSaverTemplate<T> {

    /**
     * 统一入口：校验 → 建目录 → 子类写文件 → 返回目录 File
     *
     * @param codeResult 待保存的代码模型（HtmlCodeResult / MultiFileCodeResult）
     * @param appId      应用主键，参与目录名拼接
     * @return 保存后的目录对象
     */
    public final File save(T codeResult, Long appId) {
        // 1. 校验 codeResult 非空
        validateInput(codeResult);
        // 2. 生成并创建唯一输出目录
        String uniqueDirName = buildUniqueDirName(appId);
        // 3. 子类在目录内写入具体文件
        saveFiles(uniqueDirName, codeResult);
        // 4. 返回目录 File 供上层记录路径
        return new File(uniqueDirName);
    }

    /**
     * 校验入参是否合法，为空则抛 MyException
     *
     * @param codeResult 待保存的代码模型
     */
    private void validateInput(T codeResult) {
        // 1. null 表示无可保存内容，直接 PARAMS_ERROR
        if (codeResult == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "保存的代码对象为空");
        }
    }

    /**
     * 构建唯一目录并创建，返回目录路径字符串
     *
     * @param appId 应用主键
     * @return 唯一目录路径字符串
     */
    private String buildUniqueDirName(Long appId) {
        // 1. 拼路径（尚未 mkdir）
        String uniqueDirName = buildUniqueDirPath(appId);
        // 2. 确保目录存在
        ensureDirExists(uniqueDirName);
        // 3. 返回路径
        return uniqueDirName;
    }

    /**
     * 拼出唯一目录路径（此时尚未创建目录）
     *
     * @param appId 应用主键
     * @return 目录路径字符串
     */
    private String buildUniqueDirPath(Long appId) {
        // 1. 取子类业务类型（html / multi_file 等）
        String bizType = getBizType();
        // 2. 根路径 + 分隔符 + 类型_appId
        // 根路径统一从 AppConstant 取 —— 原先这里硬编码了 Windows 绝对路径，换机器即失效。
        return AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + bizType + "_" + appId;
    }

    /**
     * 根据路径创建目录（不存在则创建）
     *
     * @param dirPath 目录路径
     */
    private void ensureDirExists(String dirPath) {
        // 1. Hutool 递归创建目录
        FileUtil.mkdir(dirPath);
    }

    /**
     * 子类返回当前保存类型标识，用于拼进目录名
     *
     * @return 如 html / multi_file
     */
    protected abstract String getBizType();

    /**
     * 子类实现：在指定目录下写入具体文件
     *
     * @param uniqueDirName 已创建好的唯一目录路径
     * @param codeResult    待保存的代码模型
     */
    protected abstract void saveFiles(String uniqueDirName, T codeResult);

    /**
     * 在指定目录下写入单个文件（子类可调）
     *
     * @param Path     目录路径
     * @param fileName 文件名
     * @param content  文件内容
     */
    protected void writeSingleFile(String Path, String fileName, String content) {
        // 1. 空内容禁止写入，避免覆盖掉用户已有文件
        if (StrUtil.isBlank(content)) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "生成的 " + fileName + " 内容为空，已终止写入");
        }
        // 2. 拼完整路径
        String filePath = Path + File.separator + fileName;
        // 3. UTF-8 落盘
        FileUtil.writeString(content, filePath, "UTF-8");
    }
}
