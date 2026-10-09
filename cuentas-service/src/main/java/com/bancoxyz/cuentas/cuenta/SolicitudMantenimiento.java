package com.bancoxyz.cuentas.cuenta;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Mantenimiento parcial (PATCH): solo cambia lo que viene. Bloquear impide retiros y pagos sin cerrar la cuenta. */
public record SolicitudMantenimiento(@Size(max = 40) String alias,
                                     @Pattern(regexp = "ACTIVA|BLOQUEADA") String estado) {
}
