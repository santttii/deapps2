package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import jakarta.ejb.ConcurrentAccessException;
import jakarta.ejb.EJBException;
import jakarta.ejb.NoSuchEJBException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.ventas.api.Dtos.EntradaDto;
import com.palco.ventas.api.Dtos.NuevaVentaDto;
import com.palco.ventas.api.Dtos.NuevoPagoDto;
import com.palco.ventas.api.Dtos.VentaDto;
import com.palco.ventas.datos.EntradaRepository;
import com.palco.ventas.datos.VentaRepository;
import com.palco.ventas.dominio.EstadoVenta;
import com.palco.ventas.dominio.Venta;
import com.palco.ventas.dominio.VentaItem;
import com.palco.ventas.negocio.externos.ClienteDeEventos;
import com.palco.ventas.negocio.externos.ClienteDeUsuarios;

/**
 * La puerta de entrada a las compras: los recursos REST hablan solo con esta
 * clase. Hace tres cosas.
 *
 * Es el índice de las compras abiertas. Cada compra tiene su instancia de
 * {@link ServicioDeVentas} (stateful). El recurso REST no puede guardarla,
 * porque se crea de nuevo en cada pedido; acá queda la referencia, anotada por
 * el id de la venta. Antes de usarla se comprueba en la base que la venta sea
 * de quien hace el pedido (el "sub" de su token): si no lo es, responde 404,
 * igual que si no existiera.
 *
 * Es la fachada: ordena los pasos que quedan fuera de la transacción de la
 * compra. Consulta a Usuarios antes de abrirla, devuelve el cupo a Eventos
 * después de que la cancelación quedó confirmada en la base y, al pagar, deja
 * anotado en la venta el intento de pago antes de que se cobre.
 *
 * Y cuida que sobre la instancia de una compra haya una sola llamada a la
 * vez. El stateful rechaza al instante una segunda llamada simultánea
 * ({@code @AccessTimeout(0)}), pero en este servidor esa llamada rechazada
 * hace además que el contenedor descarte la instancia, con la primera llamada
 * todavía adentro (se comprobó en vivo). Para que una compra no pierda su
 * instancia por un doble clic, la segunda llamada se frena acá, antes de
 * llegar al stateful, con el mismo 409 {@code operacion-en-curso}.
 *
 * Es un bean {@code @ApplicationScoped}: una sola instancia para todo el
 * servicio, porque el índice tiene que ser el mismo para todos los pedidos.
 * No es un Session Bean: no es la conversación de un usuario (eso es el
 * stateful) y no tiene que abrir transacciones; las abren el stateful y los
 * repositorios. Como lo usan varios pedidos a la vez, el índice y la lista de
 * compras ocupadas son colecciones concurrentes.
 *
 * El índice vive en la memoria de este servidor: redesplegar Ventas lo vacía.
 * Las ventas siguen en la base; una compra que quedó sin instancia se puede
 * consultar y cancelar igual, pero ya no se puede pagar, porque los titulares
 * vivían en la instancia. Si nadie la cancela, la vence el barrido.
 */
@ApplicationScoped
public class ComprasEnCurso {

    private static final Logger LOG = Logger.getLogger(ComprasEnCurso.class.getName());

    private static final String OTRA_OPERACION_EN_CURSO =
            "Hay otra operación en curso sobre esta compra. Probá de nuevo en unos segundos.";

    private static final String HAY_UN_PAGO_EN_CURSO = "Hay un pago en curso para esta compra. Esperá a que termine.";

    /** El nombre con el que el servidor publica el componente de la compra dentro de este WAR. */
    static final String JNDI_DE_LA_COMPRA = "java:module/ServicioDeVentas!com.palco.ventas.negocio.IVentas";

    @Inject
    VentaRepository ventas;

    @Inject
    EntradaRepository entradas;

    @Inject
    ClienteDeEventos eventos;

    @Inject
    ClienteDeUsuarios usuarios;

    /** De dónde sale el "ahora" para revisar si la reserva sigue vigente. Los tests ponen un reloj detenido. */
    Clock reloj = Clock.systemUTC();

    /**
     * Cómo se consigue una instancia para una compra nueva. En el servidor es
     * una búsqueda por JNDI: cada búsqueda de un stateful crea una instancia.
     * Es un campo para que el test lo reemplace por un doble.
     */
    Supplier<IVentas> nuevaCompra = ComprasEnCurso::pedirInstanciaAlServidor;

    /** Las compras abiertas: id de la venta → referencia a su instancia stateful. */
    final Map<String, IVentas> enCurso = new ConcurrentHashMap<>();

