package com.bancoxyz.pagos.pago;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioHistorial extends JpaRepository<PasoDelHistorial, Long> {

    List<PasoDelHistorial> findByPagoIdOrderByIdAsc(String pagoId);
}
