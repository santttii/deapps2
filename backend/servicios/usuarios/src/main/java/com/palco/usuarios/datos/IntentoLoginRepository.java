package com.palco.usuarios.datos;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.ejb.Local;

/**
 * Dónde quedan los inicios de sesión fallidos: la tabla {@code intento_login}.
 * Cuántos se permiten y durante cuánto tiempo lo decide
 * {@code ServicioDeUsuarios}; acá solo se guardan y se consultan. El negocio
 * conoce solo esta interfaz: en el servidor la implementa
 * {@link IntentoLoginRepositoryJpa} contra la base, y en los tests una lista
 * en memoria.
 *
 * Los intentos viven en la base y no en un mapa en memoria, así el límite es
 * el mismo con varias instancias del servicio o después de un redespliegue.
 */
@Local
public interface IntentoLoginRepository {

    /** Los momentos de los fallidos posteriores a {@code desde}, del más nuevo al más viejo; como mucho {@code maximo}. */
    List<LocalDateTime> recientes(String emailHash, LocalDateTime desde, int maximo);

    /** Anota un fallido y borra los de ese email anteriores a {@code desde}, que ya no cuentan. */
    void registrar(String emailHash, LocalDateTime momento, LocalDateTime desde);

    /** Después de un login correcto los fallidos anteriores dejan de contar. */
    void olvidar(String emailHash);
}
