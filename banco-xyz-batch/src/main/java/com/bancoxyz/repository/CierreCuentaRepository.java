package com.bancoxyz.repository;

import com.bancoxyz.entity.CierreCuenta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Cuentas del cierre publicado, una por cuenta y ejecucion. */
@Repository
public interface CierreCuentaRepository extends JpaRepository<CierreCuenta, Long> {

    List<CierreCuenta> findByJobExecutionIdOrderByCuentaIdAsc(Long jobExecutionId);

    long countByJobExecutionId(Long jobExecutionId);

    /** Lo usa {@code LimpiezaDeReintentoTasklet}: un cierre a medio publicar no debe quedar mezclado. */
    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
