"""平台停止身份与持久阶段反例；Docker/挂载替身，不替代真实容器。"""
import contextlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
import socket
import os

spec=importlib.util.spec_from_file_location('container_volume',Path(__file__).parents[1]/'import_disk_volume.py')
volume=importlib.util.module_from_spec(spec);spec.loader.exec_module(volume)


@unittest.skipUnless(sys.platform=='linux','LINUX_FILESYSTEM_REQUIRED')
class EditContainerTest(unittest.TestCase):
    @contextlib.contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            expected=volume.identity('101','a'*64,64<<20,owner_id='7',run_id='201',edit_id=str(uuid.uuid4()))
            with patch.object(volume,'ROOT',Path(directory)),patch.object(volume,'locked',contextlib.nullcontext),patch.object(volume,'mounted',return_value={}):
                folder=volume.resource_folder(expected);folder.mkdir()
                record={**expected,'state':'READY','viewState':'SEALED'};volume.write_record(folder,record)
                identifier='b'*64;name='glyahh-edit-'+expected['editId'];calls=[]
                live={'present':False,'running':True,'stopFails':False,'owner':'7','memory':1<<30}
                def command(args,allow_failure=False):
                    calls.append(args)
                    if args[1]=='ps':return subprocess.CompletedProcess(args,0,identifier if live['present'] else '', '')
                    if args[1]=='create':
                        self.assertEqual(json.loads((folder/'record.json').read_text())['editContainer']['state'],'PLANNED')
                        live['present']=True;return subprocess.CompletedProcess(args,0,identifier,'')
                    if args[1]=='start':live['running']=True;return subprocess.CompletedProcess(args,0,identifier,'')
                    if args[1]=='stop':
                        self.assertEqual(json.loads((folder/'record.json').read_text())['editContainer']['state'],'STOPPING')
                        if not live['stopFails']:live['running']=False
                        return subprocess.CompletedProcess(args,0,'','')
                    if args[1]=='rm':live['present']=False;return subprocess.CompletedProcess(args,0,'','')
                    if not live['present']:return subprocess.CompletedProcess(args,1,'','Error: No such object')
                    labels={'glyahh.import.edit':expected['editId'],'glyahh.import.owner':live['owner'],'glyahh.import.app':'101',
                            'glyahh.import.run':'201','glyahh.import.content':'a'*64,'glyahh.session_id':'app:101'}
                    item={'Id':identifier,'Name':'/'+name,'Config':{'Labels':labels,'User':'65534:65534','Image':'glyahh-sandbox-runtime:24.04','Cmd':['sleep','infinity'],'WorkingDir':'/workspace'},
                          'HostConfig':{'Privileged':False,'ReadonlyRootfs':True,'NetworkMode':'none','Memory':live['memory'],'MemorySwap':1<<30,'NanoCpus':1_000_000_000,'PidsLimit':128,'CapDrop':['ALL'],'SecurityOpt':['no-new-privileges']},
                          'Mounts':[{'Type':'bind','Source':str(folder/'work/view'),'Destination':'/workspace','RW':True}],
                          'State':{'Running':live['running'],'Pid':123 if live['running'] else 0}}
                    return subprocess.CompletedProcess(args,0,json.dumps([item]),'')
                with patch.object(volume,'command',side_effect=command):yield expected,folder,live,calls

    def test_intent_before_creation_stop_before_removal_and_repeat_safe(self):
        with self.fixture() as (expected,folder,live,calls):
            self.assertEqual(volume.edit_container(expected,True)['state'],'CONTAINER_CREATED')
            self.assertEqual(volume.edit_container(expected)['state'],'STOP_CONFIRMED')
            self.assertEqual(json.loads((folder/'record.json').read_text())['editContainer']['state'],'REMOVED')
            self.assertFalse(live['present']);self.assertEqual(volume.edit_container(expected)['state'],'STOP_CONFIRMED')
            self.assertEqual(sum(args[1]=='rm' for args in calls),1)

    def test_wrong_owner_and_unknown_container_never_stopped(self):
        with self.fixture() as (expected,folder,live,calls):
            live['present']=True
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_CONTAINER_UNREGISTERED'):volume.edit_container(expected)
            live['present']=False;volume.edit_container(expected,True);live['owner']='8'
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_CONTAINER_IDENTITY_CONFLICT'):volume.edit_container(expected)
            self.assertFalse(any(args[1] in ('stop','rm') for args in calls))

    def test_stop_not_confirmed_keeps_registration_and_never_removes(self):
        with self.fixture() as (expected,folder,live,calls):
            volume.edit_container(expected,True);live['stopFails']=True
            with self.assertRaisesRegex(volume.VolumeError,'EDIT_CONTAINER_STOP_UNCONFIRMED'):volume.edit_container(expected)
            self.assertEqual(json.loads((folder/'record.json').read_text())['editContainer']['state'],'STOPPING')
            self.assertFalse(any(args[1]=='rm' for args in calls))

    def test_start_registered_id_after_identity_checks_and_kernel_proof(self):
        with self.fixture() as (expected,folder,live,calls):
            identifier=volume.edit_container(expected,True)['containerId'];live['running']=False
            proof={'memoryBytes':1<<30,'swapBytes':0,'pids':128,'cpuQuota':100000,'cpuPeriod':100000}
            with patch.object(volume,'edit_hard_limits',return_value=proof) as guard:
                receipt=volume.edit_container(expected,start_id=identifier)
            self.assertEqual(receipt['state'],'CONTAINER_RUNNING');self.assertEqual(receipt['limits'],proof)
            guard.assert_called_once();self.assertTrue(live['running']);self.assertFalse(any(args[1] in ('stop','rm') for args in calls))

    def test_start_wrong_id_or_owner_or_memory_never_executes(self):
        with self.fixture() as (expected,folder,live,calls):
            identifier=volume.edit_container(expected,True)['containerId'];live['running']=False
            with self.assertRaises(volume.VolumeError):volume.edit_container(expected,start_id='c'*64)
            live['owner']='8'
            with self.assertRaises(volume.VolumeError):volume.edit_container(expected,start_id=identifier)
            live['owner']='7';live['memory']=2<<30
            with self.assertRaises(volume.VolumeError):volume.edit_container(expected,start_id=identifier)
            self.assertFalse(any(args[1] in ('start','stop','rm') for args in calls))

    def test_unenforceable_limits_stop_and_keep_registration_for_recovery(self):
        with self.fixture() as (expected,folder,live,calls):
            identifier=volume.edit_container(expected,True)['containerId']
            with patch.object(volume,'edit_hard_limits',side_effect=volume.VolumeError('EDIT_LIMITS_UNENFORCEABLE')):
                with self.assertRaisesRegex(volume.VolumeError,'EDIT_LIMITS_UNENFORCEABLE'):volume.edit_container(expected,start_id=identifier)
            self.assertFalse(live['running']);self.assertTrue(live['present']);self.assertEqual(json.loads((folder/'record.json').read_text())['editContainer']['state'],'STOPPING')
            self.assertFalse(any(args[1]=='rm' for args in calls))

    def test_limits_failure_and_stop_failure_keep_occupancy(self):
        with self.fixture() as (expected,folder,live,calls):
            identifier=volume.edit_container(expected,True)['containerId'];live['stopFails']=True
            with patch.object(volume,'edit_hard_limits',side_effect=volume.VolumeError('EDIT_LIMITS_UNENFORCEABLE')):
                with self.assertRaisesRegex(volume.VolumeError,'EDIT_CONTAINER_STOP_UNCONFIRMED'):volume.edit_container(expected,start_id=identifier)
            self.assertTrue(live['running']);self.assertTrue(live['present']);self.assertEqual(json.loads((folder/'record.json').read_text())['editContainer']['state'],'STOPPING')

    def test_kernel_guard_pins_and_reads_swap_and_rejects_unlimited_cpu(self):
        with self.fixture() as (expected,folder,live,calls):
            identifier=volume.edit_container(expected,True)['containerId']
            item=json.loads(volume.command(['docker','inspect',identifier]).stdout)[0]
            unified='/docker-'+identifier+'.scope';group=folder/'kernel/sys/fs/cgroup'/unified.lstrip('/');group.mkdir(parents=True)
            proc=folder/'kernel/proc/123';proc.mkdir(parents=True);(proc/'cgroup').write_text('0::'+unified+'\n')
            for name,value in {'memory.max':str(1<<30),'memory.swap.max':str(4<<30),'cpu.max':'100000 100000','pids.max':'128'}.items():
                (group/name).write_text(value)
            def mapped(*args):
                path=Path(*args);text=str(path)
                return folder/'kernel'/text.lstrip('/') if text.startswith(('/proc/','/sys/fs/cgroup')) else path
            with socket.socket(socket.AF_UNIX) as endpoint:
                address=folder/'docker.sock';endpoint.bind(str(address))
                with patch.dict(os.environ,{'DOCKER_HOST':'unix://'+str(address)}),patch.object(volume,'Path',side_effect=mapped):
                    proof=volume.edit_hard_limits(item);self.assertEqual(proof['swapBytes'],0)
                    self.assertEqual((group/'memory.swap.max').read_text().strip(),'0')
                    (group/'cpu.max').write_text('max 100000')
                    with self.assertRaisesRegex(volume.VolumeError,'EDIT_LIMITS_UNENFORCEABLE'):volume.edit_hard_limits(item)
                    (group/'cpu.max').write_text('100000 100000');(group/'memory.swap.max').unlink()
                    with self.assertRaisesRegex(volume.VolumeError,'EDIT_LIMITS_UNENFORCEABLE'):volume.edit_hard_limits(item)

    def test_kernel_guard_rejects_remote_engine_before_host_mutation(self):
        with self.fixture() as (expected,folder,live,calls):
            identifier=volume.edit_container(expected,True)['containerId'];item=json.loads(volume.command(['docker','inspect',identifier]).stdout)[0]
            with patch.dict(os.environ,{'DOCKER_HOST':'tcp://127.0.0.1:2375'}):
                with self.assertRaisesRegex(volume.VolumeError,'EDIT_LIMITS_UNENFORCEABLE'):volume.edit_hard_limits(item)


if __name__=='__main__':unittest.main()
