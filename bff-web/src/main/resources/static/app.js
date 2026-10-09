// Banca en linea del Banco XYZ. Habla solo con su BFF (/web/api); nunca ve un token: la sesion es una cookie HttpOnly.
'use strict';

const $ = (id) => document.getElementById(id);
const pesos = (n) => '$' + Number(n || 0).toLocaleString('es-CL', {maximumFractionDigits: 0});
const estado = {sesion: null, cuentas: [], cuentaAbierta: null, pagina: 0, clienteVisto: null};

// --- Llamadas al BFF: cookie de sesion + token CSRF (cookie XSRF-TOKEN devuelta en X-XSRF-TOKEN) -------------------
function csrf() {
    const par = document.cookie.split('; ').find((c) => c.startsWith('XSRF-TOKEN='));
    return par ? decodeURIComponent(par.split('=')[1]) : '';
}

async function api(ruta, opciones = {}) {
    const encabezados = {'Accept': 'application/json'};
    if (opciones.cuerpo !== undefined) {
        encabezados['Content-Type'] = 'application/json';
    }
    if (opciones.metodo && opciones.metodo !== 'GET') {
        encabezados['X-XSRF-TOKEN'] = csrf();
    }
    if (opciones.clave) {
        encabezados['Idempotency-Key'] = opciones.clave;
    }
    const respuesta = await fetch('/web/api' + ruta, {
        method: opciones.metodo || 'GET', headers: encabezados, credentials: 'same-origin',
        body: opciones.cuerpo === undefined ? undefined : JSON.stringify(opciones.cuerpo)
    });
    const texto = await respuesta.text();
    const datos = texto ? JSON.parse(texto) : null;
    if (!respuesta.ok) {
        const error = new Error((datos && (datos.detail || datos.codigo)) || ('Error ' + respuesta.status));
        error.problema = datos;
        throw error;
    }
    return datos;
}

function avisar(mensaje, tipo = 'error') {
    const caja = $('aviso');
    caja.textContent = mensaje;
    caja.className = 'aviso ' + tipo;
    setTimeout(() => caja.classList.add('oculto'), 6000);
}

function mostrar(vista) {
    ['vista-ingreso', 'vista-cliente', 'vista-ejecutivo'].forEach((v) => $(v).classList.toggle('oculto', v !== vista));
}

function fila(celdas) {
    const tr = document.createElement('tr');
    celdas.forEach((c) => {
        const td = document.createElement('td');
        if (c instanceof Node) {
            td.appendChild(c);
        } else {
            td.textContent = c === null || c === undefined ? '' : c;
        }
        if (typeof c === 'string' && c.startsWith('$')) {
            td.className = 'num';
        }
        tr.appendChild(td);
    });
    return tr;
}

function boton(texto, accion, clase = 'boton chico') {
    const b = document.createElement('button');
    b.textContent = texto;
    b.className = clase;
    b.addEventListener('click', accion);
    return b;
}

// --- Sesion --------------------------------------------------------------------------------------------------------
async function iniciar() {
    estado.sesion = await api('/sesion');
    const caja = $('sesion');
    caja.innerHTML = '';
    if (!estado.sesion.autenticado) {
        mostrar('vista-ingreso');
        return;
    }
    const quien = document.createElement('span');
    quien.textContent = estado.sesion.usuario + ' · ' + estado.sesion.rol.toLowerCase()
        + (estado.sesion.origen === 'github' ? ' · via GitHub' : '');
    caja.appendChild(quien);
    caja.appendChild(boton('Salir', salir, 'boton'));
    if (estado.sesion.rol === 'EJECUTIVO') {
        mostrar('vista-ejecutivo');
        await cargarConsola();
    } else {
        mostrar('vista-cliente');
        await cargarPanel();
    }
}

async function salir() {
    await fetch('/web/api/salir', {method: 'POST', headers: {'X-XSRF-TOKEN': csrf()}, credentials: 'same-origin'});
    location.href = '/';
}

