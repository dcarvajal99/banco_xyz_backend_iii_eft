package com.bancoxyz.repository;

import com.bancoxyz.entity.ClienteMigrado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Maestro de clientes con ids estables. */
@Repository
public interface ClienteMigradoRepository extends JpaRepository<ClienteMigrado, Long> {

    Optional<ClienteMigrado> findByNombre(String nombre);

    @Query("select coalesce(max(c.id), 0) from ClienteMigrado c")
    long mayorId();
}
