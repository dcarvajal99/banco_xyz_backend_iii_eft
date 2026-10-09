package com.bancoxyz.clientes.autenticacion;

import com.bancoxyz.clientes.config.PropiedadesClientes;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hash de las claves de usuario (BCrypt).
 *
 * <p>No es un bean {@code PasswordEncoder} a proposito: el unico de ese tipo es el de la credencial de servicio de
 * banco-auth (costo 4), y dos beans del mismo tipo confundirian a la autoconfiguracion de Spring Security.</p>
 */
@Component
public class CodificadorDeSecretos {

    private final BCryptPasswordEncoder bcrypt;
    private final String hashFicticio;

    public CodificadorDeSecretos(PropiedadesClientes propiedades) {
        this.bcrypt = new BCryptPasswordEncoder(propiedades.seguridad().bcryptFuerza());
        this.hashFicticio = bcrypt.encode("valor-que-nunca-coincide");
    }

    public String cifrar(String secreto) {
        return bcrypt.encode(secreto);
    }

    public boolean coincide(String secreto, String hash) {
        return secreto != null && hash != null && bcrypt.matches(secreto, hash);
    }

    /** Gasta lo mismo que una verificacion real para no revelar por tiempo que el usuario no existe. */
    public void verificarContraFicticio(String secreto) {
        bcrypt.matches(secreto == null ? "" : secreto, hashFicticio);
    }
}