// --- Cliente -------------------------------------------------------------------------------------------------------
async function cargarPanel() {
    const panel = await api('/panel');
    const nombre = panel.cliente ? panel.cliente.nombre : panel.usuario;
    $('saludo').textContent = 'Hola, ' + nombre.split(' ')[0];
    $('disponible').textContent = pesos(panel.disponible);
    $('deuda').textContent = pesos(panel.deuda);
    if (panel.noDisponibles.length) {
        avisar('Algunas secciones no estan disponibles por ahora: ' + panel.noDisponibles.join(', '), 'advertencia');
    }
    estado.cuentas = panel.cuentas;
    pintarCuentas(panel.cuentas);
    pintarLista('avisos', panel.avisos, (a) => (a.seguridad ? '⚠ ' : '') + a.titulo + ': ' + a.mensaje, (a) => a.seguridad);
    pintarLista('pagos', panel.pagosRecientes, (p) => p.operacion.toLowerCase().replace('_', ' ') + ' ' + pesos(p.monto)
        + ' · ' + p.estado + (p.motivo ? ' (' + p.motivo + ')' : ''), (p) => p.estado === 'RECHAZADO');
    if (panel.cliente) {
        const f = $('form-perfil');
        f.email.value = panel.cliente.email || '';
        f.telefono.value = panel.cliente.telefono || '';
        f.direccion.value = panel.cliente.direccion || '';
    }
}

function pintarCuentas(cuentas) {
    const caja = $('cuentas');
    caja.innerHTML = '';
    cuentas.forEach((c) => {
        const t = document.createElement('div');
        t.className = 'tarjeta cuenta ' + c.tipo + (c.estado !== 'ACTIVA' ? ' inactiva' : '');
        t.innerHTML = '<span class="tipo"></span><strong></strong><span class="numero"></span><span class="estado"></span>';
        t.querySelector('.tipo').textContent = c.tipo + (c.alias ? ' · ' + c.alias : '');
        t.querySelector('strong').textContent = pesos(c.saldoDisponible);
        t.querySelector('.numero').textContent = 'N° ' + c.cuentaId;
        t.querySelector('.estado').textContent = c.estado;
        t.addEventListener('click', () => abrirCuenta(c.cuentaId, 0));
        caja.appendChild(t);
    });
    document.querySelectorAll('.cuentas-ahorro').forEach((s) => opciones(s, cuentas.filter((c) => c.tipo === 'ahorro')));
    document.querySelectorAll('.cuentas-todas').forEach((s) => opciones(s, cuentas));
}

function opciones(select, cuentas) {
    select.innerHTML = '';
    cuentas.forEach((c) => {
        const o = document.createElement('option');
        o.value = c.cuentaId;
        o.textContent = c.cuentaId + ' · ' + c.tipo + ' · ' + pesos(c.saldoDisponible);
        select.appendChild(o);
    });
}

function pintarLista(id, elementos, texto, destacar) {
    const ul = $(id);
    ul.innerHTML = '';
    if (!elementos) {
        ul.innerHTML = '<li class="tenue">No disponible por ahora</li>';
        return;
    }
    if (!elementos.length) {
        ul.innerHTML = '<li class="tenue">Sin novedades</li>';
    }
    elementos.slice(0, 8).forEach((e) => {
        const li = document.createElement('li');
        li.textContent = texto(e);
        if (destacar(e)) {
            li.className = 'destacado';
        }
        ul.appendChild(li);
    });
}

async function abrirCuenta(cuentaId, pagina) {
    estado.cuentaAbierta = cuentaId;
    estado.pagina = pagina;
    const datos = await api('/cuentas/' + cuentaId + '/movimientos?pagina=' + pagina + '&tamano=10');
    $('detalle').classList.remove('oculto');
    $('detalle-titulo').textContent = 'Movimientos de la cuenta ' + cuentaId;
    const cuerpo = $('movimientos');
    cuerpo.innerHTML = '';
    datos.contenido.forEach((m) => cuerpo.appendChild(fila([m.fecha, m.tipo, m.descripcion, pesos(m.monto),
        m.origen === 'CIERRE' ? 'migrado' : 'en linea'])));
    $('mov-pagina').textContent = 'Pagina ' + (pagina + 1) + ' de ' + Math.max(1, datos.totalPaginas);
    $('mov-anterior').disabled = pagina === 0;
    $('mov-siguiente').disabled = pagina + 1 >= datos.totalPaginas;
    const estados = $('estados');
    estados.innerHTML = '';
    try {
        (await api('/cuentas/' + cuentaId + '/estados-anuales')).forEach((e) => estados.appendChild(
            fila([e.anio, e.cantidadMovimientos, pesos(e.totalDepositos), pesos(e.totalCargos), pesos(e.saldoNeto)])));
    } catch (sinEstados) {
        estados.appendChild(fila(['Sin estado anual en el cierre publicado']));
    }
}

