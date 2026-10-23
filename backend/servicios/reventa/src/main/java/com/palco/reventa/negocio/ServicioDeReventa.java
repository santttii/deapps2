package com.palco.reventa.negocio;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import jakarta.ejb.Stateless;
import jakarta.ejb.TransactionAttribute;
import jakarta.ejb.TransactionAttributeType;
import jakarta.inject.Inject;

import com.palco.common.ErroresDeNegocio;
import com.palco.common.ReglasDeNegocio;
import com.palco.common.Textos;
import com.palco.reventa.api.Dtos.CompraDto;
import com.palco.reventa.api.Dtos.CompraHechaDto;
import com.palco.reventa.api.Dtos.EntradaTransferidaDto;
import com.palco.reventa.api.Dtos.NuevaPublicacionDto;
import com.palco.reventa.api.Dtos.PublicacionDto;
import com.palco.reventa.api.Dtos.TitularDto;
import com.palco.reventa.datos.PublicacionRepository;
import com.palco.reventa.dominio.EstadoPublicacion;
import com.palco.reventa.dominio.Publicacion;
import com.palco.reventa.negocio.externos.ClienteDeEventos;
import com.palco.reventa.negocio.externos.ClienteDePagos;
import com.palco.reventa.negocio.externos.ClienteDeVentas;
import com.palco.reventa.negocio.externos.PedidoNoEnviado;

/**
 * ServicioDeReventa: publicar una entrada propia, retirarla y comprar una
 * publicación ajena, con las reglas de {@code ReglasDeNegocio} (precio entre
 * el 50% y el 110% de lo pagado; la reventa cierra 3 horas antes del evento).
 *
 * Stateless: cada pedido llega completo (quién, qué publicación, qué precio,
 * con qué tarjeta) y no hay una conversación que recordar entre un pedido y
 * el siguiente, como sí la hay en el checkout de Ventas. Todo lo que tiene
 * que durar queda en la base: el estado de la publicación y la compra en
 * curso. Así cualquier instancia del pool atiende cualquier pedido.
 *
 * Reventa no es dueña de las entradas: las tiene Ventas. Cada cambio de una
 * entrada (publicarla, retirarla, transferirla) se le pide a Ventas por REST;
 * acá se guardan la publicación y su precio. Los dos servicios no comparten
 * una transacción, así que cada operación decide qué hacer si la otra parte
 * falla (ver cada método).
 */
@Stateless
public class ServicioDeReventa implements IReventa {

    private static final Logger LOG = Logger.getLogger(ServicioDeReventa.class.getName());

    /** Las fechas de los eventos son hora local de Argentina, sin zona: la regla de las 3 horas se mide ahí. */
    static final ZoneId ZONA_DE_LOS_EVENTOS = ZoneId.of("America/Argentina/Buenos_Aires");

    /** Hasta dónde puede llegar la Idempotency-Key: con "R-xxxxxxxx:" adelante entra en las 80 de Pagos. */
    static final int LARGO_MAXIMO_CLAVE = 64;

    @Inject
    PublicacionRepository publicaciones;

    @Inject
    ClienteDeVentas ventas;

    @Inject
    ClienteDePagos pagos;

    @Inject
    ClienteDeEventos eventos;

    /** De dónde sale el "ahora"; los tests lo reemplazan por un reloj fijo. */
    Clock reloj = Clock.systemUTC();

    @Override
    public List<PublicacionDto> listarActivas(String eventoId) {
        if (noEsUnId(eventoId)) {
            throw new ErroresDeNegocio.DatosInvalidos("eventoId", "Indicá el evento.");
        }
        return publicaciones.buscarActivasPorEvento(eventoId).stream().map(ServicioDeReventa::aDto).toList();
    }

    @Override
    public List<PublicacionDto> listarDelVendedor(String vendedorId) {
        return publicaciones.buscarPorVendedor(vendedorId).stream().map(ServicioDeReventa::aDto).toList();
    }

