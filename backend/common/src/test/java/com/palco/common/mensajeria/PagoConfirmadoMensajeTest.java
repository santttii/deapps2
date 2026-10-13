package com.palco.common.mensajeria;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import jakarta.json.Json;

import org.junit.jupiter.api.Test;

import com.palco.common.ReglasDeNegocio;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Entrada;
import com.palco.common.mensajeria.PagoConfirmadoMensaje.Titular;

class PagoConfirmadoMensajeTest {

    /** El contrato escrito del mensaje. Los tests corren parados en backend/common. */
    private static final Path CONTRATO = Path.of("..", "..", "docs", "superpowers", "specs",
            "mensajeria-pago-confirmado.md");

    private static final Instant CONFIRMADA = Instant.parse("2026-10-16T21:30:00Z");
    private static final Titular ANA = new Titular("Ana Gómez", "30123456");
    private static final Titular LUIS = new Titular("Luis Pérez", "31234567");

    /** El mismo mensaje que el ejemplo del contrato, armado como lo arma Ventas. */
    private static PagoConfirmadoMensaje ejemplo() {
        return conEntradas(List.of(
                new Entrada("T-9c41d07e", "Platea B", ANA),
                new Entrada("T-51b7a2f0", "Platea B", LUIS)));
    }

    private static PagoConfirmadoMensaje conEntradas(List<Entrada> entradas) {
        return new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1", entradas, 60500, CONFIRMADA);
    }

    private static PagoConfirmadoMensaje conTitular(Titular titular) {
        return conEntradas(List.of(new Entrada("T-9c41d07e", "Platea B", titular)));
    }

    /** El bloque ```json del contrato. */
    private static String ejemploDelContrato() throws IOException {
        assertTrue(Files.exists(CONTRATO), "No se encontró el contrato en " + CONTRATO.toAbsolutePath());
        String texto = Files.readString(CONTRATO);
        int desde = texto.indexOf("```json") + "```json".length();
        return texto.substring(desde, texto.indexOf("```", desde));
    }

    /** El mismo JSON sin espacios ni saltos de línea, con los campos en el orden en que están escritos. */
    private static String sinEspacios(String json) {
        return Json.createReader(new StringReader(json)).readValue().toString();
    }

    private static void assertInvalido(PagoConfirmadoMensaje mensaje) {
        assertThrows(MensajeInvalido.class, mensaje::validar);
    }

    // --- El ejemplo del contrato ---------------------------------------------------------------

    @Test
    void leeElEjemploDelContrato() throws IOException {
        assertEquals(ejemplo(), PagoConfirmadoMensaje.desdeJson(ejemploDelContrato()));
    }

    @Test
    void escribeElEjemploDelContratoCampoPorCampo() throws IOException {
        assertEquals(sinEspacios(ejemploDelContrato()), ejemplo().aJson());
    }

    @Test
    void loQueSeEscribeSeVuelveALeerIgual() {
        assertEquals(ejemplo(), PagoConfirmadoMensaje.desdeJson(ejemplo().aJson()));
    }

    // --- Tolerancias al leer -------------------------------------------------------------------

    @Test
    void unTimestampConOtraZonaEsElMismoInstanteYSeEscribeEnUtc() throws IOException {
        String conZonaArgentina = ejemploDelContrato().replace("2026-10-16T21:30:00Z", "2026-10-16T18:30:00-03:00");

        PagoConfirmadoMensaje mensaje = PagoConfirmadoMensaje.desdeJson(conZonaArgentina);

        assertEquals(CONFIRMADA, mensaje.timestamp());
        assertTrue(mensaje.aJson().contains("\"timestamp\":\"2026-10-16T21:30:00Z\""));
    }

    @Test
    void unCampoDesconocidoSeIgnora() throws IOException {
        String conCampoNuevo = ejemploDelContrato().replace("\"eventoId\"", "\"canal\": \"web\", \"eventoId\"");

        assertEquals(ejemplo(), PagoConfirmadoMensaje.desdeJson(conCampoNuevo));
    }

    // --- Textos que no son un mensaje ----------------------------------------------------------