async function pagar(evento, tipo) {
    evento.preventDefault();
    const f = evento.target;
    const cuerpo = Object.fromEntries(new FormData(f).entries());
    ['cuentaOrigen', 'cuentaDestino', 'monto'].forEach((k) => {
        if (cuerpo[k] !== undefined) {
            cuerpo[k] = Number(cuerpo[k]);
        }
    });
    const caja = $('resultado-pago');
    try {
        // La clave de idempotencia nace en el navegador: reenviar el formulario no duplica el pago.
        let pago = await api('/pagos/' + tipo, {metodo: 'POST', cuerpo, clave: crypto.randomUUID()});
        caja.className = 'resultado';
        caja.textContent = 'Solicitud registrada (' + pago.estado + '). Esperando la confirmacion...';
        for (let i = 0; i < 12 && pago.estado === 'SOLICITADO'; i++) {
            await new Promise((r) => setTimeout(r, 700));
            pago = await api('/pagos/' + pago.id);
        }
        caja.className = 'resultado ' + (pago.estado === 'COMPLETADO' ? 'ok' : 'error');
        caja.textContent = pago.estado === 'COMPLETADO' ? 'Operacion completada.'
            : pago.estado === 'RECHAZADO' ? 'Operacion rechazada: ' + pago.motivo : 'La operacion sigue en proceso.';
        f.reset();
        await cargarPanel();
    } catch (error) {
        caja.className = 'resultado error';
        caja.textContent = error.message;
    }
}

// --- Ejecutivo -----------------------------------------------------------------------------------------------------
async function cargarConsola() {
    const clientes = await api('/ejecutivo/clientes');
    const cuerpo = $('clientes');
    cuerpo.innerHTML = '';
    clientes.forEach((c) => cuerpo.appendChild(fila([c.clienteId, c.nombre, c.usuario, c.origen,
        boton('Cuentas', () => cuentasDe(c.clienteId, c.nombre))])));
    try {
        const calidad = await api('/ejecutivo/reportes/calidad');
        const caja = $('calidad');
        caja.innerHTML = '';
        calidad.cierres.forEach((c) => {
            const p = document.createElement('p');
            p.textContent = c.conjunto + ': ejecucion ' + c.jobExecutionId + ' (' + c.jobNombre + ') · calidad ' + c.calidad;
            caja.appendChild(p);
        });
        const ul = document.createElement('ul');
        ul.className = 'lista';
        calidad.rechazados.forEach((r) => {
            const li = document.createElement('li');
            li.textContent = r.jobNombre + ' · ' + r.clasificacion + ': ' + r.cantidad + ' filas';
            ul.appendChild(li);
        });
        caja.appendChild(ul);
        const resumen = $('resumen-diario');
        resumen.innerHTML = '';
        (await api('/ejecutivo/reportes/resumen-diario')).slice(0, 12).forEach((d) => resumen.appendChild(
            fila([d.fecha, d.cantidadTransacciones, pesos(d.totalDebitos), pesos(d.totalCreditos), d.cantidadAnomalias])));
    } catch (error) {
        avisar('Reportes no disponibles: ' + error.message, 'advertencia');
    }
}

