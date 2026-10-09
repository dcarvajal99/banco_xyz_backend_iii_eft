package com.bancoxyz.clientes.cliente;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RepositorioClientes extends JpaRepository<Cliente, Long> {

    boolean existsByNombreIgnoreCase(String nombre);

    List<Cliente> findAllByOrderByNombreAsc();
}
