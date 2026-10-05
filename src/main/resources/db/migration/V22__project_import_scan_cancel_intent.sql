-- 恢复或停止失败期间保留用户取消意图，不能靠错误文案猜测。
ALTER TABLE biz.project_import_startup_scan ADD COLUMN cancel_requested BOOLEAN NOT NULL DEFAULT false;
UPDATE biz.project_import_startup_scan SET cancel_requested=true WHERE status IN ('CANCELLING','CANCELLED');
ALTER TABLE biz.project_import_startup_scan ADD CONSTRAINT startup_scan_success_not_cancelled
    CHECK (status <> 'SUCCEEDED' OR cancel_requested=false);
ALTER TABLE biz.project_import_startup_scan ADD CONSTRAINT startup_scan_cancelled_has_intent
    CHECK (status <> 'CANCELLED' OR cancel_requested=true);
