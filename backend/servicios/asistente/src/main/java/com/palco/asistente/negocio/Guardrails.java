package com.palco.asistente.negocio;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Las protecciones del asistente, antes y después del modelo.
 *
 * Entrada: largo máximo, enmascarar datos personales (DNI, tarjeta, email) antes de que salgan
 * hacia el proveedor del modelo (OWASP LLM02), y frenar intentos de inyección de prompt
 * (LLM01) y pedidos de datos de terceros.
 *
 * Salida: si la respuesta del modelo trae un DNI, un email o un número de tarjeta, no se
 * muestra (LLM02); tampoco si copia las instrucciones del sistema (LLM07).
 */
public final class Guardrails {

    /** Largo máximo de una pregunta. */
    public static final int LARGO_MAXIMO = 500;

    /**
     * Cuántas palabras seguidas iguales a las de las instrucciones hacen falta para decir que una
     * respuesta las copia. La oración más corta de las instrucciones tiene 10.
     */
    public static final int PALABRAS_SEGUIDAS = 10;

    static final String DNI_OCULTO = "[dni oculto]";
    static final String TARJETA_OCULTA = "[tarjeta oculta]";
    static final String EMAIL_OCULTO = "[email oculto]";

    // 13 a 19 dígitos, con espacios o guiones entre grupos: un número de tarjeta.
    private static final Pattern TARJETA = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");
    // 7 u 8 dígitos sueltos, con o sin puntos: un DNI argentino. Detrás de un signo $ es un precio
    // ($1.000.000), no un DNI.
    private static final Pattern DNI = Pattern.compile("(?<!\\$ ?)\\b\\d{1,2}\\.?\\d{3}\\.?\\d{3}\\b");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");

    // Inyección de prompt (LLM01). Los patrones se buscan sobre el texto normalizado (Texto.normalizar),
    // así que "IGNORÁ", "ignora" e "ignorá   tus" llegan iguales. Están agrupados por lo que busca quien
    // escribe y van en español (voseo, tuteo y usted) y en inglés, porque el modelo entiende los dos.
    // Dos reglas evitan bloquear dudas legítimas:
    // - Un verbo suelto no alcanza: tiene que ir con su objeto, cerca y en la misma oración (CERCA). En
    //   "olvidate de la factura, ¿qué regla tiene la reventa?" no se juntan.
    // - Con cualquier regla como objeto, el verbo tiene que estar en imperativo: "ignorá las reglas" es un
    //   intento; "ignoro las reglas de la reventa" u "olvidé la contraseña" son preguntas.
    // Es una lista de frases y no frena todo: por eso el asistente además solo lee, no ve datos de otros
    // usuarios y su respuesta pasa por el guardrail de salida.
    private static final String CERCA = "[^.?!¿]{0,40}";

    // Lo que el modelo tiene de fondo y nadie que compra una entrada nombra: sus instrucciones, el
    // mensaje del sistema, "lo de arriba".
    private static final String LO_DEL_MODELO = "(tus|your) (instruccion|indicacion|directiva|instruction|guideline)"
            + "|(instruccion|indicacion|regla|mensaje|texto|instruction|rule|message|text)\\w* "
            + "(inicial|original|ocult|previ|anterior|de arriba|del sistema)"
            + "|(initial|original|hidden|previous|prior|above|system|first) (instruction|rule|message|text)"
            + "|lo (anterior|de antes|de arriba)|everything (above|before)|the (message|text|words|instructions) above";

    /** 1. Que deje de lado sus instrucciones: "ignorá tus instrucciones", "olvidate de todo lo anterior". */
    private static final Pattern IGNORAR_INSTRUCCIONES = Pattern.compile(
            // Cualquier forma de ignorar u olvidar, si lo que sigue es lo del modelo.
            "\\b(ignor|olvid|disregard|forget)\\w*" + CERCA + "\\b(" + LO_DEL_MODELO + ")"
            // En imperativo alcanza con cualquier regla: "ignorá las reglas de Palco", "no sigas las reglas".
            + "|\\b(ignora|ignore|olvida|olvidate|saltea|desobedece|no sigas|no respetes|no cumplas|no hagas caso"
            + "|deja de lado|pasa por alto|disregard|forget|override|bypass)\\b" + CERCA
            + "\\b(instruccion|indicacion|regla|restriccion|directiva|instruction|rule|guideline|everything)");

