package com.palco.common.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.ws.rs.NameBinding;

/**
 * Marca un GET para que su respuesta lleve {@code ETag} y responda {@code 304 Not Modified} si
 * el cliente manda ese mismo valor en {@code If-None-Match}. Lo aplica {@link FiltroDeEtag}.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.TYPE, ElementType.METHOD })
public @interface ConEtag {
}
