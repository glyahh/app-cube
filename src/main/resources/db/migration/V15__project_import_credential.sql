CREATE TABLE biz.project_import_credential (
    credential_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    provider VARCHAR(16) NOT NULL CHECK (provider IN ('GitHub','Gitee')),
    name VARCHAR(40) NOT NULL,
    current_version BIGINT NOT NULL DEFAULT 1 CHECK (current_version > 0),
    record_revision BIGINT NOT NULL DEFAULT 0 CHECK (record_revision >= 0),
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','DELETED')),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_import_credential_owner ON biz.project_import_credential(owner_id, provider, state);

CREATE TABLE biz.project_import_credential_version (
    credential_id UUID NOT NULL REFERENCES biz.project_import_credential(credential_id),
    version BIGINT NOT NULL CHECK (version > 0),
    key_id VARCHAR(40),
    nonce VARCHAR(24),
    ciphertext TEXT,
    state VARCHAR(16) NOT NULL CHECK (state IN ('ACTIVE','REVOKED','DELETED')),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (credential_id,version),
    UNIQUE (key_id,nonce),
    CHECK ((state='DELETED' AND key_id IS NULL AND nonce IS NULL AND ciphertext IS NULL)
        OR (state<>'DELETED' AND key_id IS NOT NULL AND nonce IS NOT NULL AND ciphertext IS NOT NULL))
);

-- 资格先在数据库撤销，实际执行资源停止另行核验。
CREATE TABLE biz.project_import_credential_lease (
    task_id UUID PRIMARY KEY REFERENCES biz.project_import_task(task_id),
    credential_id UUID NOT NULL,
    credential_version BIGINT NOT NULL,
    owner_id BIGINT NOT NULL REFERENCES biz."user"(id),
    repository_url TEXT NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' CHECK (state IN ('ACTIVE','REVOKED')),
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (credential_id,credential_version) REFERENCES biz.project_import_credential_version(credential_id,version)
);