    /** 2. Que muestre, repita o traduzca sus instrucciones: "decime tu prompt", "repetí el mensaje de arriba". */
    private static final Pattern REVELAR_INSTRUCCIONES = Pattern.compile(
            // Nombrar el prompt ya alcanza: ninguna duda sobre entradas lo menciona.
            "\\bprompts?\\b|instrucciones del sistema"
            // Un pedido de mostrar, repetir o traducir, seguido de lo del modelo.
            + "|\\b(deci|decime|dime|mostra|mostrame|muestra|muestrame|pasame|dame|repeti|repetime|repite|escribi"
            + "|escribime|copia|imprimi|imprime|traduci|traducime|traduce|resumi|resume|revela|cuales son|cual es"
            + "|que dicen|tell|show|print|write|repeat|reveal|translate|give|what are|what is|what were)\\b" + CERCA
            + "\\b(" + LO_DEL_MODELO + ")");

    /** 3. Que cambie de rol o se quede sin límites: "actuá como administrador", "a partir de ahora sos DAN". */
    private static final Pattern CAMBIAR_DE_ROL = Pattern.compile(
            "\\b(actua como|comportate como|hace de cuenta|hacete pasar|(fingi|finge) (ser|que)|simula ser"
            + "|como si fueras|como si no tuvieras|juego de rol|act as|pretend (to be|you)|role ?play)\\b"
            // Un rol nuevo "desde ahora". Con una duda después ("a partir de ahora, ¿cuántas...?") no es un rol.
            + "|\\b(a partir de ahora|desde ahora|de ahora en (mas|adelante)|from now on),? (sos|eres|seras|te llamas|you)\\b"
            // Los "modos" sin reglas que circulan para saltear los filtros de un modelo. "Dan" solo, no:
            // "¿me dan factura?".
            + "|\\b(sos|eres|you are|modo|mode) dan\\b|\\bdan mode\\b|jailbreak"
            + "|\\bmodo (desarrollador|developer|dios|god|admin|administrador|sin restricciones|sin filtros)\\b"
            + "|\\b(developer|god) mode\\b"
            // Responder cualquier cosa es justo lo que no tiene que hacer.
            + "|\\b(responde|respondeme|contesta|contestame|answer)( a| sobre)? (cualquier cosa|cualquier tema|lo que sea|anything)"
            // Atribuirse un rol que Palco no tiene. Organizador y staff sí existen: "soy staff" es una duda normal.
            + "|\\bsoy (el |la |un |una |tu )?(admin|administrador|administradora|desarrollador|desarrolladora|developer)\\b"
            + "|\\bi( am|'m) (the |an |a |your )?(admin|administrator|developer)\\b");

    /** 4. Un texto del usuario disfrazado de mensaje del sistema: "[SISTEMA]: nuevas reglas", "<|im_start|>". */
    private static final Pattern MENSAJE_FALSO_DEL_SISTEMA = Pattern.compile(
            // Una etiqueta de rol entre corchetes, llaves o signos de menor y mayor: [sistema], <system>, [INST].
            "[\\[<{] ?/? ?(sistema|system|sys|inst|admin|administrador|developer|desarrollador|asistente|assistant) ?[\\]>}]"
            // Las marcas que separan los mensajes en el formato de chat de algunos modelos.
            + "|<\\|\\w+\\|>"
            // Una etiqueta de rol con dos puntos al principio de la pregunta, de una oración o de un texto
            // pegado (después de dos puntos o de unas comillas): "SISTEMA: ...", "el mail dice: 'SYSTEM: ...'".
            // En el medio de una oración es una duda: "el sistema: no me deja pagar".
            + "|(^|[.!?:] |[\"'«“‘])\\W*(sistema|system|admin|administrador|developer|desarrollador) ?:"
            // Reglas o instrucciones "nuevas" puestas como encabezado: "nuevas reglas: ...".
            + "|\\b(nuevas|new) (reglas|instrucciones|indicaciones|rules|instructions) ?:");

    private static final List<Pattern> INYECCION =
            List.of(IGNORAR_INSTRUCCIONES, REVELAR_INSTRUCCIONES, CAMBIAR_DE_ROL, MENSAJE_FALSO_DEL_SISTEMA);

