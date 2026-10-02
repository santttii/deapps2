package com.palco.common.rest;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.json.bind.JsonbException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;

/**
 * Lee y escribe JSON con JSON-B (el estándar de Jakarta EE) en todos los
 * servicios, en lugar del Jackson que trae RESTEasy. Así las fechas salen en
 * ISO, los null se incluyen y un JSON mal formado termina en un 400 con el
 * mismo formato de error que el resto ({@link ErrorRespuesta}).
 */
@Provider
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class JsonProvider implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    private static final Jsonb JSONB = JsonbBuilder.create(new JsonbConfig().withNullValues(true));

    @Override
    public boolean isReadable(Class<?> tipo, Type generico, Annotation[] anotaciones, MediaType mediaType) {
        return esJson(tipo);
    }

    @Override
    public Object readFrom(Class<Object> tipo, Type generico, Annotation[] anotaciones, MediaType mediaType,
            MultivaluedMap<String, String> headers, InputStream entrada) {
        try {
            return JSONB.fromJson(entrada, generico);
        } catch (JsonbException e) {
            throw new BadRequestException("El cuerpo de la solicitud no es un JSON válido.", e);
        }
    }

    @Override
    public boolean isWriteable(Class<?> tipo, Type generico, Annotation[] anotaciones, MediaType mediaType) {
        return esJson(tipo);
    }

    @Override
    public void writeTo(Object valor, Class<?> tipo, Type generico, Annotation[] anotaciones, MediaType mediaType,
            MultivaluedMap<String, Object> headers, OutputStream salida) {
        JSONB.toJson(valor, generico, salida);
    }

    /** Strings y bytes los maneja RESTEasy tal cual. */
    private static boolean esJson(Class<?> tipo) {
        return tipo != String.class && tipo != byte[].class && !InputStream.class.isAssignableFrom(tipo);
    }
}
