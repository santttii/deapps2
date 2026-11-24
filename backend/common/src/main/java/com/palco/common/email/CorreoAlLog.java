package com.palco.common.email;

import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import com.palco.common.Textos;

/**
 * Escribe el email en el log del servidor en lugar de mandarlo. Es lo que se
 * usa cuando no hay servidor SMTP.
 *
 * Por defecto el log no lleva datos personales ni códigos. El renglón dice a
 * quién iba, con la dirección enmascarada ({@link Email#enmascarar}), y el
 * asunto, que es un texto fijo de Palco o el título de un evento. El cuerpo no
 * se escribe: lleva el nombre de quien lo recibe, los de los titulares y los
 * códigos de verificación y de reinicio de contraseña.
 *
 * <pre>Email para a***@palco.test (Tu código de Palco), sin el cuerpo: lo escribe PALCO_CORREO_LOG_COMPLETO=true.</pre>
 *
 * Con la variable de entorno {@code PALCO_CORREO_LOG_COMPLETO=true} escribe el
 * email entero, con la dirección tal cual:
 *
 * <pre>Email para ana@palco.test (Tu código de Palco): Hola Ana: tu código para verificar el email es 123456. ...</pre>
 *
 * Es un modo de desarrollo y hay que pedirlo: sin SMTP el log es la única
 * bandeja de entrada, y sin el código no se puede verificar una cuenta nueva.
 * Lo prende {@code backend/dev.sh}. El {@code docker-compose.yml} lo deja
 * apagado por defecto y lo prende solo si se lo levanta con la variable en
 * {@code true}, como hacen los jobs del CI que lo necesitan: las pruebas E2E,
 * {@code scripts/pruebas} y los "Pasos manuales" de Postman sacan el código de
 * ese renglón, y su formato no cambió. En un despliegue no se define: ahí los emails salen por
 * SMTP ({@link CorreoSmtp}) y, si faltara el servidor, el log queda como en el
 * primer ejemplo. Cualquier valor que no sea {@code true} (en mayúsculas o
 * minúsculas) cuenta como apagado, para que un error de tipeo no deje datos
 * personales en el log.
 *
 * Sale todo en un solo renglón ({@link Textos#enUnaLinea}): el asunto y el
 * cuerpo llevan datos que escribió alguien (un nombre de titular, por ejemplo),
 * y con un salto de línea adentro aparecería un renglón falso en el log.
 *
 * {@code @ApplicationScoped}: no guarda nada de cada email, y el modo se lee una
 * vez; una instancia para todo el servicio. {@code @Typed} hace que solo se
 * pueda inyectar por su clase y no como {@link Correos}: ese lo entrega
 * {@link FabricaDeCorreos}.
 */
@ApplicationScoped
@Typed(CorreoAlLog.class)
public class CorreoAlLog implements Correos {

    /** El valor de {@code PALCO_CORREO} que elige esta implementación. */
    public static final String NOMBRE = "log";

    /** La variable que prende el modo de desarrollo, con el email entero en el log. */
    static final String VARIABLE_COMPLETO = "PALCO_CORREO_LOG_COMPLETO";

    private static final Logger LOG = Logger.getLogger(CorreoAlLog.class.getName());

    /** No es privado para que un test lo fije sin variables de entorno. */
    boolean completo = Boolean.parseBoolean(System.getenv(VARIABLE_COMPLETO));

    @Override
    public void enviar(Email email) {
        LOG.info(renglon(email));
    }

    /** Lo que queda en el log: ver la nota de la clase. */
    String renglon(Email email) {
        if (completo) {
            return Textos.enUnaLinea("Email para " + email.para() + " (" + email.asunto() + "): " + email.cuerpo());
        }
        return Textos.enUnaLinea("Email para " + email.paraEnmascarado() + " (" + email.asunto()
                + "), sin el cuerpo: lo escribe " + VARIABLE_COMPLETO + "=true.");
    }
}
