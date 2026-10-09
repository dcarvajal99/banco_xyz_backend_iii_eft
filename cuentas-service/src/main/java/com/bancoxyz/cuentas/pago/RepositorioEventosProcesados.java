package com.bancoxyz.cuentas.pago;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioEventosProcesados extends JpaRepository<EventoProcesado, String> {
}
