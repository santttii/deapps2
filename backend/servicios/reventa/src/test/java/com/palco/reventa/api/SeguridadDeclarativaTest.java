package com.palco.reventa.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.HttpMethod;

import org.junit.jupiter.api.Test;

/**
 * Seguridad declarativa: cada operación HTTP de Reventa dice quién la puede llamar, con
 * {@code @RolesAllowed}, {@code @PermitAll} o {@code @DenyAll} en el método o en la clase. Sin
 * ninguna de las tres, la operación queda abierta a cualquiera, con sesión o sin ella (DEF-43).
 *
 * Los recursos se buscan entre las clases compiladas del servicio: todas las que tienen
 * {@code @Path}. Así un recurso o una operación nuevos entran solos, sin tocar este test. El
 * {@code health} no está: lo trae {@code common} y es público a propósito, para el control de
 * salud de Docker. Cada servicio con recursos tiene una copia de este test, porque los
 * servicios no comparten clases entre sí, tampoco las de prueba.
 */
class SeguridadDeclarativaTest {

    @Test
    void cadaOperacionDiceQuienLaPuedeLlamar() throws Exception {
        List<Class<?>> recursos = recursosDelServicio();
        // Si la búsqueda no encontrara los recursos, el test pasaría sin revisar nada.
        assertTrue(recursos.containsAll(List.of(ReventasResource.class)),
                "recursos encontrados: " + recursos);

        List<String> sinAnotacion = new ArrayList<>();
        for (Class<?> recurso : recursos) {
            List<Method> operaciones = Arrays.stream(recurso.getDeclaredMethods())
                    .filter(SeguridadDeclarativaTest::esOperacionHttp)
                    .toList();
            assertFalse(operaciones.isEmpty(), recurso.getSimpleName() + " no tiene operaciones");
            for (Method operacion : operaciones) {
                if (!diceQuienLaPuedeLlamar(operacion) && !diceQuienLaPuedeLlamar(recurso)) {
                    sinAnotacion.add(recurso.getSimpleName() + "#" + operacion.getName());
                }
            }
        }
        assertEquals(List.of(), sinAnotacion.stream().sorted().toList(),
                "operaciones sin @RolesAllowed, @PermitAll ni @DenyAll");
    }

    /** Un método es una operación si lleva @GET, @POST, @PUT, @DELETE…: todas están marcadas con @HttpMethod. */
    private static boolean esOperacionHttp(Method metodo) {
        return Arrays.stream(metodo.getAnnotations())
                .anyMatch(anotacion -> anotacion.annotationType().isAnnotationPresent(HttpMethod.class));
    }

    private static boolean diceQuienLaPuedeLlamar(AnnotatedElement elemento) {
        return elemento.isAnnotationPresent(RolesAllowed.class)
                || elemento.isAnnotationPresent(PermitAll.class)
                || elemento.isAnnotationPresent(DenyAll.class);
    }

    /** Las clases con {@code @Path} de la carpeta donde quedó compilado el servicio ({@code target/classes}). */
    private static List<Class<?>> recursosDelServicio() throws Exception {
        Path carpeta = Path.of(ReventasResource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> recursos = new ArrayList<>();
        try (Stream<Path> archivos = Files.walk(carpeta)) {
            for (Path archivo : archivos.filter(a -> a.toString().endsWith(".class")).toList()) {
                String ruta = carpeta.relativize(archivo).toString();
                String nombre = ruta.substring(0, ruta.length() - ".class".length()).replace(File.separatorChar, '.');
                // Sin inicializarla: solo se leen sus anotaciones.
                Class<?> clase = Class.forName(nombre, false, SeguridadDeclarativaTest.class.getClassLoader());
                if (clase.isAnnotationPresent(jakarta.ws.rs.Path.class)) {
                    recursos.add(clase);
                }
            }
        }
        return recursos;
    }
}