    /** Las compras que en este momento tienen una llamada en curso sobre su instancia. */
    private final Set<String> ocupadas = ConcurrentHashMap.newKeySet();

    /**
     * Abre una compra. Repetir el pedido con la misma clave de idempotencia
     * devuelve la venta que ya existe, sin reservar otra vez.
     *
     * @param compradorId       el usuario del token (claim "sub")
     * @param tokenDelComprador el token tal cual llegó en el pedido, sin la palabra "Bearer"
     */
    public VentaDto iniciar(String compradorId, String tokenDelComprador, NuevaVentaDto nueva,
            String claveIdempotencia) {
        // Un pedido repetido: se devuelve la venta como está hoy, sin crear otra instancia ni reservar.
        Optional<Venta> anterior = ventas.buscarPorClave(compradorId, claveIdempotencia);
        if (anterior.isPresent()) {
            return ServicioDeVentas.aDto(anterior.get());
        }

        // Solo compra quien verificó su identidad. Se consulta antes de abrir la transacción de la compra.
        if (!usuarios.yo(tokenDelComprador).identidadVerificada()) {
            throw new ErroresDeNegocio.SinPermiso(TiposDeProblema.IDENTIDAD_NO_VERIFICADA,
                    "Tenés que verificar tu identidad antes de comprar.");
        }

        IVentas compra = nuevaCompra.get();
        try {
            VentaDto venta = compra.iniciarCompra(compradorId, nueva, claveIdempotencia);
            enCurso.put(venta.id(), compra);
            return venta;
        } catch (RuntimeException e) {
            // La compra no se abrió: esa instancia no sirve para nada más.
            soltar(compra);
            if (e instanceof ErroresDeNegocio.Conflicto conflicto
                    && VentaRepository.TIPO_CLAVE_REPETIDA.equals(conflicto.tipo())) {
                // Dos pedidos con la misma clave llegaron a la vez y los dos pasaron la primera búsqueda.
                // La restricción única de la base dejó guardar a uno solo: vale esa venta.
                return ventas.buscarPorClave(compradorId, claveIdempotencia)
                        .map(ServicioDeVentas::aDto)
                        .orElseThrow(() -> e);
            }
            throw e;
        }
    }

    /** La venta, solo si es del comprador que la pide. */
    public VentaDto obtener(String compradorId, String ventaId) {
        return ServicioDeVentas.aDto(delComprador(compradorId, ventaId));
    }

    /**
     * La entrada, solo si es de una venta del comprador que la pide. Una
     * entrada ajena responde lo mismo que una que no existe.
     */
    public EntradaDto obtenerEntrada(String compradorId, String entradaId) {
        return entradas.buscarPorId(entradaId)
                .filter(entrada -> entrada.getVenta().getCompradorId().equals(compradorId))
                .map(ServicioDeVentas::aDto)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe esa entrada."));
    }

