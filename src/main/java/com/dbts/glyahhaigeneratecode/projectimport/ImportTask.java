package com.dbts.glyahhaigeneratecode.projectimport;

import com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import com.dbts.glyahhaigeneratecode.service.support.ProjectImportStaging.StoredFile;
import java.util.List;
import java.util.UUID;

public record ImportTask(UUID taskId, long ownerId, Definition definition, Status status, long revision,
                         int nextFile, List<StoredFile> receipts, String contentHash, String error,
                         String cleanup, String executorId) {
    public enum Status { READING_CONTENT, UPLOADING, WAITING_CONFIRMATION, COMMITTING, SUCCEEDED, CANCELLING, CANCELLED,
        FAILED, STOP_FAILED, RECOVERY_REQUIRED }
    public record Definition(String appName, List<FileEntry> files, Options options, Manifest manifest) {
        public Definition { files = List.copyOf(files); }
    }
    public ImportTask { receipts = List.copyOf(receipts); }
    public record View(UUID taskId, String appName, Status status, long revision, int nextFile,
                       Manifest manifest, String contentHash, String error, String cleanup) { }
    public View view() {
        return new View(taskId, definition.appName(), status, revision, nextFile, definition.manifest(), contentHash, error, cleanup);
    }
}
