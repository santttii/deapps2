package com.palco.ventas.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import jakarta.annotation.security.RolesAllowed;

import org.junit.jupiter.api.Test;

/**
 * Las operaciones de reventa de Ventas son internas: cambian el estado de una
 * entrada o la transfieren a otro comprador, y el vendedor y el comprador
 * viajan en el cuerpo. Si una aceptara el token de una persona, esa persona
 * podría pedirlas a nombre de otra.
 */
class ReventaDeEntradasResourceTest {

    @Test
    void todasLasOperacionesSonSoloParaOtrosServicios() {
        Method[] operaciones = Arrays.stream(ReventaDeEntradasResource.class.getDeclaredMethods())
                .filter(metodo -> Modifier.isPublic(metodo.getModifiers()))
                .toArray(Method[]::new);
        // Consultar, publicar, retirar, controlar la compra y transferir.
        assertTrue(operaciones.length >= 5, "operaciones: " + operaciones.length);

        for (Method operacion : operaciones) {
            RolesAllowed roles = operacion.getAnnotation(RolesAllowed.class);
            assertNotNull(roles, operacion.getName() + " no declara quién la puede llamar");
            assertArrayEquals(new String[] { "servicio" }, roles.value(), operacion.getName());
        }
    }

    @Test
    void elControlDeCompraExisteYEsInterno() throws NoSuchMethodException {
        RolesAllowed roles = ReventaDeEntradasResource.class
                .getMethod("controlarCompra", DtosDeReventa.ControlDeCompraDto.class)
                .getAnnotation(RolesAllowed.class);

        assertNotNull(roles, "controlarCompra no declara quién la puede llamar");
        assertArrayEquals(new String[] { "servicio" }, roles.value());
    }
}