async function cuentasDe(clienteId, nombre) {
    estado.clienteVisto = {clienteId, nombre};
    $('cuentas-cliente-titulo').textContent = 'Cuentas de ' + nombre;
    const cuerpo = $('cuentas-cliente');
    cuerpo.innerHTML = '';
    (await api('/ejecutivo/clientes/' + clienteId + '/cuentas')).forEach((c) => {
        const acciones = document.createElement('span');
        if (c.estado !== 'CERRADA') {
            acciones.appendChild(boton(c.estado === 'ACTIVA' ? 'Bloquear' : 'Activar', () => mantener(c.cuentaId,
                c.estado === 'ACTIVA' ? 'BLOQUEADA' : 'ACTIVA')));
            acciones.appendChild(boton('Cerrar', () => cerrar(c.cuentaId)));
        }
        cuerpo.appendChild(fila([c.cuentaId, c.tipo, pesos(c.saldoDisponible), c.estado, acciones]));
    });
}

async function mantener(cuentaId, nuevoEstado) {
    try {
        await api('/ejecutivo/cuentas/' + cuentaId, {metodo: 'PATCH', cuerpo: {estado: nuevoEstado}});
        avisar('Cuenta ' + cuentaId + ' ' + nuevoEstado.toLowerCase(), 'ok');
        await cuentasDe(estado.clienteVisto.clienteId, estado.clienteVisto.nombre);
    } catch (error) {
        avisar(error.message);
    }
}

async function cerrar(cuentaId) {
    try {
        await api('/ejecutivo/cuentas/' + cuentaId + '/cierre', {metodo: 'POST', cuerpo: {motivo: 'Solicitud del cliente'}});
        avisar('Cuenta ' + cuentaId + ' cerrada', 'ok');
        await cuentasDe(estado.clienteVisto.clienteId, estado.clienteVisto.nombre);
    } catch (error) {
        avisar(error.message);
    }
}

async function enviarFormulario(evento, ruta, armar, alTerminar) {
    evento.preventDefault();
    try {
        const resultado = await api(ruta, {metodo: 'POST', cuerpo: armar(Object.fromEntries(new FormData(evento.target).entries()))});
        evento.target.reset();
        avisar(alTerminar(resultado), 'ok');
        await cargarConsola();
    } catch (error) {
        const campos = error.problema && error.problema.errores ? ' (' + error.problema.errores.map((e) => e.campo).join(', ') + ')' : '';
        avisar(error.message + campos);
    }
}

// --- Eventos -------------------------------------------------------------------------------------------------------
document.querySelectorAll('.pestana').forEach((p) => p.addEventListener('click', () => {
    document.querySelectorAll('.pestana').forEach((x) => x.classList.toggle('activa', x === p));
    ['form-transferencia', 'form-servicio', 'form-deposito'].forEach((f) => $(f).classList.toggle('oculto', f !== p.dataset.form));
}));
$('form-transferencia').addEventListener('submit', (e) => pagar(e, 'transferencias'));
$('form-servicio').addEventListener('submit', (e) => pagar(e, 'servicios'));
$('form-deposito').addEventListener('submit', (e) => pagar(e, 'depositos'));
$('mov-anterior').addEventListener('click', () => abrirCuenta(estado.cuentaAbierta, estado.pagina - 1));
$('mov-siguiente').addEventListener('click', () => abrirCuenta(estado.cuentaAbierta, estado.pagina + 1));
$('form-perfil').addEventListener('submit', async (e) => {
    e.preventDefault();
    try {
        await api('/perfil', {metodo: 'PUT', cuerpo: Object.fromEntries(new FormData(e.target).entries())});
        avisar('Perfil actualizado', 'ok');
    } catch (error) {
        avisar(error.message);
    }
});
$('form-apertura').addEventListener('submit', (e) => enviarFormulario(e, '/ejecutivo/cuentas',
    (d) => ({clienteId: Number(d.clienteId), tipo: d.tipo, alias: d.alias || null}),
    (c) => 'Cuenta ' + c.cuentaId + ' abierta para ' + c.titular));
$('form-alta').addEventListener('submit', (e) => enviarFormulario(e, '/ejecutivo/clientes',
    (d) => ({nombre: d.nombre, edad: Number(d.edad), usuario: d.usuario, claveInicial: d.claveInicial,
        perfil: {email: d.email || null, telefono: null, direccion: null}}),
    (c) => 'Cliente ' + c.clienteId + ' creado (usuario ' + c.usuario + ')'));

iniciar().catch((error) => avisar(error.message));
