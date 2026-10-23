package com.palco.ventas.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
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
import com.palco.ventas.negocio.externos.ClienteDeEventos.Devolucion;
import com.palco.ventas.negocio.externos.ClienteDeUsuarios;
import com.palco.ventas.negocio.externos.CobroNoRealizado;

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
 * vivían en la instancia. Si el comprador intenta pagarla, se cancela en ese
 * momento y devuelve su cupo; si nadie la toca, la vence el barrido. La
 * excepción es la compra que había quedado con un intento de pago sin
 * resultado: no se cancela ni vence, y la cierra {@link ConciliacionDePagos}
 * cuando sabe por Pagos cómo terminó ese cobro.
 */
@ApplicationScoped
public class ComprasEnCurso {

    private static final Logger LOG = Logger.getLogger(ComprasEnCurso.class.getName());

    private static final String OTRA_OPERACION_EN_CURSO =
            "Hay otra operación en curso sobre esta compra. Probá de nuevo en unos segundos.";

    private static final String HAY_UN_PAGO_EN_CURSO = "Hay un pago en curso para esta compra. Esperá a que termine.";

    private static final String HAY_UN_PAGO_SIN_RESULTADO = "Esta compra tiene un pago del que no se conoce el"
            + " resultado. No pagues de nuevo ni empieces otra compra por estas entradas: puede que ya se haya cobrado.";

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
     * devuelve la venta que ya existe, sin reservar otra vez. Si la clave ya
     * abrió una compra distinta (otro evento, otros sectores u otras
     * cantidades) lanza 409 {@code clave-reutilizada}: devolver aquella venta
     * le haría creer al comprador que se reservó lo que pidió ahora.
     *
     * @param compradorId       el usuario del token (claim "sub")
     * @param tokenDelComprador el token tal cual llegó en el pedido, sin la palabra "Bearer"
     */
    public VentaDto iniciar(String compradorId, String tokenDelComprador, NuevaVentaDto nueva,
            String claveIdempotencia) {
        // Un pedido repetido: se devuelve la venta como está hoy, sin crear otra instancia ni reservar.
        Optional<Venta> anterior = ventas.buscarPorClave(compradorId, claveIdempotencia);
        if (anterior.isPresent()) {
            return ServicioDeVentas.aDto(mismoPedido(anterior.get(), nueva));
        }

        // Solo compra quien verificó su identidad. Se consulta antes de pedir una instancia y de reservar nada.
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
                // La restricción única de la base dejó guardar a uno solo: vale esa venta. El que
                // perdió ya devolvió el cupo que había reservado, antes de lanzar el conflicto.
                return ventas.buscarPorClave(compradorId, claveIdempotencia)
                        .map(ganadora -> ServicioDeVentas.aDto(mismoPedido(ganadora, nueva)))
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
     * Las compras del comprador que las pide, de la más nueva a la más vieja,
     * cada una con sus entradas. Vienen todas, en cualquier estado: también
     * las canceladas y las vencidas. Las de otro usuario no aparecen nunca,
     * porque se buscan por el comprador del token. Si no compró nada, la
     * lista viene vacía.
     *
     * Se leen de la base: no dependen de que la compra tenga su instancia viva
     * ni de lo que recuerde el navegador.
     */
    public List<VentaDto> listar(String compradorId) {
        return ventas.buscarPorComprador(compradorId).stream()
                .map(ServicioDeVentas::aDto)
                .toList();
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
     * servicio perdió la instancia de esa compra; en ese último caso la venta
     * se cancela y devuelve el cupo antes de responder); 409 {@code venta-ya-paga}
     * si se pagó con otra clave; 409 {@code operacion-en-curso} si hay otro
     * pago u otra llamada en curso; 402 {@code pago-rechazado}; y 504
     * {@code sin-respuesta} si quien cobra no contestó: en ese caso se
     * reintenta con la misma clave.
     *
     * Ese reintento vale aunque la reserva haya vencido, mientras viva la
     * instancia de la compra: la marca siguió reteniendo el cupo. Si la
     * instancia ya no está, la venta marcada no tiene salida por la API:
     * responde 409 {@code operacion-en-curso} con el aviso de no pagar de
     * nuevo, nunca {@code reserva-vencida}, y deja un WARNING.
     */
    public VentaDto pagar(String compradorId, String ventaId, NuevoPagoDto pago, String clavePago) {
        LocalDateTime ahora = ServicioDeVentas.ahoraEnUtc(reloj);

        // 1. Se mira la venta, sin cambiar nada.
        Venta venta = delComprador(compradorId, ventaId);
        if (venta.estaPaga() && clavePago.equals(venta.getClavePago())) {
            // Es el mismo pedido de pago, que llegó otra vez: no se cobra de nuevo.
            return ServicioDeVentas.aDto(venta);
        }
        // Una venta abierta que ya trae anotada una clave de pago tuvo un intento del que no se supo el
        // resultado (por ejemplo, uno que terminó en 504). Si la clave es la de este pedido, lo repite.
        boolean conPagoSinResultado = venta.getEstado() == EstadoVenta.EN_CHECKOUT && venta.getClavePago() != null;
        boolean reintento = conPagoSinResultado && clavePago.equals(venta.getClavePago());
        IVentas compra = enCurso.get(ventaId);
        if (conPagoSinResultado) {
            // Un reintento no exige la reserva vigente: la marca de aquel intento sigue reteniendo el cupo.
            // Lo que no puede faltar es la instancia, que es la que tiene los titulares.
            boolean sePuedeSeguir = compra != null && (reintento || !venta.reservaVencida(ahora));
            if (!sePuedeSeguir) {
                throw pagoSinResultado(ventaId, venta.getClavePago());
            }
        } else {
            ServicioDeVentas.exigirQueSePuedaPagar(venta, ahora);
            if (compra == null) {
                // Sin instancia no están los titulares: se redesplegó el servicio o el servidor la descartó.
                // La respuesta invita a empezar de nuevo, así que la venta se cierra ya y suelta su cupo.
                throw compraPerdida(venta);
            }
        }

        ocupar(ventaId);
        try {
            // 2. Se anota la clave de este intento. El UPDATE exige que la venta siga EN_CHECKOUT y sin la
            //    clave de otro intento. La reserva vigente la exige solo si todavía no había una clave: en
            //    un reintento la clave ya está anotada y el UPDATE confirma que sigue ahí.
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
     * cerró sin pagarse. Lo llama quien cambió el estado de la venta con su
     * propio UPDATE, después de que ese cambio quedó confirmado, y el barrido
     * {@link DevolucionDeCupos} con las que quedaron pendientes.
     *
     * El UPDATE que cerró la venta dejó la marca de devolución pendiente. Si
     * Eventos aceptó todos los renglones (o rechazó alguno para siempre), acá
     * se saca. Si algún renglón quedó sin respuesta, la marca sigue y el
     * barrido lo vuelve a intentar. Repetirlo es seguro: cada devolución lleva
     * su clave y Eventos no resta dos veces. No falla: si Eventos no responde
     * queda un WARNING por sector.
     */
    public void devolverCupo(Venta venta) {
        boolean faltaAlguno = false;
        for (VentaItem item : venta.getItems()) {
            Devolucion resultado = eventos.liberarCupo(venta.getId(), venta.getEventoId(), item.getSectorId(),
                    item.getCantidad());
            faltaAlguno |= resultado != Devolucion.HECHA && resultado != Devolucion.RECHAZADA;
        }
        if (faltaAlguno) {
            return;
        }
        try {
            ventas.marcarCupoDevuelto(venta.getId());
        } catch (RuntimeException e) {
            // El cupo ya está devuelto. Si la marca no se pudo sacar, el barrido lo intenta de nuevo y
            // Eventos reconoce las claves: no resta otra vez.
            LOG.warning("El cupo de la venta " + venta.getId() + " ya se devolvió, pero no se pudo anotar en la"
                    + " base; el barrido lo vuelve a intentar sin restar dos veces. Motivo: " + e);
        }
    }

    /**
     * Quita del índice la referencia de una compra que ya terminó. La llaman
     * el {@code @PreDestroy} de la instancia, el barrido cuando vence la venta
     * y la conciliación cuando la cierra.
     */
    public void olvidar(String ventaId) {
        enCurso.remove(ventaId);
    }

    // ---- Lo que usa ConciliacionDePagos ----

    /** Si la compra todavía tiene su instancia en el índice: mientras la tenga, el comprador puede reintentar el pago. */
    boolean tieneInstancia(String ventaId) {
        return enCurso.containsKey(ventaId);
    }

    /**
     * La conciliación toma la compra como una llamada más: con la misma marca
     * de "ocupada" que usan pagar y cancelar. Así un reintento del comprador y
     * la conciliación nunca trabajan a la vez sobre la misma venta. Devuelve
     * {@code false} si había otra llamada en curso; la conciliación la deja
     * para la corrida siguiente. Quien la tomó la suelta con
     * {@link #soltarDeLaConciliacion}, en un {@code finally}.
     */
    boolean tomarParaConciliar(String ventaId) {
        return ocupadas.add(ventaId);
    }

    void soltarDeLaConciliacion(String ventaId) {
        desocupar(ventaId);
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
     *
     * En un reintento la marca no se quita aunque este pedido no haya
     * cobrado: del intento anterior con esa clave no se sabe el resultado.
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
        } catch (CobroNoRealizado e) {
            // Quien cobra avisó que el pedido se frenó antes del cobro (por ejemplo, Pagos no está
            // desplegado): este pedido no cobró. El comprador recibe el mismo 504 y puede reintentar.
            quitarMarcaSinCobro(ventaId, clavePago, reintento);
            throw e;
        } catch (NoSuchEJBException e) {
            // El servidor ya había destruido la instancia: la llamada no entró, así que no se cobró.
            enCurso.remove(ventaId);
            quitarMarcaSinCobro(ventaId, clavePago, reintento);
            if (reintento) {
                throw pagoSinResultado(ventaId, clavePago);
            }
            // Ya sin la marca de este intento, la venta se cierra como cuando no hay instancia.
            throw ventas.buscarPorId(ventaId).map(this::compraPerdida).orElseGet(ServicioDeVentas::reservaVencida);
        } catch (ErroresDeNegocio.Conflicto e) {
            if (TiposDeProblema.RESERVA_VENCIDA.equals(e.tipo())) {
                // La reserva venció justo entre la marca y la revisión de la instancia, que es anterior al cobro.
                quitarMarcaSinCobro(ventaId, clavePago, reintento);
                if (reintento) {
                    // La marca del intento anterior sigue puesta: tampoco acá se invita a comprar de nuevo.
                    throw pagoSinResultado(ventaId, clavePago);
                }
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
     * Cierra una compra vigente que perdió su instancia (se redesplegó el
     * servicio o el servidor la descartó) y devuelve el 409
     * {@code reserva-vencida} que se le responde al comprador. Sin los
     * titulares no se puede pagar, y el mensaje le dice que empiece de nuevo:
     * para que eso sea cierto, la venta pasa a {@code CANCELADA} con el mismo
     * UPDATE condicional de {@link #cancelar} y, si este pedido fue el que la
     * cerró, su cupo vuelve a Eventos. Si el UPDATE no cambió nada, la cerró
     * otro pedido (o tiene la marca de otro intento de pago) y el cupo no se
     * toca: lo devuelve quien la cerró.
     */
    private ErroresDeNegocio.Conflicto compraPerdida(Venta venta) {
        if (ventas.cancelar(venta.getId())) {
            LOG.info("Venta " + venta.getId() + " cancelada al pagar porque su compra ya no tenía instancia:"
                    + " se devuelve el cupo a Eventos.");
            devolverCupo(venta);
        }
        return ServicioDeVentas.reservaVencida();
    }

    /**
     * La venta de una clave de idempotencia ya usada, si el pedido es el
     * mismo: mismo evento y mismos renglones (sector y cantidad, en el mismo
     * orden). Si no, lanza 409 {@code clave-reutilizada}. Los titulares no se
     * comparan porque no se guardan en la base: viven en la instancia.
     */
    private static Venta mismoPedido(Venta anterior, NuevaVentaDto nueva) {
        List<String> guardados = anterior.getItems().stream()
                .map(item -> item.getSectorId() + "x" + item.getCantidad())
                .toList();
        List<String> pedidos = nueva.items().stream()
                .map(item -> item.sectorId() + "x" + item.cantidad())
                .toList();
        if (!anterior.getEventoId().equals(nueva.eventoId()) || !guardados.equals(pedidos)) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.CLAVE_REUTILIZADA,
                    "Esa clave ya se usó para otra compra");
        }
        return anterior;
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
     * El error de una compra que quedó con un intento de pago sin resultado y
     * que ya no se puede pagar: venció la reserva o no está la instancia que
     * guardaba los titulares. No es {@code reserva-vencida}: ese mensaje
     * invita a empezar otra compra, y puede que esta ya se haya cobrado. Por
     * la API del comprador no tiene salida: la cierra la conciliación, que le
     * pregunta a Pagos por esa clave. Deja un WARNING con la venta y la clave.
     */
    private static ErroresDeNegocio.Conflicto pagoSinResultado(String ventaId, String claveAnotada) {
        LOG.warning("La venta " + ventaId + " quedó con un intento de pago sin resultado (clave " + claveAnotada
                + ") y ya no se puede pagar: venció la reserva o no está la instancia de la compra. La cierra"
                + " ConciliacionDePagos cuando Pagos diga cómo terminó el cobro con esa clave.");
        return new ErroresDeNegocio.Conflicto(TiposDeProblema.OPERACION_EN_CURSO, HAY_UN_PAGO_SIN_RESULTADO);
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
