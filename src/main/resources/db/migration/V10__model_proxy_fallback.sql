ALTER TABLE biz."user"
    ADD COLUMN auto_model_proxy_fallback BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE biz.app
    ADD COLUMN force_model_direct BOOLEAN NOT NULL DEFAULT FALSE;
