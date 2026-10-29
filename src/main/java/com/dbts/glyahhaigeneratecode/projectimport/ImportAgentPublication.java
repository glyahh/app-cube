package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.dbts.glyahhaigeneratecode.runtime.SnapshotRecord;
import com.dbts.glyahhaigeneratecode.runtime.WorkspaceSnapshotService;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 发布仅由Java完成；移动窗口中断时依据实际目录和持久阶段恢复。 */
@Service
@lombok.extern.slf4j.Slf4j
public final class ImportAgentPublication {
    private final ImportAgentEditLedger ledger;
    private final ImportWorkspaceCoordinator coordinator;
    private final WorkspacePathResolver paths;
    private final ImportDiskVolume volumes;
    private final ImportStartupSnapshotStore snapshots;
    private final ImportAgentCandidateVerifier verifier;
    private final Path privateRoot;
    private final ImportAgentOutputResources outputs;
    private final WorkspaceSnapshotService snapshotService;
    private final ObjectMapper json=new ObjectMapper();

    public ImportAgentPublication(ImportAgentEditLedger ledger,ImportWorkspaceCoordinator coordinator,WorkspacePathResolver paths,
            ImportDiskVolume volumes,ImportProperties properties)throws IOException{
        this.ledger=ledger;this.coordinator=coordinator;this.paths=paths;this.volumes=volumes;
        outputs=new ImportAgentOutputResources(ledger,volumes,properties);
        snapshotService=new WorkspaceSnapshotService(properties.getStagingRoot().resolveSibling("project_edit_snapshots"),properties.getSnapshotRetain());
        privateRoot=properties.getStagingRoot().resolveSibling("project_edit_publish").toAbsolutePath().normalize();
        new ProjectImportStaging(privateRoot,List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()));
        snapshots=new ImportStartupSnapshotStore(properties.getStagingRoot().resolveSibling("project_edit_private"),
                List.of(Path.of("temp/code_output"),Path.of("temp/code_deploy"),properties.getStagingRoot()),properties.getMaxFiles(),properties.getMaxSourceBytes());
        verifier=new ImportAgentCandidateVerifier(properties.getMaxFiles(),properties.getMaxSourceBytes());
    }

    public void publish(UUID editId,long ownerId){
        publish(editId,ownerId,()->true);
    }

    public void publish(UUID editId,long ownerId,BooleanSupplier requestedEligible){
        var observed=ledger.require(editId,ownerId);
        coordinator.withLock(observed.identity().appId(),()->{
            var row=ledger.require(editId,ownerId);var e=row.identity();
            if(!ledger.qualifiedSealing(row)||!current(e)||!requestedEligible.getAsBoolean())throw conflict();
            Path source=paths.outputDir(e.appId(),CodeGenTypeEnum.AUTO).toAbsolutePath().normalize();
            SnapshotRecord snapshot=null;
            String stage="PREPARE";
            try{
                // 1. 再读独立验证回执，登记发布路径后才分配同文件系统候选。
                var proof=ledger.candidateProof(row);long bytes=json.readTree(row.resources()).get(0).path("diskBytes").asLong();
                Path folder=privateRoot.resolve(editId.toString()),candidate=folder.resolve("candidate"),backup=folder.resolve("backup");
                ProjectImportStaging.guardManagedPath(folder);
                if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS)||!Files.getFileStore(source).equals(Files.getFileStore(privateRoot)))throw conflict();
                var original=snapshots.describe(editId,source,()->ledger.qualifiedSealing(observed));
                if(!original.contentHash().equals(e.sourceHash()))throw conflict();
                String stateHash=proof.path("authorizedStateHash").asText(),candidateHash=proof.path("candidateHash").asText(),manifestHash=proof.path("candidateManifestHash").asText();
                BooleanSupplier candidateEligible=()->{var latest=ledger.require(editId,ownerId);return
                    List.of("SEALING","WAITING_REVIEW","COMMITTING").contains(latest.state())&&!latest.cancelRequested()
                    &&latest.identity().expiresAt().isAfter(java.time.Instant.now())&&requestedEligible.getAsBoolean();};
                var access=volumes.candidateAccess(e,bytes,stateHash,candidateHash,manifestHash,verifier,candidateEligible);
                var inventory=access.inventory();
                byte[] state=volumes.readEditDocument(e,bytes,stateHash,candidateHash,manifestHash,"private-state",candidateEligible);
                var authorized=new ArrayList<String>();json.readTree(state).path("authorized").fieldNames().forEachRemaining(authorized::add);Collections.sort(authorized);
                var journal=json.createObjectNode().put("source",source.toString()).put("folder",folder.toString())
                        .put("candidate",candidate.toString()).put("backup",backup.toString()).put("originalHash",e.sourceHash());
                outputs.plan(row,proof);
                row=ledger.beginPublication(row,journal,authorized);
                // 合入前记录受影响文件的字节；快照只作证据与恢复，不替代目录移动状态机。
                snapshot=snapshotService.create(source,inventory.files().keySet(),"publication");
                row=ledger.bindSnapshot(row,snapshot.id());
                stage="MATERIALIZE";
                Files.createDirectory(folder);Files.createDirectory(candidate);
                final UUID id=editId;BooleanSupplier eligible=()->{
                    var now=ledger.require(id,ownerId);return "COMMITTING".equals(now.state())&&!now.cancelRequested()
                            &&requestedEligible.getAsBoolean()&&!Thread.currentThread().isInterrupted()&&now.identity().expiresAt().isAfter(java.time.Instant.now());
                };
                if(Files.getFileStore(source).getUsableSpace()<Math.addExact(bytes,256L<<20))throw new IOException("PUBLICATION_SPACE_INSUFFICIENT");
                materialize(source,candidate,inventory,access,eligible);
                outputs.copy(row,proof,eligible);
                stage="CANDIDATE_HASH";
                snapshots.verify(original,source,eligible);
                var staged=snapshots.describe(editId,candidate,eligible);journal.put("publishedHash",staged.contentHash());
                row=ledger.publicationPhase(row,"CANDIDATE_READY","CANDIDATE_READY",journal);
                stage="OLD_MOVE";
                // 2. 每次移动前重核版本和资格；目录移动必须保持同一文件系统且不覆盖现有目录。
                snapshots.verify(original,source,eligible);move(source,backup);
                row=ledger.publicationPhase(row,"CANDIDATE_READY","OLD_MOVED",journal);
                stage="NEW_MOVE";
                if(!eligible.getAsBoolean())throw conflict();move(candidate,source);
                row=ledger.publicationPhase(row,"OLD_MOVED","NEW_MOVED",journal);
                snapshots.verify(staged,source,eligible);
                stage="DATABASE_COMMIT";
                row=ledger.commitPublication(row);
                stage="FINAL_CLEANUP";
                // 3. 数据库提交后不回滚新源码；确认资源释放和备份清理后才标记业务成功。
                finishCommitted(row,journal,source,folder,backup);
                try{snapshotService.markCommitted(source,snapshot.id());}
                catch(IOException markError){log.warn("合入后快照标记未写入 edit={}",editId);}
                return null;
            }catch(IOException|RuntimeException error){
                if(snapshot!=null){
                    try{snapshotService.markFailed(source,snapshot.id(),stage);}
                    catch(IOException markError){log.warn("快照失败标记未写入 edit={}",editId);}
                }
                if(error instanceof AccessDeniedException denied){
                    String name=denied.getFile()==null?"":Path.of(denied.getFile()).getFileName().toString();
                    String category=List.of(".env","app.py","empty.txt").contains(name)?name:"OTHER_NODE";
                    log.warn("导入发布文件权限拒绝 edit={} node={}",editId,category);
                }
                String code=error.getMessage();
                log.warn("导入发布未完成 edit={} stage={} type={} code={}",editId,stage,error.getClass().getSimpleName(),
                        code!=null&&code.matches("[A-Z][A-Z0-9_]{1,80}")?code:"UNCONFIRMED");
                var failed=ledger.require(editId,ownerId);
                if(!"NONE".equals(failed.publishPhase())&&!List.of("SUCCEEDED","CANCELLED","FAILED","STALE").contains(failed.state()))ledger.publicationRecovery(failed);
                throw new MyException(ErrorCode.OPERATION_ERROR,"代码合入或清理未完成，请查看恢复状态。");
            }
        });
    }

    /** 调用者已持有应用锁并确认旧执行者停止；不根据阶段自述盲目删除目录。 */
    public void recover(ImportAgentEditLedger.Row row)throws IOException{
        var e=row.identity();var journal=ledger.publicationJournal(row);
        Path source=Path.of(journal.path("source").asText()).toAbsolutePath().normalize();
        Path expected=paths.outputDir(e.appId(),CodeGenTypeEnum.AUTO).toAbsolutePath().normalize();
        Path folder=privateRoot.resolve(e.id().toString()),candidate=folder.resolve("candidate"),backup=folder.resolve("backup");
        if(!source.equals(expected)||!folder.toString().equals(journal.path("folder").asText())
                ||!candidate.toString().equals(journal.path("candidate").asText())||!backup.toString().equals(journal.path("backup").asText())
                ||!e.sourceHash().equals(journal.path("originalHash").asText()))throw new IOException("PUBLICATION_IDENTITY_INVALID");
        for(Path path:List.of(source,folder,candidate,backup))ProjectImportStaging.guardManagedPath(path);
        if("COMMITTED".equals(row.publishPhase())){finishCommitted(row,journal,source,folder,backup);return;}
        // 未提交：移动实际发生但阶段未写入时，仍能从备份/新目录摘要确定恢复方向。
        if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)){
            requireHash(e.id(),backup,e.sourceHash());
            if(Files.exists(source,LinkOption.NOFOLLOW_LINKS)){
                requireHash(e.id(),source,journal.path("publishedHash").asText());
                if(Files.exists(candidate,LinkOption.NOFOLLOW_LINKS))throw new IOException("PUBLICATION_DIRECTORY_CONFLICT");
                move(source,candidate);
            }
            move(backup,source);
        }
        requireHash(e.id(),source,e.sourceHash());
        removeTree(candidate);if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS))Files.delete(folder);
        outputs.finish(row,false);row=release(row);ledger.finishPublication(row,false);
    }

    private void finishCommitted(ImportAgentEditLedger.Row row,com.fasterxml.jackson.databind.JsonNode journal,Path source,Path folder,Path backup)throws IOException{
        requireHash(row.identity().id(),source,journal.path("publishedHash").asText());
        outputs.finish(row,true);
        if(Files.exists(backup,LinkOption.NOFOLLOW_LINKS)){requireHash(row.identity().id(),backup,row.identity().sourceHash());removeTree(backup);}
        if(Files.exists(folder.resolve("candidate"),LinkOption.NOFOLLOW_LINKS))throw new IOException("PUBLICATION_DIRECTORY_CONFLICT");
        if(Files.exists(folder,LinkOption.NOFOLLOW_LINKS))Files.delete(folder);
        row=release(row);ledger.finishPublication(row,true);
    }

    private ImportAgentEditLedger.Row release(ImportAgentEditLedger.Row row)throws IOException{
        if(!"RELEASED".equals(json.readTree(row.resources()).get(0).path("state").asText())){
            long bytes=json.readTree(row.resources()).get(0).path("diskBytes").asLong();volumes.releaseEdit(row.identity(),bytes);row=ledger.volumeReleased(row);
        }
        ledger.candidateReleased(row);ledger.viewReleased(row);return row;
    }

    private void requireHash(UUID id,Path directory,String expected)throws IOException{
        if(expected==null||!expected.matches("[0-9a-f]{64}")||!snapshots.describe(id,directory,()->true).contentHash().equals(expected))
            throw new IOException("PUBLICATION_CONTENT_CHANGED");
    }

    static void materialize(Path source,Path candidate,ImportAgentCandidateVerifier.Inventory inventory,
            ImportAgentCandidateVerifier.FilesAccess access,BooleanSupplier eligible)throws IOException{
        for(String name:inventory.directories().stream().filter(n->!n.equals(".")).sorted(Comparator.comparingInt(String::length)).toList()){
            Path target=inside(candidate,name);Files.createDirectory(target);Path before=inside(source,name);
            if(Files.isDirectory(before,LinkOption.NOFOLLOW_LINKS))attributes(before,target);
        }
        for(var item:inventory.files().entrySet()){
            if(!eligible.getAsBoolean())throw new IOException("PUBLICATION_REVOKED");
            Path target=inside(candidate,item.getKey()),before=inside(source,item.getKey());
            if(Files.isRegularFile(before,LinkOption.NOFOLLOW_LINKS))Files.copy(before,target,StandardCopyOption.COPY_ATTRIBUTES);
            var dos=Files.getFileAttributeView(target,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(dos!=null&&Files.exists(target,LinkOption.NOFOLLOW_LINKS))dos.setReadOnly(false);
            MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception error){throw new IllegalStateException(error);}
            long count=0;byte[] buffer=new byte[64*1024];
            // 已复制的隐藏文件在Windows不能以CREATE重新打开；新文件保持独占创建。
            var options=Files.exists(target,LinkOption.NOFOLLOW_LINKS)
                    ?Set.<OpenOption>of(StandardOpenOption.TRUNCATE_EXISTING,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
                    :Set.<OpenOption>of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
            try(var input=access.open(item.getKey());var output=FileChannel.open(target,options)){
                int length;while((length=input.read(buffer))!=-1){
                    if(!eligible.getAsBoolean()||length>item.getValue().bytes()-count)throw new IOException("PUBLICATION_CONTENT_CHANGED");
                    digest.update(buffer,0,length);count+=length;var block=java.nio.ByteBuffer.wrap(buffer,0,length);while(block.hasRemaining())output.write(block);
                }output.force(true);
            }
            if(count!=item.getValue().bytes()||!HexFormat.of().formatHex(digest.digest()).equals(item.getValue().sha256()))throw new IOException("PUBLICATION_CONTENT_CHANGED");
            if(Files.isRegularFile(before,LinkOption.NOFOLLOW_LINKS))attributes(before,target);
            var posix=Files.getFileAttributeView(target,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            if(posix!=null){var permissions=EnumSet.noneOf(PosixFilePermission.class);var all=PosixFilePermission.values();for(int i=0;i<9;i++)if((item.getValue().mode()&(1<<(8-i)))!=0)permissions.add(all[i]);posix.setPermissions(permissions);}
        }
        attributes(source,candidate);
    }

    private static Path inside(Path root,String name)throws IOException{
        Path path=root.resolve(name).normalize();if(!path.startsWith(root)||name.startsWith("/")||name.contains("\\")||Arrays.asList(name.split("/",-1)).contains(".."))throw new IOException("PUBLICATION_PATH_INVALID");
        ProjectImportStaging.guardManagedPath(path);return path;
    }

    private static void attributes(Path source,Path target)throws IOException{
        var acl=Files.getFileAttributeView(source,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(acl!=null){
            var out=Files.getFileAttributeView(target,AclFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
            try{if(!out.getAcl().equals(acl.getAcl()))out.setAcl(acl.getAcl());}
            catch(AccessDeniedException error){throw new IOException("PUBLICATION_ACL_WRITE_DENIED");}
            try{if(!out.getOwner().equals(acl.getOwner()))out.setOwner(acl.getOwner());}
            catch(AccessDeniedException error){throw new IOException("PUBLICATION_OWNER_WRITE_DENIED");}
        }
        var posix=Files.getFileAttributeView(source,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(posix!=null){var value=posix.readAttributes();var out=Files.getFileAttributeView(target,PosixFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);out.setOwner(value.owner());out.setGroup(value.group());out.setPermissions(value.permissions());}
        var dos=Files.getFileAttributeView(source,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);
        if(dos!=null){var value=dos.readAttributes();var out=Files.getFileAttributeView(target,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);out.setHidden(value.isHidden());out.setSystem(value.isSystem());out.setReadOnly(value.isReadOnly());}
    }

    private static void move(Path source,Path target)throws IOException{
        ProjectImportStaging.guardManagedPath(source);ProjectImportStaging.guardManagedPath(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)||!Files.getFileStore(source).equals(Files.getFileStore(target.getParent())))throw new IOException("PUBLICATION_MOVE_INVALID");
        Files.move(source,target,StandardCopyOption.ATOMIC_MOVE);
    }

    static void removeTree(Path root)throws IOException{
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;ProjectImportStaging.guardManagedPath(root);
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            @Override public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{
                ProjectImportStaging.guardManagedPath(file);if(!attrs.isRegularFile())throw new IOException("PUBLICATION_NODE_INVALID");
                var dos=Files.getFileAttributeView(file,DosFileAttributeView.class,LinkOption.NOFOLLOW_LINKS);if(dos!=null)dos.setReadOnly(false);Files.delete(file);return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }

    public void releaseOutputs(long appId){
        try{outputs.releaseApp(appId);}catch(IOException|RuntimeException error){throw new MyException(ErrorCode.OPERATION_ERROR,"执行产物清理未确认，应用未删除。");}
    }

    public void discardOutputs(ImportAgentEditLedger.Row row)throws IOException{outputs.finish(row,false);}

    private static boolean current(ImportAgentEditLedger.Entry e){var p=ProcessHandle.current();return e.executorPid()==p.pid()&&e.executorStart().equals(p.info().startInstant().orElse(null));}
    private static MyException conflict(){return new MyException(ErrorCode.CONFLICT_ERROR,"发布资格或原内容已变化。");}
}