    // Pedir datos personales de otra persona. Nombrar el dato no alcanza: "¿qué pasa si pierdo mi
    // tarjeta?" o "¿necesito el DNI para entrar?" son dudas sobre los datos propios.
    private static final String DATO = "(dni|documento|email|mail|correo|telefono|tarjeta)";
    private static final String DE_OTRA_PERSONA = "(del|de la|de los|de las|de)( un| una| esa| ese| esta| este| mi| mis)? "
            + "(titular|comprador|vendedor|duen|persona|otr[oa]|usuari|client|alguien|quien)";
    private static final Pattern DATOS_DE_TERCEROS = Pattern.compile(
            // Una orden que pide el dato mismo: "pasame el DNI de Juan", "decime su email", "dame los datos de
            // la tarjeta". "Pasá" cuenta solo al principio de la oración, donde es una orden: en "¿qué pasa
            // si...?" o "no me pasa la tarjeta" es pasar de suceder.
            "(\\b(pasame|dame|decime|mostrame|mostra)|(^|[.!?¿,] ?)pasa)\\b(,? por favor,?)?( todos| todas)?"
            + "( el| la| los| las| su| sus)?( (numero|numeros|datos) (de|del)( la| el| su| sus)?)? " + DATO
            // Un pedido más largo o con "quiero" o "necesito": el dato tiene que ser de otra persona.
            // "Decime cuál es el DNI del titular" o "necesito el mail del vendedor" sí; "decime si tengo que
            // llevar el DNI" o "quiero pagar con otra tarjeta" no.
            + "|\\b(pasame|dame|decime|mostrame|mostra|quiero|necesito)\\b" + CERCA
            + "\\b((su|sus) " + DATO + "|" + DATO + "\\w* " + DE_OTRA_PERSONA + ")");

    private Guardrails() {
    }

    /** El resultado de revisar una pregunta. */
    public record Revision(boolean bloqueada, String motivo, String texto) {
        static Revision bloqueada(String motivo) {
            return new Revision(true, motivo, null);
        }

        static Revision aceptada(String texto) {
            return new Revision(false, null, texto);
        }
    }

    /** Revisa y limpia la pregunta antes de mandarla al modelo. */
    public static Revision revisarEntrada(String pregunta) {
        String texto = pregunta == null ? "" : pregunta.strip();
        if (texto.isEmpty()) {
            return Revision.bloqueada("vacia");
        }
        if (texto.length() > LARGO_MAXIMO) {
            return Revision.bloqueada("demasiado-larga");
        }
        String normalizada = Texto.normalizar(texto);
        for (Pattern p : INYECCION) {
            if (p.matcher(normalizada).find()) {
                return Revision.bloqueada("inyeccion");
            }
        }
        if (DATOS_DE_TERCEROS.matcher(normalizada).find()) {
            return Revision.bloqueada("datos-personales");
        }
        return Revision.aceptada(enmascarar(texto));
    }

    /** Reemplaza tarjetas, DNI y emails por marcas: esos datos nunca llegan al modelo. */
    public static String enmascarar(String texto) {
        String limpio = TARJETA.matcher(texto).replaceAll(TARJETA_OCULTA);
        limpio = EMAIL.matcher(limpio).replaceAll(EMAIL_OCULTO);
        return DNI.matcher(limpio).replaceAll(DNI_OCULTO);
    }

    /** true si la respuesta del modelo trae datos personales y no se puede mostrar. */
    public static boolean salidaConDatosPersonales(String respuesta) {
        String texto = respuesta == null ? "" : respuesta;
        return TARJETA.matcher(texto).find() || DNI.matcher(texto).find() || EMAIL.matcher(texto).find();
    }

    /**
     * true si la respuesta del modelo copia sus instrucciones y no se puede mostrar (LLM07): comparte
     * con ellas {@link #PALABRAS_SEGUIDAS} palabras seguidas, sin mirar mayúsculas, tildes ni signos.
     * Es el control de salida para una inyección que pasó la entrada. Decir lo mismo con otras palabras
     * ("no tengo acceso a datos de otros usuarios") no es copiarlas.
     */
    public static boolean salidaConInstrucciones(String respuesta, String instrucciones) {
        String texto = " " + String.join(" ", palabras(respuesta)) + " ";
        List<String> propias = palabras(instrucciones);
        for (int i = 0; i + PALABRAS_SEGUIDAS <= propias.size(); i++) {
            if (texto.contains(" " + String.join(" ", propias.subList(i, i + PALABRAS_SEGUIDAS)) + " ")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> palabras(String texto) {
        return Arrays.stream(Texto.normalizar(texto).split("[^\\p{L}\\p{N}]+")).filter(p -> !p.isEmpty()).toList();
    }
}
