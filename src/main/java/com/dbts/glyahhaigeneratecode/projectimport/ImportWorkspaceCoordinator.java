package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Supplier;

/** 同一应用的扫描、保存和删除共用数据库会话锁；不改变原业务事务边界。 */
@Component
public class ImportWorkspaceCoordinator {
    private final JdbcTemplate jdbc;
    private static final long NAMESPACE=Long.MIN_VALUE;
    public ImportWorkspaceCoordinator(DataSource source){jdbc=new JdbcTemplate(source);}

    public <T> T withLock(long appId,Supplier<T> action){
        if(appId<=0||action==null)throw new MyException(ErrorCode.PARAMS_ERROR,"应用或操作无效。");
        try {
            return jdbc.execute((ConnectionCallback<T>)connection->{
                long key=appId^NAMESPACE;
                // 1. 忙时直接拒绝，不让写入无限排队或绕过正在建立的快照。
                if(!query(connection,"SELECT pg_try_advisory_lock(?)",key))throw new MyException(ErrorCode.CONFLICT_ERROR,"应用正在处理，请稍后重试。");
                try {
                    T result=action.get();
                    if(!connection.isValid(2))throw new SQLException("Workspace lock connection lost");
                    return result;
                }finally{
                    // 2. 成败都释放；释放未确认时显式报错，不宣称任务正常完成。
                    if(!query(connection,"SELECT pg_advisory_unlock(?)",key))throw new SQLException("Workspace lock release unconfirmed");
                }
            });
        }catch(org.springframework.dao.DataAccessException error){
            throw new MyException(ErrorCode.SYSTEM_ERROR,"工作区协调失败，请检查任务状态。");
        }
    }

    private static boolean query(Connection connection,String sql,long key)throws SQLException{
        try(var statement=connection.prepareStatement(sql)){
            statement.setLong(1,key);
            try(var result=statement.executeQuery()){
                if(!result.next())throw new SQLException("Workspace lock result missing");
                return result.getBoolean(1);
            }
        }
    }
}
