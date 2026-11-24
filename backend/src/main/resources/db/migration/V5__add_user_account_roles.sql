alter table app_user add column role varchar(16);

update app_user set role = 'ADMIN';

alter table app_user alter column role set not null;
alter table app_user add constraint app_user_role_check check (role in ('ADMIN', 'STAFF'));