    /**
     * Paga una compra abierta y devuelve la venta ya {@code CONFIRMADA}, con
     * sus entradas. Repetir el pedido con la misma clave de idempotencia
     * devuelve la venta como está hoy, sin cobrar otra vez.
     *
     * Antes de cobrar deja anotada en la venta la clave de este intento de
     * pago ({@code clave_pago}), con un UPDATE que queda confirmado en el
     * momento, fuera de la transacción del pago. Esa marca sirve para dos
     * cosas. Mientras está, nadie puede cancelar ni vencer la venta: el cupo
     * no se libera con un cobro en curso. Y si la transacción del pago falla
     * después de cobrar, en la venta queda el rastro de que hubo un intento.
     *
     * Lanza 404 si la venta no existe o es de otro usuario; 409
     * {@code reserva-vencida} si ya no se puede pagar (venció, se canceló o el
     * servicio perdió la instancia de esa compra); 409 {@code venta-ya-paga}
     * si se pagó con otra clave; 409 {@code operacion-en-curso} si hay otro
     * pago u otra llamada en curso; 402 {@code pago-rechazado}; y 504
     * {@code sin-respuesta} si quien cobra no contestó: en ese caso se
     * reintenta con la misma clave.
     */
    public VentaDto pagar(String compradorId, String ventaId, NuevoPagoDto pago, String clavePago) {
        LocalDateTime ahora = ServicioDeVentas.ahoraEnUtc(reloj);

        // 1. Se mira la venta, sin cambiar nada.
        Venta venta = delComprador(compradorId, ventaId);
        if (venta.estaPaga() && clavePago.equals(venta.getClavePago())) {
            // Es el mismo pedido de pago, que llegó otra vez: no se cobra de nuevo.
            return ServicioDeVentas.aDto(venta);
        }
        ServicioDeVentas.exigirQueSePuedaPagar(venta, ahora);
        IVentas compra = enCurso.get(ventaId);
        if (compra == null) {
            // Sin instancia no están los titulares: se redesplegó el servicio o el servidor la descartó.
            // No se anota nada: la venta queda como estaba y, si nadie la cancela, vence sola.
            throw ServicioDeVentas.reservaVencida();
        }
        // Si la venta ya traía anotada esta misma clave, este pedido repite un intento de pago del que
        // no se supo el resultado (por ejemplo, uno que terminó en 504).
        boolean reintento = clavePago.equals(venta.getClavePago());

        ocupar(ventaId);
        try {
            // 2. Se anota la clave de este intento. El UPDATE exige que la venta siga EN_CHECKOUT, con la
            //    reserva vigente y sin la clave de otro intento.
            if (!ventas.guardarClaveDePago(ventaId, clavePago, ahora)) {
                // No cambió ninguna fila: se vuelve a leer la venta para saber por qué.
                ServicioDeVentas.exigirQueSePuedaPagar(delComprador(compradorId, ventaId), ahora);
                // Sigue abierta y vigente: lo que frenó el UPDATE es la clave de otro intento de pago.
                throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, HAY_UN_PAGO_EN_CURSO);
            }
            return cobrarYConfirmar(compra, ventaId, pago, clavePago, reintento);
        } finally {
            desocupar(ventaId);
        }
    }

    /**
     * Cancela una compra sin pagar y devuelve su cupo. Repetirlo sobre una
     * venta ya cancelada o vencida no hace nada y tampoco falla. Lanza 409
     * {@code venta-ya-paga} si ya se pagó y 409 {@code operacion-en-curso} si
     * hay un pago en curso u otra llamada sobre la misma compra.
     */
    public void cancelar(String compradorId, String ventaId) {
        Venta venta = delComprador(compradorId, ventaId);
        if (venta.getEstado() == EstadoVenta.EN_CHECKOUT) {
            if (cerrarLaCompra(ventaId)) {
                // Este pedido fue el que canceló la venta: le toca devolver el cupo, y recién ahora,
                // con la cancelación ya confirmada en la base.
                LOG.info("Venta " + ventaId + " cancelada por su comprador: se devuelve el cupo a Eventos.");
                devolverCupo(venta);
                return;
            }
            // No la canceló este pedido: se la vuelve a leer para saber por qué.
            venta = delComprador(compradorId, ventaId);
        }
        if (venta.estaPaga()) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.VENTA_YA_PAGA,
                    "La compra ya está paga: no se puede cancelar.");
        }
        if (venta.getEstado() == EstadoVenta.EN_CHECKOUT) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, HAY_UN_PAGO_EN_CURSO);
        }
        // Ya estaba cancelada o vencida. El cupo lo devuelve quien la cerró, no este pedido.
    }

    /**
     * Devuelve a Eventos el cupo de todos los renglones de una venta que ya se
     * cerró sin pagarse. Solo lo llama quien cambió el estado de la venta con
     * su propio UPDATE, y después de que ese cambio quedó confirmado. Si
     * Eventos no responde no falla: queda un WARNING por cada sector.
     */
    public void devolverCupo(Venta venta) {
        for (VentaItem item : venta.getItems()) {
            eventos.liberarCupo(venta.getId(), venta.getEventoId(), item.getSectorId(), item.getCantidad());
        }
    }

    /** Quita del índice la referencia de una compra que ya terminó. La llama el {@code @PreDestroy} de la instancia. */
    public void olvidar(String ventaId) {
        enCurso.remove(ventaId);
    }

    // ---------------------------------------------------------------------------------------------

    /** La venta, si existe y es de ese comprador. Una venta ajena responde lo mismo que una que no existe. */
    private Venta delComprador(String compradorId, String ventaId) {
        return ventas.buscarPorId(ventaId)
                .filter(venta -> venta.getCompradorId().equals(compradorId))
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe esa venta."));
    }

    /**
     * Pasa la venta a {@code CANCELADA} y dice si lo logró. Lo hace la instancia
     * de la compra, para que el servidor la libere en la misma llamada
     * ({@code @Remove}). Si la instancia ya no está, el mismo UPDATE se hace
     * por el repositorio: la venta está en la base y no depende de la instancia.
     */
    private boolean cerrarLaCompra(String ventaId) {
        IVentas compra = enCurso.get(ventaId);
        if (compra == null) {
            // Sin instancia: venció por inactividad o se redesplegó el servicio.
            return ventas.cancelar(ventaId);
        }
        ocupar(ventaId);
        try {
            boolean cancelada = compra.cancelarCompra();
            enCurso.remove(ventaId);
            return cancelada;
        } catch (NoSuchEJBException e) {
            // El servidor ya había destruido la instancia: la referencia no sirve más.
            enCurso.remove(ventaId);
            return ventas.cancelar(ventaId);
        } catch (ConcurrentAccessException e) {
            // No debería pasar, porque la segunda llamada se frena en ocupar(). Si el servidor igual
            // rechaza la llamada, la respuesta es la misma.
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, OTRA_OPERACION_EN_CURSO);
        } finally {
            desocupar(ventaId);
        }
    }

    /**
     * Pasos 3 y 4 del pago: llama a la instancia de la compra y, según cómo
     * termina, decide qué hace con la marca del intento de pago. La regla es
     * una sola: la marca se quita cuando se sabe que no se cobró. Ante la duda
     * (quien cobra no contestó, o una falla inesperada) se deja: la venta
     * queda {@code EN_CHECKOUT} con su cupo, a la vista para revisarla. Si
     * quien cobra no contestó, el comprador reintenta con la misma clave.
     */
    private VentaDto cobrarYConfirmar(IVentas compra, String ventaId, NuevoPagoDto pago, String clavePago,
            boolean reintento) {
        try {
            // 3. La instancia cobra y, en una sola transacción, confirma la venta, crea las entradas y
            //    publica el mensaje. Al volver, el servidor ya la liberó (@Remove).
            VentaDto pagada = compra.confirmarCompra(pago, clavePago);
            enCurso.remove(ventaId);
            return pagada;
        } catch (ErroresDeNegocio.PagoRechazado e) {
            // 4. El cobro fue rechazado: no se cobró. Sin la marca, la compra se puede pagar con otro
            //    medio de pago, cancelar o vencer.
            ventas.borrarClaveDePago(ventaId, clavePago);
            throw e;
        } catch (NoSuchEJBException e) {
            // El servidor ya había destruido la instancia: la llamada no entró, así que no se cobró.
            enCurso.remove(ventaId);
            quitarMarcaSinCobro(ventaId, clavePago, reintento);
            throw ServicioDeVentas.reservaVencida();
        } catch (ErroresDeNegocio.Conflicto e) {
            if (TiposDeProblema.RESERVA_VENCIDA.equals(e.tipo())) {
                // La reserva venció justo entre la marca y la revisión de la instancia, que es anterior al cobro.
                quitarMarcaSinCobro(ventaId, clavePago, reintento);
            }
            throw e;
        } catch (ConcurrentAccessException e) {
            // No debería pasar, porque la segunda llamada se frena en ocupar(). La marca no se toca.
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, OTRA_OPERACION_EN_CURSO);
        } catch (EJBException e) {
            // Una falla inesperada dentro de la instancia. El servidor la descarta sin avisarle (no corre
            // su @PreDestroy), así que la referencia ya no sirve: se quita. La marca se deja.
            enCurso.remove(ventaId);
            throw e;
        }
    }

    /**
     * Quita la marca cuando este pedido no llegó a cobrar. Si es un reintento
     * se deja: la marca ya estaba, y del intento anterior no se sabe si cobró.
     */
    private void quitarMarcaSinCobro(String ventaId, String clavePago, boolean reintento) {
        if (!reintento) {
            ventas.borrarClaveDePago(ventaId, clavePago);
        }
    }

    /**
     * Anota que empieza una llamada sobre la instancia de esa compra. Si ya
     * había una en curso lanza 409 {@code operacion-en-curso} y la llamada
     * nueva no llega al stateful. Quien llama avisa cuando termina con
     * {@link #desocupar}, en un {@code finally}.
     */
    private void ocupar(String ventaId) {
        if (!ocupadas.add(ventaId)) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, OTRA_OPERACION_EN_CURSO);
        }
    }

    private void desocupar(String ventaId) {
        ocupadas.remove(ventaId);
    }

    /**
     * Le pide al servidor que libere una instancia que no llegó a abrir una
     * compra: {@code cancelarCompra} lleva {@code @Remove}. Si la falla fue
     * inesperada, el servidor ya la había descartado y no queda nada que hacer.
     */
    private static void soltar(IVentas compra) {
        try {
            compra.cancelarCompra();
        } catch (RuntimeException yaDescartada) {
            LOG.fine("La instancia ya no existía al soltarla: " + yaDescartada);
        }
    }

    private static IVentas pedirInstanciaAlServidor() {
        try {
            return InitialContext.doLookup(JNDI_DE_LA_COMPRA);
        } catch (NamingException e) {
            throw new IllegalStateException("No se encontró el componente de la compra en " + JNDI_DE_LA_COMPRA, e);
        }
    }
}