    /**
     * Publica una entrada propia. Primero se controla todo lo que se puede
     * controlar sin cambiar nada (que sea del vendedor y esté VALIDA, el
     * precio, el horario); después se le pide a Ventas que la deje PUBLICADA, y
     * recién ahí se guarda la publicación. Si guardarla falla, se le pide a
     * Ventas que la retire, para que la entrada no quede publicada sin una
     * publicación que la venda.
     */
    @Override
    public PublicacionDto publicar(String vendedorId, NuevaPublicacionDto nueva) {
        if (noEsUnId(nueva.entradaId())) {
            throw new ErroresDeNegocio.NoEncontrado("No existe esa entrada entre las tuyas.");
        }
        ClienteDeVentas.Entrada entrada = ventas.consultar(nueva.entradaId(), vendedorId);
        if (!"VALIDA".equals(entrada.estado())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.ENTRADA_NO_DISPONIBLE,
                    "La entrada no se puede revender: está " + entrada.estado() + ".");
        }
        long minimo = ReglasDeNegocio.minimoReventa(entrada.precioPagado());
        long maximo = ReglasDeNegocio.topeReventa(entrada.precioPagado());
        if (nueva.precio() < minimo || nueva.precio() > maximo) {
            throw new ErroresDeNegocio.ReglaIncumplida(TiposDeProblema.PRECIO_FUERA_DE_RANGO,
                    "El precio tiene que estar entre $" + minimo + " y $" + maximo + ".");
        }
        exigirReventaAbierta(entrada.eventoId());

