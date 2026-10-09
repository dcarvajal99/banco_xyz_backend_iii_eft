-- Lo que el batch deja en migracion (tablas de Hibernate) y en public (metadatos de Spring Batch).
-- Ejecucion 10 COMPLETED con cuarentena de aviso (DEGRADADA): es la publicada.
-- Ejecucion 11 FAILED con id mayor y filas escritas: NUNCA se publica.
create schema if not exists migracion;
create table if not exists public.batch_job_instance (
    job_instance_id bigint primary key, version bigint, job_name varchar(100) not null, job_key varchar(32) not null);
create table if not exists public.batch_job_execution (
    job_execution_id bigint primary key, version bigint, job_instance_id bigint not null, create_time timestamp not null,
    start_time timestamp, end_time timestamp, status varchar(10), exit_code varchar(2500), exit_message varchar(2500),
    last_updated timestamp);
create table if not exists public.batch_step_execution (
    step_execution_id bigint primary key, version bigint not null, step_name varchar(100) not null,
    job_execution_id bigint not null, create_time timestamp not null, status varchar(10));
create table if not exists migracion.cierre_cuenta (
    id bigint primary key, job_execution_id bigint not null, cuenta_id bigint not null, cliente_id bigint,
    titular varchar(120) not null, tipo varchar(20) not null, saldo_inicial numeric(18, 2) not null,
    tasa_mensual numeric(8, 5) not null, interes numeric(18, 2) not null, saldo_final numeric(18, 2) not null,
    anomalia boolean not null, observacion varchar(255), linea_origen int not null, filas_en_origen int not null,
    publicado_en timestamp not null);
create table if not exists migracion.movimiento_anual (
    id bigint primary key, cuenta_id bigint not null, fecha date not null, anio int not null,
    tipo_transaccion varchar(20) not null, monto numeric(18, 2) not null, descripcion varchar(255) not null,
    anomalia boolean not null, observacion varchar(255), job_execution_id bigint not null, procesado_en timestamp not null);
create table if not exists migracion.estado_cuenta_anual (
    id bigint primary key, cuenta_id bigint not null, anio int not null, cantidad_movimientos bigint not null,
    total_depositos numeric(18, 2) not null, total_cargos numeric(18, 2) not null, saldo_neto numeric(18, 2) not null,
    primera_fecha date, ultima_fecha date, movimientos_con_anomalia bigint not null, job_execution_id bigint not null,
    generado_en timestamp not null);
create table if not exists migracion.resumen_diario (
    id bigint primary key, fecha date not null, cantidad_transacciones bigint not null,
    total_debitos numeric(18, 2) not null, total_creditos numeric(18, 2) not null, monto_maximo numeric(18, 2) not null,
    cantidad_anomalias bigint not null, job_execution_id bigint not null, generado_en timestamp not null);
create table if not exists migracion.registro_rechazado (
    id bigint primary key, job_nombre varchar(80) not null, archivo varchar(60) not null,
    clasificacion varchar(20) not null, numero_linea int not null, contenido varchar(500) not null,
    motivo varchar(255) not null, job_execution_id bigint not null, registrado_en timestamp not null);

delete from migracion.registro_rechazado;
delete from migracion.resumen_diario;
delete from migracion.estado_cuenta_anual;
delete from migracion.movimiento_anual;
delete from migracion.cierre_cuenta;
delete from public.batch_step_execution;
delete from public.batch_job_execution;
delete from public.batch_job_instance;

insert into public.batch_job_instance values (1, 0, 'migracionCompletaJob', 'k1');
insert into public.batch_job_instance values (2, 0, 'calculoInteresesMensualesJob', 'k2');
insert into public.batch_job_execution values (10, 0, 1, timestamp '2026-10-09 03:00:00', timestamp '2026-10-09 03:00:00',
    timestamp '2026-10-09 03:00:41', 'COMPLETED', 'COMPLETED', '', timestamp '2026-10-09 03:00:41');
insert into public.batch_job_execution values (11, 0, 2, timestamp '2026-10-09 04:00:00', timestamp '2026-10-09 04:00:00',
    timestamp '2026-10-09 04:01:00', 'FAILED', 'FAILED', 'calidad inaceptable', timestamp '2026-10-09 04:01:00');
