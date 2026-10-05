"""事务私有 schema 验证 V24/实际 SQL；不创建业务批准，不调用模型，不停止进程。"""
import json
import re
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
import psycopg


def main():
    evidence=Path('temp/import-startup-model-lease-20261004')/('schema-'+str(time.time_ns()))
    evidence.mkdir(parents=True)
    report={'status':'RUNNING','checks':[],'workflow':'NOT_IMPLEMENTED','migrationExecuted':False,'modelCalls':0,'evidence':str(evidence)}
    dsn=next(x.split('=',1)[1].strip().strip('"').strip("'") for x in Path('.env').read_text(encoding='utf-8-sig').splitlines() if x.startswith('AGENT_POSTGRES_DSN='))
    try:
        with psycopg.connect(dsn) as db:
            schema='startup_lease_probe_'+uuid.uuid4().hex
            db.execute('CREATE SCHEMA '+schema)
            scan,app,owner,task,content,revision,epoch=db.execute('SELECT scan_id,app_id,owner_id,import_task_id,content_hash,config_revision,context_epoch FROM biz.project_import_startup_inspection ORDER BY created_at DESC LIMIT 1').fetchone()
            rules=re.search(r'RULES_VERSION="([^"]+)"',Path('src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupInspectionRepository.java').read_text(encoding='utf-8')).group(1)
            tables={'project_import_startup_inspection':'inspection','project_import_startup_configuration':'configuration','project_import_publication':'publication','app':'app','project_import_startup_approval':'approval','project_import_startup_model_execution':'execution'}
            for original,target in list(tables.items())[:4]:
                db.execute(f'CREATE TABLE {schema}.{target} AS SELECT * FROM biz.{original} WHERE '+('id=%s' if original=='app' else 'app_id=%s'),(app,))
            db.execute(f"UPDATE {schema}.inspection SET state='SUCCEEDED',rules_version=%s WHERE scan_id=%s",(rules,scan))
            db.execute(f'UPDATE {schema}.configuration SET config_revision=%s,context_epoch=%s',(revision,epoch))
            def replace(text):
                for original,target in tables.items():text=text.replace('biz.'+original,schema+'.'+target)
                return text
            # V23 的外键仍指向现有受控应用；只创建/修改事务内表，原表只读。
            ddl23=Path('src/main/resources/db/migration/V23__project_import_startup_approval.sql').read_text(encoding='utf-8').replace('biz.project_import_startup_approval',schema+'.approval')
            db.execute(ddl23)
            db.execute(replace(Path('src/main/resources/db/migration/V24__project_import_startup_model_execution.sql').read_text(encoding='utf-8')))
            approval_sql=[replace(x).replace('?','%s') for x in re.findall(r'"""(.*?)"""',Path('src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupApprovalRepository.java').read_text(encoding='utf-8'),re.S)]
            lease_sql=[replace(x).replace('?','%s') for x in re.findall(r'"""(.*?)"""',Path('src/main/java/com/dbts/glyahhaigeneratecode/projectimport/ImportStartupModelLeaseRepository.java').read_text(encoding='utf-8'),re.S)]
            assert len(approval_sql)==2 and len(lease_sql)==4
            approval,request,executor=uuid.uuid4(),uuid.uuid4(),uuid.uuid4()
            expires=datetime.now(timezone.utc)+timedelta(minutes=3)
            values=(approval,request,owner,app,scan,task,content,revision,epoch,rules,'["."]','a'*64,'APPROVE','APPROVED',expires,scan,owner,app,task,content,revision,epoch,rules)
            assert db.execute(approval_sql[0],values).rowcount==1
            def transition(state,version,target,cancel=False):
                return db.execute(approval_sql[1],(target,None,None,cancel,approval,owner,app,scan,version,state,target,rules)).rowcount
            assert transition('APPROVED',0,'RUNNING')==1
            start=datetime.now(timezone.utc)
            claim=(executor,123,start,approval,owner,app,1)
            assert db.execute(lease_sql[0],claim).rowcount==1
            qualification=(approval,owner,app,0,executor,123,start,rules)
            assert db.execute(lease_sql[1],qualification).fetchone()[0]==1
            report['checks'].append({'name':'实际 claim 绑定运行批准版本和唯一执行身份','status':'PASS'})
            try:
                with db.transaction():db.execute(lease_sql[0],claim)
            except psycopg.errors.UniqueViolation:pass
            else:raise AssertionError('duplicate executor admitted')
            bad=list(qualification);bad[4]=uuid.uuid4()
            assert db.execute(lease_sql[1],bad).fetchone()[0]==0
            report['checks'].append({'name':'重复 claim 和另一 executor 不取得租约','status':'PASS'})
            try:
                with db.transaction():db.execute(f'UPDATE {schema}.execution SET owner_id=owner_id+1 WHERE approval_id=%s',(approval,))
            except psycopg.errors.ForeignKeyViolation:pass
            else:raise AssertionError('foreign owner admitted')
            db.execute(f'UPDATE {schema}.configuration SET context_epoch=context_epoch+1')
            assert db.execute(lease_sql[1],qualification).fetchone()[0]==0
            db.execute(f'UPDATE {schema}.configuration SET context_epoch=%s',(epoch,))
            db.execute(f'UPDATE {schema}.approval SET expires_at=%s WHERE approval_id=%s',(datetime.now(timezone.utc)-timedelta(seconds=1),approval))
            assert db.execute(lease_sql[1],qualification).fetchone()[0]==0
            db.execute(f'UPDATE {schema}.approval SET expires_at=%s WHERE approval_id=%s',(expires,approval))
            assert db.execute(lease_sql[1],qualification).fetchone()[0]==1
            report['checks'].append({'name':'数据库拒绝另一 owner，epoch变化和批准到期撤销资格','status':'PASS'})
            def resource(executor_id=executor,version=0):
                return db.execute(lease_sql[2],(json.dumps([{'pid':456,'started':start.isoformat()}]),approval,owner,app,version,executor_id,123,start)).rowcount
            assert resource()==1
            assert transition('RUNNING',1,'CANCELLING',True)==1
            assert db.execute(lease_sql[1],qualification).fetchone()[0]==0
            assert resource()==1 and resource(uuid.uuid4())==0
            report['checks'].append({'name':'取消撤销运行资格但仍登记本租约待清理资源','status':'PASS'})
            assert db.execute(lease_sql[3],('DONE',approval,owner,app,0,executor,123,start,'PENDING')).rowcount==1
            assert resource()==0
            assert db.execute(lease_sql[3],('FAILED',approval,owner,app,0,executor,123,start,'PENDING')).rowcount==0
            report['checks'].append({'name':'清理 CAS 关闭后旧资源写入和旧清理版本被拒绝','status':'PASS'})
            db.rollback()
        report['status']='PASS'
    except Exception as error:
        report.update(status='FAIL',errorType=type(error).__name__)
        raise RuntimeError('模型租约 SQL 预检失败，见脱敏报告') from None
    finally:(evidence/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False))


if __name__=='__main__':main()
