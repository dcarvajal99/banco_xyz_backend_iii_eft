package com.bancoxyz.pagos.pago;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RepositorioPagos extends JpaRepository<Pago, String> {

    Optional<Pago> findByClienteIdAndClaveIdempotencia(long clienteId, String claveIdempotencia);

    List<Pago> findTop20ByClienteIdOrderByCreadoEnDesc(long clienteId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Pago p where p.id = :id")
    Optional<Pago> buscarParaActualizar(@Param("id") String id);
}
