CREATE TABLE biz.project_import_preference (
    owner_id BIGINT PRIMARY KEY REFERENCES biz."user"(id),
    skip_dependencies BOOLEAN NOT NULL DEFAULT FALSE,
    skip_build_outputs BOOLEAN NOT NULL DEFAULT FALSE,
    include_sensitive BOOLEAN NOT NULL DEFAULT FALSE,
    update_time TIMESTAMP NOT NULL DEFAULT now()
);
