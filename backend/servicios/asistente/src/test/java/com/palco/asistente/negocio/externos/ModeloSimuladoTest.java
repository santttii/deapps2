package com.palco.asistente.negocio.externos;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.palco.asistente.negocio.Texto;

/**
 * Respuestas del modelo simulado que el set de evaluación no cubre, y los mismos datos en lo que
 * recibe el modelo real: las reglas entre compras, dónde
 * está la factura y dos preguntas que antes caían en la respuesta equivocada ("máximo de entradas"
 * contestaba lo de la reventa y "cancelar mi compra" contestaba lo de un evento cancelado).
 */
class ModeloSimuladoTest {

    private final ModeloSimulado modelo = new ModeloSimulado();

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', textBlock = """
            ¿Cuántas entradas puedo comprar?                 | 6 entradas por compra | 6 por evento | una sola entrada por evento
            ¿Cuál es el máximo de entradas?                  | 6 entradas por compra | 6 por evento | dni
            ¿Puedo cancelar mi compra?                       | mientras no la pagaste | cancelar compra | reventa
            ¿Puedo cancelar la compra del evento del sábado? | mientras no la pagaste | cancelar compra | reventa
            Se canceló el evento, ¿me devuelven la plata?    | se anula | devuelve | email
            Lo cancelaron, ¿qué pasa con mi entrada?         | se anula | devuelve | email
            ¿Dónde veo la factura?                           | mis entradas | ver factura | cae
            ¿A cuánto la puedo vender como máximo?           | precio original | 10 % | redondeado
            """)
    void respondeLoQueCorresponde(String pregunta, String a, String b, String c) {
        String respuesta = Texto.normalizar(modelo.completar("", pregunta));
        for (String esperado : new String[] { a, b, c }) {
            assertTrue(respuesta.contains(Texto.normalizar(esperado.strip())),
                    "«" + pregunta + "» respondió «" + respuesta + "» y le falta «" + esperado.strip() + "»");
        }
    }

    @Test
    void losDatosDelModeloRealDicenLoMismo() {
        String datos = ModeloCompatibleOpenAI.datosDePalco();
        assertTrue(datos.contains("Cada DNI puede tener una sola entrada por evento"), datos);
        assertTrue(datos.contains("Una compra se cancela solo mientras no se pagó"), datos);
        assertTrue(datos.contains("Mis entradas, con el botón Ver factura"), datos);
    }
}
