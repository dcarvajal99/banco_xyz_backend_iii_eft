-- Lo que el batch deja en el esquema migracion (misma forma que crea Hibernate en banco-xyz-batch): el maestro de
-- clientes con ids estables de la carga de semana_3 de fin_legacy_data.
create schema if not exists migracion;
create table if not exists migracion.cliente_migrado (
    id                bigint primary key,
    nombre            varchar(120) not null unique,
    edad              int          not null,
    cuentas_en_cierre int          not null,
    primera_ejecucion bigint       not null,
    ultima_ejecucion  bigint       not null,
    actualizado_en    timestamp    not null
);
delete from migracion.cliente_migrado;
insert into migracion.cliente_migrado values
    (1, 'Alice Brown', 45, 12, 1, 1, current_timestamp),
    (2, 'Bob Johnson', 45, 5, 1, 1, current_timestamp),
    (3, 'Charlie Green', 30, 7, 1, 1, current_timestamp),
    (4, 'Diana Prince', 35, 6, 1, 1, current_timestamp),
    (5, 'Jane Smith', 45, 9, 1, 1, current_timestamp),
    (6, 'John Doe', 25, 6, 1, 1, current_timestamp),
    (7, 'Steve Rogers', 25, 4, 1, 1, current_timestamp);