insert into public.batch_step_execution values (100, 0, 'cuarentenaAvisoStep', 10, timestamp '2026-10-09 03:00:30', 'COMPLETED');
insert into public.batch_step_execution values (101, 0, 'cuarentenaCorteStep', 11, timestamp '2026-10-09 04:01:00', 'COMPLETED');

-- Cliente 4 = Diana Prince, 5 = Jane Smith (ids del maestro de clientes de la migracion).
insert into migracion.cierre_cuenta values (1, 10, 101, 4, 'Diana Prince', 'ahorro', 8000, 0.00500, 40.00, 8040.00, false, null, 2, 2, current_timestamp);
insert into migracion.cierre_cuenta values (2, 10, 102, 5, 'Jane Smith', 'ahorro', 5000, 0.00000, 0.00, 5000.00, true, 'Titular repetido', 4, 2, current_timestamp);
insert into migracion.cierre_cuenta values (3, 10, 103, 5, 'Jane Smith', 'prestamo', 7000, 0.01500, 105.00, 7105.00, false, null, 5, 1, current_timestamp);
insert into migracion.cierre_cuenta values (4, 10, 104, null, 'Sin identificar', 'hipoteca', 5000, 0.00900, 45.00, 5045.00, true, 'Titular sin identificar', 6, 1, current_timestamp);
insert into migracion.cierre_cuenta values (5, 10, 105, 4, 'Diana Prince', 'ahorro', 12000, 0.00600, 72.00, 12072.00, false, 'Bonificacion de tercera edad', 7, 1, current_timestamp);
insert into migracion.cierre_cuenta values (6, 11, 101, 9, 'Atacante', 'ahorro', 999999, 0, 0, 999999.00, false, null, 2, 1, current_timestamp);
insert into migracion.cierre_cuenta values (7, 11, 199, 9, 'Atacante', 'ahorro', 999999, 0, 0, 999999.00, false, null, 3, 1, current_timestamp);

insert into migracion.movimiento_anual values (1, 101, date '2024-12-24', 2024, 'deposito', 3000.00, 'Ingreso navideño', true, 'Tipo normalizado', 10, current_timestamp);
insert into migracion.movimiento_anual values (2, 101, date '2024-11-10', 2024, 'retiro', -1500.00, 'Retiro parcial', false, null, 10, current_timestamp);
insert into migracion.movimiento_anual values (3, 101, date '2024-10-05', 2024, 'compra', -100.00, 'Compra en tienda', false, null, 10, current_timestamp);
insert into migracion.movimiento_anual values (4, 102, date '2024-09-01', 2024, 'deposito', 2000.00, 'Ingreso mensual', false, null, 10, current_timestamp);
insert into migracion.movimiento_anual values (5, 101, date '2025-01-01', 2025, 'deposito', 999999.00, 'No publicado', false, null, 11, current_timestamp);
insert into migracion.estado_cuenta_anual values (1, 101, 2024, 3, 3000.00, 1600.00, 1400.00, date '2024-10-05', date '2024-12-24', 1, 10, current_timestamp);
insert into migracion.estado_cuenta_anual values (2, 102, 2024, 1, 2000.00, 0.00, 2000.00, date '2024-09-01', date '2024-09-01', 0, 10, current_timestamp);
insert into migracion.resumen_diario values (1, date '2024-06-29', 3, 1200.00, 3000.00, 3000.00, 1, 10, current_timestamp);
insert into migracion.resumen_diario values (2, date '2024-06-30', 2, 200.00, 1500.00, 1500.00, 0, 10, current_timestamp);
insert into migracion.registro_rechazado values (1, 'calculoInteresesMensualesJob', 'intereses_trimestrales.csv', 'OMITIDO', 5, '114,Unknown,,30,-1', 'Saldo vacio', 10, current_timestamp);
insert into migracion.registro_rechazado values (2, 'estadosCuentaAnualesJob', 'estados_financieros_anuales.csv', 'OMITIDO', 8, '110,24-07-2024,retiro,,', 'Monto vacio', 10, current_timestamp);
insert into migracion.registro_rechazado values (3, 'reporteTransaccionesDiariasJob', 'movimientos_financieros_diarios.csv', 'OMITIDO', 9, '4,04/05/2024,,invalido', 'Tipo invalido', 10, current_timestamp);
insert into migracion.registro_rechazado values (4, 'calculoInteresesMensualesJob', 'intereses_trimestrales.csv', 'OMITIDO', 10, 'x', 'De la corrida fallida', 11, current_timestamp);
