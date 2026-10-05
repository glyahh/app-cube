package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;

/** 磁盘剩余空间不足时直接失败。这是准入拒绝，不是操作系统硬配额，也不截断已读内容。 */
public final class ImportDiskAdmission {
    private ImportDiskAdmission(){ }

    public static void requireUsable(long usableBytes,long minimumBytes){
        if(minimumBytes<0||usableBytes<minimumBytes)throw new MyException(ErrorCode.OPERATION_ERROR,"磁盘剩余空间不足，已停止。未截断内容。");
    }
}
