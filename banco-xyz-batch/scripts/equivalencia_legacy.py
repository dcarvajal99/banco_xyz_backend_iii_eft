#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Equivalencia con el proceso legacy: recalcula de forma INDEPENDIENTE los tres reportes y el cierre a partir de los
CSV originales de fin_legacy_data y los compara, valor por valor, con lo que produjo el batch.

No comparte una sola linea con el codigo Java: reescribe en Python las reglas de negocio que aplicaba el proceso
COBOL/shell (tasas por producto, bonificacion de tercera edad, redondeo bancario HALF_UP a dos decimales, signo del
movimiento segun su tipo, totales por dia y por cuenta/anio) y las reglas de depuracion del dato sucio. Si dos
implementaciones escritas por separado llegan al mismo numero en cada fila, el batch es equivalente al legacy.

Ademas resume que cambia respecto del archivo legacy sin depurar (lo que el sistema antiguo procesaba a ciegas).

Uso:
    python3 scripts/equivalencia_legacy.py <carpeta de entrada> <carpeta de salida del batch>
    python3 scripts/equivalencia_legacy.py data/semana_3 salida

Codigo de salida: 0 si no hay diferencias, 1 si las hay.
"""
import csv
import re
import sys
import unicodedata
from collections import Counter, defaultdict
from datetime import date
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from pathlib import Path

# ---------------------------------------------------------------------------------------- reglas de negocio legacy
TASAS = {"ahorro": Decimal("0.00500"), "prestamo": Decimal("0.01500"), "hipoteca": Decimal("0.00900")}
BONIFICACION_TERCERA_EDAD = Decimal("0.00100")
EDAD_TERCERA_EDAD, EDAD_MINIMA, EDAD_MAXIMA = 60, 18, 99
TIPOS_TRANSACCION = {"debito", "credito"}
TIPOS_MOVIMIENTO = {"deposito", "retiro", "compra", "pago"}
MOVIMIENTOS_DE_CARGO = {"retiro", "compra", "pago"}
SIN_IDENTIFICAR = "Sin identificar"
CENTAVOS = Decimal("0.01")

# Los cuatro formatos de fecha que conviven en el archivo legacy.
FORMATOS_FECHA = [
    (re.compile(r"^(\d{4})-(\d{2})-(\d{2})$"), (1, 2, 3)),
    (re.compile(r"^(\d{4})/(\d{2})/(\d{2})$"), (1, 2, 3)),
    (re.compile(r"^(\d{2})-(\d{2})-(\d{4})$"), (3, 2, 1)),
    (re.compile(r"^(\d{2})/(\d{2})/(\d{4})$"), (3, 2, 1)),
]


def fecha(texto):
    texto = (texto or "").strip()
    for patron, (a, m, d) in FORMATOS_FECHA:
        coincide = patron.match(texto)
        if coincide:
            try:
                return date(int(coincide[a]), int(coincide[m]), int(coincide[d]))
            except ValueError:
                return None
    return None


def monto(texto):
    """Formato chileno: punto de miles, coma decimal, signo peso opcional."""
    texto = (texto or "").strip()
    if not texto:
        return None
    limpio = texto.replace(".", "").replace(",", ".").replace("$", "").replace(" ", "")
    try:
        return Decimal(limpio)
    except InvalidOperation:
        return None


def entero(texto):
    texto = (texto or "").strip()
    try:
        return int(texto) if re.fullmatch(r"[+-]?\d+", texto) else None
    except ValueError:
        return None


def normalizar(texto):
    sin_tildes = unicodedata.normalize("NFD", (texto or "").strip().lower())
    return "".join(c for c in sin_tildes if unicodedata.category(c) != "Mn")


def redondear(valor):
    return valor.quantize(CENTAVOS, rounding=ROUND_HALF_UP)


def filas(archivo):
    """(numero de linea del archivo, campos sin espacios); la cabecera es la linea 1."""
    with open(archivo, encoding="utf-8", newline="") as f:
        lector = csv.reader(f)
        next(lector, None)
        for numero, campos in enumerate(lector, start=2):
            yield numero, [c.strip() for c in campos]


# ----------------------------------------------------------------------------------------------- referencia
def referencia_transacciones(archivo):
    vistos, por_dia, crudo = set(), {}, Counter()
    for _, campos in filas(archivo):
        crudo["filas"] += 1
        if len(campos) != 4:
            crudo["rechazadas"] += 1
            continue
        id_txt, f_txt, m_txt, t_txt = campos
        identificador = entero(id_txt)
        if identificador is None:
            crudo["rechazadas"] += 1
            continue
        if identificador in vistos:
            crudo["duplicadas"] += 1
            continue
        vistos.add(identificador)
        f, m, tipo = fecha(f_txt), monto(m_txt), normalizar(t_txt)
        if f is None or m is None or tipo not in TIPOS_TRANSACCION:
            crudo["rechazadas"] += 1
            continue
        dia = por_dia.setdefault(f, {"cantidad": 0, "debitos": Decimal(0), "creditos": Decimal(0), "maximo": Decimal(0)})
        dia["cantidad"] += 1
        if m > 0:
            dia["debitos" if tipo == "debito" else "creditos"] += m
            dia["maximo"] = max(dia["maximo"], m)
    return {f.isoformat(): (d["cantidad"], redondear(d["debitos"]), redondear(d["creditos"]),
                            redondear(d["creditos"] - d["debitos"]), redondear(d["maximo"]))
            for f, d in por_dia.items()}, crudo


def referencia_intereses(archivo):
    identicas, validas, crudo = set(), [], Counter()
    for linea, campos in filas(archivo):
        crudo["filas"] += 1
        if len(campos) != 5:
            crudo["rechazadas"] += 1
            continue
        clave = ",".join(campos)
        if clave in identicas:
            crudo["duplicadas"] += 1
            continue
        identicas.add(clave)
        cuenta_txt, nombre, saldo_txt, edad_txt, tipo_txt = campos
        cuenta, tipo, saldo, edad = entero(cuenta_txt), normalizar(tipo_txt), monto(saldo_txt), entero(edad_txt)
        if cuenta is None or tipo not in TASAS or saldo is None or saldo < 0 or edad is None \
                or not EDAD_MINIMA <= edad <= EDAD_MAXIMA:
            crudo["rechazadas"] += 1
            continue
        if not nombre or nombre.lower() == "unknown":
            nombre = SIN_IDENTIFICAR
        tasa = TASAS[tipo] + (BONIFICACION_TERCERA_EDAD if tipo == "ahorro" and edad >= EDAD_TERCERA_EDAD else 0)
        interes = redondear(saldo * tasa)
        validas.append({"linea": linea, "cuenta": cuenta, "nombre": nombre, "tipo": tipo, "edad": edad,
                        "saldo": redondear(saldo), "tasa": tasa, "interes": interes,
                        "saldo_final": redondear(saldo + interes)})
    return validas, crudo


def referencia_estados(archivo):
    por_cuenta, crudo = {}, Counter()
    for _, campos in filas(archivo):
        crudo["filas"] += 1
        if len(campos) != 5:
            crudo["rechazadas"] += 1
            continue
        cuenta_txt, f_txt, tipo_txt, m_txt, _ = campos
        cuenta, f, tipo, m = entero(cuenta_txt), fecha(f_txt), normalizar(tipo_txt), monto(m_txt)
        if cuenta is None or f is None or tipo not in TIPOS_MOVIMIENTO or m is None:
            crudo["rechazadas"] += 1
            continue
        if tipo_txt.strip().lower() != tipo:
            crudo["tipos_con_tilde"] += 1
        firmado = -abs(m) if tipo in MOVIMIENTOS_DE_CARGO else abs(m)
        if firmado != m:
            crudo["signos_corregidos"] += 1
        e = por_cuenta.setdefault((cuenta, f.year), {"n": 0, "dep": Decimal(0), "car": Decimal(0), "min": f, "max": f})
        e["n"] += 1
        if firmado >= 0:
            e["dep"] += firmado
        else:
            e["car"] += -firmado
        e["min"], e["max"] = min(e["min"], f), max(e["max"], f)
    return {(c, a): (e["n"], redondear(e["dep"]), redondear(e["car"]), redondear(e["dep"] - e["car"]),
                     e["min"].isoformat(), e["max"].isoformat())
            for (c, a), e in por_cuenta.items()}, crudo


def referencia_cierre(validas):
    canonicas, repeticiones = {}, Counter()
    for fila in sorted(validas, key=lambda v: (v["cuenta"], v["linea"])):
        canonicas.setdefault(fila["cuenta"], fila)
        repeticiones[fila["cuenta"]] += 1
    return {c: (f["nombre"], f["tipo"], f["saldo_final"], repeticiones[c]) for c, f in canonicas.items()}


# ----------------------------------------------------------------------------------------------- batch
def leer_csv(archivo):
    with open(archivo, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def comparar(nombre, esperado, obtenido):
    claves = set(esperado) | set(obtenido)
    distintas = sorted((k for k in claves if esperado.get(k) != obtenido.get(k)), key=str)
    for k in distintas[:5]:
        print(f"    DIFERENCIA {nombre} {k}: legacy={esperado.get(k)} batch={obtenido.get(k)}")
    return len(esperado), len(obtenido), len(claves) - len(distintas), len(distintas)


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    entrada, salida = Path(sys.argv[1]), Path(sys.argv[2])

    ref_dias, crudo_t = referencia_transacciones(entrada / "movimientos_financieros_diarios.csv")
    validas, crudo_i = referencia_intereses(entrada / "intereses_trimestrales.csv")
    ref_estados, crudo_e = referencia_estados(entrada / "estados_financieros_anuales.csv")
    ref_cierre = referencia_cierre(validas)

    batch_dias = {r["fecha"]: (int(r["cantidad_transacciones"]), Decimal(r["total_debitos"]),
                               Decimal(r["total_creditos"]), Decimal(r["saldo_neto"]), Decimal(r["monto_maximo"]))
                  for r in leer_csv(salida / "reporte_transacciones_diarias.csv")}
    # Intereses: una cuenta se repite con datos distintos, asi que se compara la coleccion completa de filas.
    clave_interes = lambda c, n, t, e, s, ta, i, sf: (int(c), n, t, int(e), Decimal(s), Decimal(ta), Decimal(i), Decimal(sf))
    ref_intereses = Counter(clave_interes(v["cuenta"], v["nombre"], v["tipo"], v["edad"], v["saldo"], v["tasa"],
                                          v["interes"], v["saldo_final"]) for v in validas)
    batch_intereses = Counter(clave_interes(r["cuenta_id"], r["nombre"], r["tipo"], r["edad"], r["saldo_inicial"],
                                            r["tasa_mensual"], r["interes"], r["saldo_final"])
                              for r in leer_csv(salida / "intereses_mensuales.csv"))
    batch_estados = {(int(r["cuenta_id"]), int(r["anio"])): (int(r["cantidad_movimientos"]), Decimal(r["total_depositos"]),
                                                            Decimal(r["total_cargos"]), Decimal(r["saldo_neto"]),
                                                            r["primera_fecha"], r["ultima_fecha"])
                     for r in leer_csv(salida / "estados_cuenta_anuales.csv")}
    cierre_batch = leer_csv(salida / "cierre_publicado.csv")
    batch_cierre = {int(r["cuenta_id"]): (r["titular"], r["tipo"], Decimal(r["saldo_final"]), int(r["filas_en_origen"]))
                    for r in cierre_batch}
    # Un mismo titular, un mismo cliente: el id lo asigna el batch, aqui se verifica que sea consistente.
    clientes_por_titular = defaultdict(set)
    for r in cierre_batch:
        clientes_por_titular[r["titular"]].add(r["cliente_id"])
    clientes_inconsistentes = [t for t, ids in clientes_por_titular.items()
                               if len(ids) != 1 or (t == SIN_IDENTIFICAR) != (ids == {""})]

    print("Equivalencia con el proceso legacy (reglas reescritas de forma independiente en Python)")
    print(f"  entrada: {entrada}   salida del batch: {salida}\n")
    print(f"  {'Proceso':<34}{'legacy':>8}{'batch':>8}{'iguales':>9}{'difieren':>10}")
    resultados = [
        ("Reporte de transacciones diarias", comparar("dia", ref_dias, batch_dias)),
        ("Calculo de intereses (filas)", comparar("interes", dict(ref_intereses), dict(batch_intereses))),
        ("Estados de cuenta anuales", comparar("estado", ref_estados, batch_estados)),
        ("Cierre publicado (cuentas)", comparar("cierre", ref_cierre, batch_cierre)),
    ]
    diferencias = 0
    for proceso, (n_ref, n_batch, iguales, distintas) in resultados:
        diferencias += distintas
        print(f"  {proceso:<34}{n_ref:>8}{n_batch:>8}{iguales:>9}{distintas:>10}")
    print(f"  {'Titulares del cierre (un cliente)':<34}{len(clientes_por_titular):>8}{'':>8}"
          f"{len(clientes_por_titular) - len(clientes_inconsistentes):>9}{len(clientes_inconsistentes):>10}")
    diferencias += len(clientes_inconsistentes)

    bruto = sum(m for m in (monto(c[2]) for _, c in filas(entrada / "movimientos_financieros_diarios.csv")
                            if len(c) == 4) if m is not None)
    print("\n  Frente al archivo legacy sin depurar (lo que el proceso antiguo sumaba a ciegas):")
    print(f"    movimientos diarios : {crudo_t['filas']} filas; se migran {sum(v[0] for v in ref_dias.values())}, "
          f"{crudo_t['rechazadas']} quedan fuera por dato sucio y {crudo_t['duplicadas']} por id repetido; "
          f"sumar la columna monto del archivo daba {bruto} (tipos invalidos y montos negativos incluidos); "
          f"el reporte depurado suma {sum(v[1] + v[2] for v in ref_dias.values())} en debitos y creditos validos")
    print(f"    intereses           : {crudo_i['filas']} filas; {len(validas)} liquidadas, {crudo_i['rechazadas']} "
          f"rechazadas y {crudo_i['duplicadas']} identicas; {len(ref_cierre)} cuentas distintas en el cierre "
          f"(el archivo repite una misma cuenta hasta {max(v[3] for v in ref_cierre.values()) if ref_cierre else 0} veces)")
    print(f"    estados anuales     : {crudo_e['filas']} filas; {crudo_e['rechazadas']} rechazadas, "
          f"{crudo_e['signos_corregidos']} signos corregidos y {crudo_e['tipos_con_tilde']} tipos con tilde normalizados")
    print(f"\nResultado: {'EQUIVALENTE' if diferencias == 0 else 'CON DIFERENCIAS'} ({diferencias} diferencias)")
    return 0 if diferencias == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
