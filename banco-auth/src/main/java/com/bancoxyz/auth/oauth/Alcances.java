package com.bancoxyz.auth.oauth;

import java.util.Set;

/** Scopes del banco. Cada microservicio exige el suyo: un token solo sirve para lo que se pidio y se permitio. */
public final class Alcances {

    public static final String CUENTAS_LEER = "cuentas.leer";
    public static final String CUENTAS_ADMINISTRAR = "cuentas.administrar";
    public static final String CLIENTES_LEER = "clientes.leer";
    public static final String CLIENTES_PERFIL = "clientes.perfil";
    public static final String CLIENTES_ADMINISTRAR = "clientes.administrar";
    public static final String PAGOS_ESCRIBIR = "pagos.escribir";
    public static final String PAGOS_LEER = "pagos.leer";
    public static final String REPORTES_LEER = "reportes.leer";
    /** Servicio a servicio: el BFF del cajero (tarjeta + PIN, saldo, retiro). */
    public static final String CAJERO_OPERAR = "cajero.operar";
    /** Operacion: rotar y retirar las claves de firma. Solo el cliente operacion-banco. */
    public static final String CLAVES_ADMINISTRAR = "claves.administrar";

    /** Solo un ejecutivo los recibe aunque el cliente OAuth los pida (minimo privilegio por rol). */
    public static final Set<String> DE_EJECUTIVO = Set.of(CUENTAS_ADMINISTRAR, CLIENTES_ADMINISTRAR, REPORTES_LEER);
    /** Un ejecutivo no paga ni transfiere desde la consola. */
    public static final Set<String> DE_CLIENTE = Set.of(PAGOS_ESCRIBIR, CLIENTES_PERFIL);

    private Alcances() {
    }
}
