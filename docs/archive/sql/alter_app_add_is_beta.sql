use gly_ai_generate_code;

alter table app
    add column if not exists is_beta tinyint default 0 not null comment '是否 beta 应用：0-否，1-是（workflow beta）' after userId;