    @Test
    void unTextoNuloVacioNullOQueNoEsJsonEsInvalido() {
        for (String texto : Arrays.asList(null, "", "   ", "null", "esto no es json", "{", "[1, 2]", "\"hola\"", "42")) {
            assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(texto), "Texto: " + texto);
        }
    }

    @Test
    void unJsonCortadoDentroDeUnCampoDesconocidoEsInvalidoYNoDejaColgadoAlQueLee() {
        // Con estos textos JSON-B, solo, no lanza error ni termina: el consumidor se quedaría
        // girando para siempre, sin reintentos ni DLQ. Por eso el test tiene un tiempo máximo.
        String cortadoEnUnCampoExtra = ejemplo().aJson().replaceFirst("\\}$", ",\"extra\":{\"x\":1");

        for (String texto : List.of("{\"a\":{", "{\"a\":[", cortadoEnUnCampoExtra)) {
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(texto)),
                    "Texto: " + texto);
        }
    }

    @Test
    void unJsonConOtraFormaEsInvalido() {
        // JSON-B no se queja por los campos que faltan: los deja en null y en 0. Lo detecta validar().
        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson("{}"));
        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson("{\"ventaId\": \"V-3f9a1c2b\"}"));
    }

    @Test
    void unTimestampQueNoEsUnaFechaEsInvalido() throws IOException {
        String conFechaRota = ejemploDelContrato().replace("2026-10-16T21:30:00Z", "ayer a la noche");

        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(conFechaRota));
    }

    @Test
    void alLeerTambienSeValida() throws IOException {
        String sinVenta = ejemploDelContrato().replace("\"V-3f9a1c2b\"", "\"\"");
        String conDniCorto = ejemploDelContrato().replace("30123456", "123");
        String conEntradaNula = "{\"ventaId\":\"V-3f9a1c2b\",\"compradorId\":\"user-titular\",\"eventoId\":\"ev-1\","
                + "\"entradas\":[null],\"montoTotal\":60500,\"timestamp\":\"2026-10-16T21:30:00Z\"}";

        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(sinVenta));
        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(conDniCorto));
        assertThrows(MensajeInvalido.class, () -> PagoConfirmadoMensaje.desdeJson(conEntradaNula));
    }

    // --- validar(): lo que también frena al que publica ----------------------------------------

    @Test
    void elEjemploEsValido() {
        assertDoesNotThrow(() -> ejemplo().validar());
    }

    @Test
    void losIdsSonObligatoriosYDeHasta40Caracteres() {
        List<Entrada> entradas = ejemplo().entradas();
        String largo = "V-" + "x".repeat(39); // 41 caracteres

        assertInvalido(new PagoConfirmadoMensaje(null, "user-titular", "ev-1", entradas, 60500, CONFIRMADA));
        assertInvalido(new PagoConfirmadoMensaje(" ", "user-titular", "ev-1", entradas, 60500, CONFIRMADA));
        assertInvalido(new PagoConfirmadoMensaje(largo, "user-titular", "ev-1", entradas, 60500, CONFIRMADA));
        assertInvalido(new PagoConfirmadoMensaje("V-3f9a1c2b", null, "ev-1", entradas, 60500, CONFIRMADA));
        assertInvalido(new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "", entradas, 60500, CONFIRMADA));
        assertInvalido(conEntradas(List.of(new Entrada(null, "Platea B", ANA))));
        assertInvalido(conEntradas(List.of(new Entrada(largo, "Platea B", ANA))));

        String justo = "V-" + "x".repeat(38); // 40 caracteres
        assertDoesNotThrow(() -> new PagoConfirmadoMensaje(justo, "user-titular", "ev-1", entradas, 60500, CONFIRMADA)
                .validar());
    }

    @Test
    void tieneQueTraerEntreUnaYElMaximoDeEntradas() {
        Entrada entrada = new Entrada("T-9c41d07e", "Platea B", ANA);

        assertInvalido(conEntradas(null));
        assertInvalido(conEntradas(List.of()));
        assertInvalido(conEntradas(Collections.nCopies(ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN + 1, entrada)));
        assertDoesNotThrow(() -> conEntradas(Collections.nCopies(ReglasDeNegocio.MAX_ENTRADAS_POR_ORDEN, entrada))
                .validar());
    }

    @Test
    void ningunaEntradaPuedeSerNula() {
        List<Entrada> conUnaNula = new ArrayList<>(ejemplo().entradas());
        conUnaNula.add(null);

        assertInvalido(conEntradas(conUnaNula));
    }

    @Test
    void cadaTitularLlevaNombreYUnDniDe7U8Digitos() {
        assertInvalido(conTitular(null));
        assertInvalido(conTitular(new Titular(null, "30123456")));
        assertInvalido(conTitular(new Titular("  ", "30123456")));
        assertInvalido(conTitular(new Titular("Ana Gómez", null)));
        assertInvalido(conTitular(new Titular("Ana Gómez", "123456")));     // 6 dígitos
        assertInvalido(conTitular(new Titular("Ana Gómez", "123456789")));  // 9 dígitos
        assertInvalido(conTitular(new Titular("Ana Gómez", "30.123.456"))); // con puntos
        assertInvalido(conTitular(new Titular("Ana Gómez", "3012345A")));

        assertDoesNotThrow(() -> conTitular(new Titular("Ana Gómez", "3012345")).validar()); // 7 dígitos
    }

    @Test
    void elMontoEsPositivoYElTimestampObligatorio() {
        List<Entrada> entradas = ejemplo().entradas();

        assertInvalido(new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1", entradas, 0, CONFIRMADA));
        assertInvalido(new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1", entradas, -1, CONFIRMADA));
        assertInvalido(new PagoConfirmadoMensaje("V-3f9a1c2b", "user-titular", "ev-1", entradas, 60500, null));
    }
}
