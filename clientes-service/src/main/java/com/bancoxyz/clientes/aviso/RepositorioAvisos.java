package com.bancoxyz.clientes.aviso;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioAvisos extends JpaRepository<Aviso, Long> {

    boolean existsByEventoId(String eventoId);

    List<Aviso> findTop30ByClienteIdOrderByIdDesc(long clienteId);
}
