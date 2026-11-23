create table app_user (
    id uuid primary key,
    login_identifier varchar(190) not null,
    display_name varchar(200) not null,
    password_hash varchar(255) not null,
    active boolean not null default true,
    session_version bigint not null default 0,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    password_changed_at timestamp with time zone not null,
    constraint app_user_login_identifier_unique unique (login_identifier),
    constraint app_user_login_identifier_normalized check (login_identifier = lower(trim(login_identifier)))
);

create table auth_login_attempt (
    key_type varchar(16) not null,
    key_value varchar(255) not null,
    window_started_at timestamp with time zone not null,
    failure_count integer not null,
    blocked_until timestamp with time zone,
    updated_at timestamp with time zone not null,
    primary key (key_type, key_value),
    constraint auth_login_attempt_failure_count_nonnegative check (failure_count >= 0)
);

create index auth_login_attempt_updated_at_idx on auth_login_attempt (updated_at);
