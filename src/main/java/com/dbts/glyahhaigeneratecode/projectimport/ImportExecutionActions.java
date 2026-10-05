package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.model.Entity.User;
import java.util.List;
import java.util.UUID;

/** 隔离执行对安装/运行开放的动作：先准入，准入通过才执行。旧签名保持给测试桩。 */
public interface ImportExecutionActions {
    List<String> admit(long appId,ImportExecutionSandbox.Limits limits);
    ImportExecutionRunner.Result execute(User user,long appId,UUID planId,String directory,String command,ImportExecutionSandbox.Limits limits);
    default ImportExecutionRunner.Result execute(User user,long appId,UUID planId,String directory,String command,ImportExecutionSandbox.Limits limits,
                                                 String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime){
        return execute(user,appId,planId,directory,command,limits,slot,network,lifetime,null);
    }
    default ImportExecutionRunner.Result execute(User user,long appId,UUID planId,String directory,String command,ImportExecutionSandbox.Limits limits,
                                                 String slot,ImportExecutionSandbox.Network network,ImportExecutionSandbox.Lifetime lifetime,Integer port){
        return execute(user,appId,planId,directory,command,limits);
    }
    /** 计划版本变化时停止其他版本仍在运行的槽位。停不掉时返回 false。 */
    default boolean stopOtherPlans(long appId,String planRevision){return true;}
    /** 停止一次执行。返回停止后的状态；容器未消失时为 STOP_FAILED。 */
    default String stopExecution(UUID id){return "STOPPED";}
    /** 按应用、所有者和计划核对执行状态；无法确认时不能复用旧 READY。 */
    default String executionState(User user,long appId,UUID planId,UUID id){return "UNKNOWN";}
    default String executionReason(User user,long appId,UUID planId,UUID id){return null;}
}
