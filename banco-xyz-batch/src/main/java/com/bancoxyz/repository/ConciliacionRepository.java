package com.bancoxyz.repository;

import com.bancoxyz.entity.Conciliacion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Totales de control por archivo y ejecucion. */
@Repository
public interface ConciliacionRepository extends JpaRepository<Conciliacion, Long> {

    List<Conciliacion> findByJobExecutionIdOrderByIdAsc(Long jobExecutionId);

    long deleteByJobExecutionIdIn(java.util.Collection<Long> jobExecutionIds);
}