        ventas.publicar(entrada.entradaId(), vendedorId);
        Publicacion publicacion = new Publicacion(nuevoId(), entrada.entradaId(), vendedorId, entrada.eventoId(),
                entrada.sectorId(), entrada.sector(), entrada.precioPagado(), nueva.precio(), ahoraUtc());
        try {
            publicaciones.guardar(publicacion);
        } catch (RuntimeException e) {
            deshacerPublicacion(entrada.entradaId(), vendedorId);
            throw e;
        }
        return aDto(publicacion);
    }

    /**
     * Retira una publicación propia. El UPDATE condicional la deja RETIRADA en
     * esta transacción y después se le pide a Ventas que devuelva la entrada a
     * VALIDA. Si Ventas falla, la excepción deshace la transacción y la
     * publicación sigue ACTIVA: el vendedor ve el error y puede reintentar.
     * No se retira mientras alguien la está comprando, ni mientras no se sepa
     * si una compra anterior se cobró (eso lo resuelve la conciliación).
     */
    @Override
    public void retirar(String vendedorId, String publicacionId) {
        Publicacion publicacion = propia(vendedorId, publicacionId);
        if (!publicaciones.retirar(publicacion.getId(), vendedorId, ahoraUtc())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.PUBLICACION_NO_ACTIVA,
                    "La publicación ya no está activa, o alguien la está comprando en este momento.");
        }
        ventas.retirar(publicacion.getEntradaId(), vendedorId);
    }

    /**
     * Compra una publicación. Corre sin transacción propia: cada paso queda
     * confirmado en la base al volver, porque entre paso y paso se llama a
     * otros servicios y una transacción abierta no sirve de nada con ellos.
     *
     * <ol>
     * <li>Se toma la publicación con un UPDATE condicional (sigue ACTIVA, pero
     * con la compra anotada): de dos compras a la vez, una sola llega a
     * cobrar.</li>
     * <li>Se cobra contra Pagos el precio más el cargo de servicio, con una
     * clave derivada de la Idempotency-Key: repetir la compra no cobra dos
     * veces.</li>
     * <li>Con el cobro aprobado, Ventas transfiere la entrada.</li>
     * <li>La publicación queda VENDIDA.</li>
     * </ol>
     *
     * Si el cobro se rechaza o se sabe que no se hizo, la publicación se
     * libera. Si Pagos no responde, la toma queda: no se sabe si cobró, y
     * mientras tanto nadie más paga por esa entrada. La toma no vence sola: o
     * el mismo comprador reintenta con la misma clave, o
     * {@link ConciliacionDeReventas} le pregunta a Pagos cómo terminó y
     * transfiere la entrada o libera la publicación.
     *
     * Límite: si el cobro sale aprobado y la transferencia falla, la
     * publicación vuelve a ACTIVA y el cobro queda hecho. Queda un registro
     * SEVERE con el pago para devolverlo a mano: el sistema todavía no tiene
     * reembolsos. Si el mismo comprador reintenta con la misma clave, Pagos
     * devuelve ese mismo pago aprobado y la transferencia se vuelve a
     * intentar sin cobrar de nuevo.
     */
    @Override
    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
    public CompraHechaDto comprar(String compradorId, String publicacionId, CompraDto compra, String clave) {
        validarClave(clave);
        validarTitular(compra.titular());
        Publicacion publicacion = buscar(publicacionId);

        // Un reintento de una compra que ya terminó: Ventas devuelve la misma venta y no cobra nada.
        if (publicacion.getEstado() == EstadoPublicacion.VENDIDA && compradorId.equals(publicacion.getCompradorId())
                && clave.equals(publicacion.getClaveCompra())) {
            return resultado(publicacion, transferir(publicacion, compradorId, compra.titular(),
                    publicacion.getPagoId()));
        }
        if (publicacion.getEstado() != EstadoPublicacion.ACTIVA) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.PUBLICACION_NO_ACTIVA,
                    "La publicación ya no está disponible.");
        }
        if (publicacion.getVendedorId().equals(compradorId)) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.COMPRA_PROPIA,
                    "No podés comprar tu propia publicación.");
        }
        exigirReventaAbierta(publicacion.getEventoId());

        // La toma anota también el titular: si el cobro se hace y la compra no termina, la conciliación
        // transfiere la entrada a su nombre sin esperar al comprador.
        if (!publicaciones.tomar(publicacion.getId(), compradorId, clave, compra.titular().nombre().strip(),
                compra.titular().dni(), ahoraUtc())) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.PUBLICACION_NO_ACTIVA,
                    "La publicación ya no está disponible: otra persona la está comprando o ya la compró.");
        }

        long monto = publicacion.getPrecio() + ReglasDeNegocio.cargoServicio(publicacion.getPrecio());
        ClienteDePagos.Cobro cobro = cobrar(publicacion, monto, compra, clave);
        if (!cobro.aprobado()) {
            publicaciones.liberar(publicacion.getId(), clave);
            throw new ErroresDeNegocio.PagoRechazado("El pago fue rechazado ("
                    + Textos.enUnaLinea(cobro.motivo()) + "). Probá con otro medio de pago.");
        }

        ClienteDeVentas.Transferencia transferencia;
        try {
            transferencia = transferir(publicacion, compradorId, compra.titular(), cobro.pagoId());
        } catch (RuntimeException e) {
            publicaciones.liberar(publicacion.getId(), clave);
            LOG.severe("Reventa " + publicacion.getId() + ": el cobro " + Textos.enUnaLinea(cobro.pagoId())
                    + " de $" + monto + " al comprador " + Textos.enUnaLinea(compradorId)
                    + " se aprobó pero la transferencia de la entrada " + publicacion.getEntradaId()
                    + " falló. La publicación volvió a ACTIVA; hay que devolver ese pago. Motivo: "
                    + Textos.enUnaLinea(e.getMessage()));
            if (e instanceof ErroresDeNegocio.Conflicto) {
                throw new ErroresDeNegocio.Conflicto(TiposDeProblema.PUBLICACION_NO_ACTIVA,
                        "La entrada ya no está disponible. El cobro se va a devolver.");
            }
            throw new ErroresDeNegocio.SinRespuesta("No se pudo transferir la entrada. Reintentá con la misma"
                    + " Idempotency-Key: no se cobra de nuevo.");
        }

        if (!publicaciones.marcarVendida(publicacion.getId(), compradorId, clave, transferencia.ventaId(),
                cobro.pagoId(), ahoraUtc())) {
            // Ventas ya transfirió la entrada, y eso vale: acá solo quedó la publicación sin cerrar.
            LOG.severe("Reventa " + publicacion.getId() + ": Ventas transfirió la entrada (venta "
                    + Textos.enUnaLinea(transferencia.ventaId()) + ") pero la publicación ya no estaba ACTIVA.");
        }
        LOG.info("Reventa " + publicacion.getId() + " vendida a " + Textos.enUnaLinea(compradorId)
                + " con el pago " + Textos.enUnaLinea(cobro.pagoId()) + ".");
        return resultado(buscar(publicacion.getId()), transferencia);
    }

    /** El cobro, liberando la publicación cuando se sabe que no se cobró. */
    private ClienteDePagos.Cobro cobrar(Publicacion publicacion, long monto, CompraDto compra, String clave) {
        try {
            return pagos.cobrar(publicacion.getId(), monto, compra.medioDePago(), compra.token(), clave);
        } catch (PedidoNoEnviado e) {
            publicaciones.liberar(publicacion.getId(), clave);
            throw e;
        } catch (ErroresDeNegocio.SinRespuesta e) {
            // No se sabe si cobró: la toma queda hasta que venza, para que nadie más pague por esta entrada.
            LOG.warning("Reventa " + publicacion.getId() + ": Pagos no respondió al cobrar con la clave "
                    + Textos.enUnaLinea(clave) + ". La publicación queda tomada hasta que se sepa si se cobró:"
                    + " la resuelve un reintento con la misma clave o la conciliación.");
            throw e;
        } catch (RuntimeException e) {
            publicaciones.liberar(publicacion.getId(), clave);
            throw e;
        }
    }

    private ClienteDeVentas.Transferencia transferir(Publicacion publicacion, String compradorId, TitularDto titular,
            String pagoId) {
        return ventas.transferir(publicacion.getId(), publicacion.getEntradaId(), publicacion.getVendedorId(),
                compradorId, new ClienteDeVentas.Titular(titular.nombre().strip(), titular.dni()),
                publicacion.getPrecio(), pagoId);
    }

    /** Si guardar la publicación falló, la entrada no puede quedar PUBLICADA: se le pide a Ventas que la retire. */
    private void deshacerPublicacion(String entradaId, String vendedorId) {
        try {
            ventas.retirar(entradaId, vendedorId);
        } catch (RuntimeException e) {
            LOG.severe("La entrada " + entradaId + " quedó PUBLICADA en Ventas sin una publicación en Reventa: "
                    + "hay que devolverla a VALIDA. Motivo: " + Textos.enUnaLinea(e.getMessage()));
        }
    }

    /**
     * La reventa de un evento cierra {@code HORAS_LIMITE_REVENTA} horas antes de
     * que empiece. La fecha es hora local de Argentina, así que "ahora" se mide
     * en esa zona y no en la del servidor.
     */
    private void exigirReventaAbierta(String eventoId) {
        LocalDateTime fecha = eventos.fechaDelEvento(eventoId);
        LocalDateTime ahoraEnElEvento = LocalDateTime.ofInstant(reloj.instant(), ZONA_DE_LOS_EVENTOS);
        if (!ahoraEnElEvento.isBefore(fecha.minusHours(ReglasDeNegocio.HORAS_LIMITE_REVENTA))) {
            throw new ErroresDeNegocio.Conflicto(TiposDeProblema.REVENTA_CERRADA, "La reventa de este evento cerró: "
                    + "termina " + ReglasDeNegocio.HORAS_LIMITE_REVENTA + " horas antes de que empiece.");
        }
    }

    private Publicacion buscar(String publicacionId) {
        if (noEsUnId(publicacionId)) {
            throw new ErroresDeNegocio.NoEncontrado("No existe esa publicación.");
        }
        return publicaciones.buscarPorId(publicacionId)
                .orElseThrow(() -> new ErroresDeNegocio.NoEncontrado("No existe esa publicación."));
    }

    /** Una publicación ajena responde igual que una que no existe: 404. */
    private Publicacion propia(String vendedorId, String publicacionId) {
        Publicacion publicacion = buscar(publicacionId);
        if (!publicacion.getVendedorId().equals(vendedorId)) {
            throw new ErroresDeNegocio.NoEncontrado("No existe esa publicación.");
        }
        return publicacion;
    }

    private static void validarClave(String clave) {
        if (clave == null || clave.isBlank() || clave.length() > LARGO_MAXIMO_CLAVE
                || clave.codePoints().anyMatch(Textos::esDeControl)) {
            throw new ErroresDeNegocio.DatosInvalidos("Idempotency-Key",
                    "La cabecera Idempotency-Key es obligatoria y tiene hasta " + LARGO_MAXIMO_CLAVE + " caracteres.");
        }
    }

    private static void validarTitular(TitularDto titular) {
        if (titular.nombre().strip().codePoints().anyMatch(Textos::esDeControl)) {
            throw new ErroresDeNegocio.DatosInvalidos("titular.nombre",
                    "El nombre del titular va en una sola línea, sin saltos de línea ni caracteres de control.");
        }
        if (!ReglasDeNegocio.esDniValido(titular.dni())) {
            throw new ErroresDeNegocio.DatosInvalidos("titular.dni", "El DNI debe tener 7 u 8 dígitos.");
        }
    }

    /** Un id va en rutas de otros servicios y en el log: solo letras, números, guiones y guiones bajos. */
    static boolean noEsUnId(String id) {
        return id == null || !id.matches("[A-Za-z0-9_-]{1,40}");
    }

    private static CompraHechaDto resultado(Publicacion publicacion, ClienteDeVentas.Transferencia transferencia) {
        ClienteDeVentas.EntradaNueva nueva = transferencia.entrada();
        EntradaTransferidaDto entrada = new EntradaTransferidaDto(nueva.id(), nueva.codigoQr(),
                new TitularDto(nueva.titular().nombre(), nueva.titular().dni()), nueva.eventoId(), nueva.sectorId(),
                nueva.precioPagado());
        return new CompraHechaDto(aDto(publicacion), entrada, transferencia.ventaId());
    }

    static PublicacionDto aDto(Publicacion p) {
        return new PublicacionDto(p.getId(), p.getEntradaId(), p.getEventoId(), p.getSectorId(), p.getSector(),
                p.getPrecio(), p.getPrecioMaximo(), p.getEstado().name(),
                p.getPublicada().toInstant(ZoneOffset.UTC).toString());
    }

    private LocalDateTime ahoraUtc() {
        return LocalDateTime.ofInstant(reloj.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String nuevoId() {
        return "R-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
