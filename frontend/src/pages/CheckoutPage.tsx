import { useEffect, useRef, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import type { Carrito } from '../store/actions';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { calcularServicio, money } from '../lib/format';
import {
  cancelarCompra,
  iniciarCompra,
  nuevaClave,
  obtenerOrden,
  pagarCompra,
  tokenDePrueba,
  type NuevaCompra,
} from '../servicios/ventas';
import {
  esCVVValido,
  esDNIValido,
  esNombreValido,
  esNumeroTarjetaValido,
  esVencimientoValido,
  formatearNumeroTarjeta,
  soloDigitosDNI,
} from '../lib/validate';
import { MINUTOS_RESERVA, type EstadoOrden, type Evento, type MedioPago, type Orden, type Usuario } from '../types';
import Stepper from '../components/Stepper';
import Input from '../components/Input';
import Button from '../components/Button';
import Modal from '../components/Modal';
import StatusMessage from '../components/StatusMessage';
import PageTransition from '../components/PageTransition';

// El checkout contra ServicioDeVentas, en dos pasos:
//   1. Titulares: todavía no hay nada en el backend. "Continuar" inicia la compra, que reserva el
//      cupo por 10 minutos. Los titulares viajan en ese pedido y después no se pueden cambiar.
//   2. Pago: con la reserva hecha corre la cuenta regresiva. Se paga o se cancela.
// El id de la venta queda en el carrito (carrito.ordenId): por eso recargar la página, o irse y
// volver, retoma la misma compra en el paso de pago.
//
// Un pago que termina sin resultado (504 o sin conexión) no se da por rechazado ni por perdido:
// la página le pregunta a Ventas cómo quedó la venta y dice lo que contesta. Si sigue EN_CHECKOUT
// no se sabe si se cobró, y Ventas la mantiene tomada (la marca del intento de pago impide
// cancelarla o vencerla hasta que se resuelva). Por eso ahí la página no suelta la reserva ni dice
// que se liberó nada.

interface FilaTitular {
  sectorId: string;
  sectorNombre: string;
  precio: number;
  nombre: string;
  apellido: string;
  dni: string;
}

// Lo que dice el modal cuando la compra ya no sigue. Cada texto corresponde a un estado que
// informó Ventas: solo se dice que los lugares se liberaron cuando la venta quedó cancelada o vencida.
type Cierre = Extract<EstadoOrden, 'cancelada' | 'vencida'> | 'no_existe';
const TEXTO_DE_CIERRE: Record<Cierre, { titulo: string; texto: string }> = {
  vencida: {
    titulo: 'Venció tu reserva',
    texto: `La reserva dura ${MINUTOS_RESERVA} minutos y venció sin un pago aprobado: los lugares volvieron a la venta. Volvé a elegir tus entradas.`,
  },
  cancelada: {
    titulo: 'La compra se canceló',
    texto: 'La compra quedó cancelada sin un pago aprobado y los lugares volvieron a la venta. Volvé a elegir tus entradas.',
  },
  no_existe: {
    titulo: 'La compra ya no está disponible',
    texto: 'No encontramos esta compra entre las tuyas. Volvé a elegir tus entradas.',
  },
};

// Una fila por entrada del carrito. La primera queda a nombre de quien compra.
function filasIniciales(carrito: Carrito | null, evento: Evento | undefined, comprador: Usuario | null): FilaTitular[] {
  if (!carrito || !evento) return [];
  const filas: FilaTitular[] = [];
  carrito.items.forEach((item) => {
    const sector = evento.sectores.find((s) => s.id === item.sectorId);
    if (!sector) return;
    for (let i = 0; i < item.cantidad; i++) {
      const esPrimera = filas.length === 0;
      filas.push({
        sectorId: sector.id,
        sectorNombre: sector.nombre,
        precio: sector.precio,
        nombre: esPrimera ? comprador?.nombre ?? '' : '',
        apellido: esPrimera ? comprador?.apellido ?? '' : '',
        dni: esPrimera ? comprador?.dni ?? '' : '',
      });
    }
  });
  return filas;
}

// Una diferencia de pocos segundos entre los relojes no es un reloj mal puesto: es lo que tardó
// el pedido más el redondeo a segundos del vencimiento. Por debajo de esto no se corrige nada.
const TOLERANCIA_RELOJ_MS = 5_000;

// Cuánto le lleva el reloj del servidor al de este navegador, en milisegundos (negativo si el
// del navegador está adelantado). Se mide al recibir una reserva recién hecha: el backend acaba
// de fijar el vencimiento como "su hora más 10 minutos", así que su hora es el vencimiento
// menos 10 minutos. Es la única pista de la hora del servidor que llega al navegador.
function medirDesfaseReloj(reserva: Orden): number {
  const horaDelServidor = Date.parse(reserva.venceReservaISO) - MINUTOS_RESERVA * 60_000;
  const desfase = horaDelServidor - Date.now();
  return Math.abs(desfase) < TOLERANCIA_RELOJ_MS ? 0 : desfase;
}

// Segundos que faltan para que venza la reserva. Se calcula contra el reloj cada vez, en lugar de
// descontar un contador: así no se atrasa con la pestaña en segundo plano y sigue bien al recargar.
// El vencimiento está en la hora del servidor, por eso al reloj del navegador se le suma el
// desfase medido al reservar: con el reloj adelantado la cuenta arrancaba con minutos de menos,
// o directamente en cero, y la página soltaba una reserva recién hecha.
function segundosHasta(venceISO: string, desfaseReloj: number): number {
  return Math.max(0, Math.ceil((Date.parse(venceISO) - (Date.now() + desfaseReloj)) / 1000));
}

export default function CheckoutPage() {
  const { state, dispatch, usuarioActual } = useApp();
  const navigate = useNavigate();
  // Un carrito que armó otra cuenta en este navegador no se usa: se trata como vacío.
  const carritoAjeno = !!state.carrito?.usuarioId && state.carrito.usuarioId !== usuarioActual?.id;
  const carrito = carritoAjeno ? null : state.carrito;
  const evento = carrito ? state.eventos.find((e) => e.id === carrito.eventoId) : undefined;
  // Se guarda en el carrito junto con la reserva: al recargar o volver se usa el mismo. El ?? 0
  // cubre un carrito guardado antes de que existiera este dato.
  const desfaseReloj = carrito?.desfaseReloj ?? 0;

  // La venta que devolvió el backend al reservar. null mientras no hay reserva.
  const [orden, setOrden] = useState<Orden | null>(null);
  const [segundosRestantes, setSegundosRestantes] = useState(0);
  // Por qué terminó la compra, según Ventas; null mientras sigue.
  const [cierre, setCierre] = useState<Cierre | null>(null);
  // Se cumplió el plazo de la reserva pero Ventas la sigue teniendo abierta (por ejemplo, con un
  // pago sin resultado iniciado en otra pestaña): los lugares siguen tomados.
  const [abiertaFueraDePlazo, setAbiertaFueraDePlazo] = useState(false);
  const [consultando, setConsultando] = useState(false);
  // Evita que la cuenta regresiva dispare el cierre más de una vez.
  const cerrandoPorPlazo = useRef(false);

  // Si al entrar el carrito ya tenía una reserva (se recargó la página o se volvió al checkout),
  // se retoma esa compra en lugar de empezar otra.
  const [ordenARetomar] = useState(() => carrito?.ordenId ?? null);
  const [errorAlRetomar, setErrorAlRetomar] = useState('');
  const [reintentosDeRetomar, setReintentosDeRetomar] = useState(0);

  // Las filas se arman una sola vez, al entrar: ProtectedRoute y RequireCatalog garantizan que
  // el usuario y el evento ya están. Así un refresco del evento no borra lo que se tipeó.
  const [titulares, setTitulares] = useState<FilaTitular[]>(() => filasIniciales(carrito, evento, usuarioActual));
  const [erroresTitulares, setErroresTitulares] = useState<Record<number, { nombre?: string; apellido?: string; dni?: string }>>({});
  const [reservando, setReservando] = useState(false);
  const [errorReserva, setErrorReserva] = useState('');
  const [sinCupo, setSinCupo] = useState(false);
  // Clave de idempotencia del último pedido de reserva, junto con el pedido que se mandó.
  const ultimoPedido = useRef<{ clave: string; pedido: string } | null>(null);

  const [medioPago, setMedioPago] = useState<MedioPago>('pasarela');
  const [tarjeta, setTarjeta] = useState({ numero: '', vencimiento: '', cvv: '', titular: '' });
  const [erroresTarjeta, setErroresTarjeta] = useState<Record<string, string>>({});
  const [pagando, setPagando] = useState(false);
  const [cancelando, setCancelando] = useState(false);
  const [errorPago, setErrorPago] = useState('');
  // El último pago no tuvo resultado: se guarda en el carrito para que sobreviva a recargar.
  const pagoSinResultado = carrito?.pagoSinResultado ?? null;
  const sinResultado = !!pagoSinResultado || abiertaFueraDePlazo;

  // Pasa al paso de pago con la reserva que devolvió el backend. El desfase se recibe por
  // parámetro porque al reservar se acaba de medir y todavía no está en el carrito de este render.
  function mostrarPago(reserva: Orden, desfase: number) {
    setOrden(reserva);
    setSegundosRestantes(segundosHasta(reserva.venceReservaISO, desfase));
  }

  // La compra terminó (Ventas la dio por cancelada o vencida, o no la encuentra): se quita del
  // carrito y se avisa por qué. El carrito nunca se pone en null acá: la página caería en su
  // propio <Navigate to="/">.
  function cerrarCompra(motivo: Cierre) {
    if (carrito) {
      dispatch({
        type: 'SET_CARRITO',
        payload: { ...carrito, ordenId: null, clavePago: null, pagoSinResultado: null },
      });
    }
    setOrden(null);
    setCierre(motivo);
  }

  // Lleva la pantalla al estado que informó Ventas. Es lo único que decide qué se le dice al
  // usuario después de un pago sin resultado o de cumplirse el plazo.
  function aplicarEstado(recibida: Orden) {
    if (recibida.estado === 'en_checkout') {
      mostrarPago(recibida, desfaseReloj);
    } else if (recibida.estado === 'confirmada' || recibida.estado === 'emitida') {
      // El pago se aprobó (también puede ser el que había quedado sin resultado).
      navigate(`/confirmacion/${recibida.id}`, { replace: true });
    } else {
      cerrarCompra(recibida.estado);
    }
  }

  // "Ver estado": consulta la venta y muestra lo que contesta Ventas.
  async function consultarEstado(ordenId: string) {
    setConsultando(true);
    setErrorPago('');
    try {
      aplicarEstado(await obtenerOrden(ordenId));
    } catch (error) {
      if (error instanceof ErrorApi && error.estado === 404) cerrarCompra('no_existe');
      else setErrorPago(`No pudimos consultar el estado de la compra: ${mensajeDeError(error)}`);
    } finally {
      setConsultando(false);
    }
  }

  // Retomar la compra que quedó a medias: se le pregunta al backend cómo está.
  useEffect(() => {
    if (!ordenARetomar || !usuarioActual) return;
    let vigente = true;
    obtenerOrden(ordenARetomar)
      .then((recibida) => {
        if (vigente) aplicarEstado(recibida);
      })
      .catch((error: unknown) => {
        if (!vigente) return;
        if (error instanceof ErrorApi && error.estado === 404) cerrarCompra('no_existe');
        else setErrorAlRetomar(mensajeDeError(error));
      });
    return () => {
      vigente = false;
    };
    // Corre al entrar y cuando se toca "Reintentar"; no tiene que repetirse en cada render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ordenARetomar, reintentosDeRetomar]);

  // Cuenta regresiva de la reserva. Al llegar a cero se cancela la compra (así el cupo vuelve ya,
  // sin esperar al barrido del backend) y se consulta cómo quedó: el aviso sale de esa respuesta.
  // Con un pago en vuelo o sin resultado no se toca nada: Ventas retiene la venta hasta saber si
  // se cobró, y la cancelación respondería 409.
  useEffect(() => {
    if (!orden || sinResultado) return;
    const intervalo = setInterval(() => {
      const restantes = segundosHasta(orden.venceReservaISO, desfaseReloj);
      setSegundosRestantes(restantes);
      if (restantes === 0 && !pagando && !cerrandoPorPlazo.current) {
        cerrandoPorPlazo.current = true;
        cerrarPorPlazo(orden.id);
      }
    }, 1000);
    return () => clearInterval(intervalo);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orden, pagando, sinResultado]);

  async function cerrarPorPlazo(ordenId: string) {
    try {
      await cancelarCompra(ordenId);
    } catch {
      // Si no se pudo cancelar (por ejemplo, 409 por un pago en curso), lo dice la consulta.
    }
    try {
      const recibida = await obtenerOrden(ordenId);
      if (recibida.estado === 'cancelada' || recibida.estado === 'vencida') {
        // La canceló este pedido o el barrido del backend: en los dos casos, por el plazo.
        cerrarCompra('vencida');
        return;
      }
      // Sigue abierta después del plazo: no se libera nada ni se dice que se liberó.
      if (recibida.estado === 'en_checkout') setAbiertaFueraDePlazo(true);
      aplicarEstado(recibida);
    } catch (error) {
      if (error instanceof ErrorApi && error.estado === 404) {
        cerrarCompra('no_existe');
      } else {
        setAbiertaFueraDePlazo(true);
        setErrorPago(`No pudimos consultar el estado de la compra: ${mensajeDeError(error)}`);
      }
    }
  }

  if (!carrito || !evento) return <Navigate to="/" replace />;

  const conReserva = carrito.ordenId !== null;
  const paso = conReserva ? 1 : 0;
  const rutaDelEvento = `/evento/${evento.slug}`;

  const minutos = Math.floor(segundosRestantes / 60);
  const segundos = segundosRestantes % 60;
  const timerCritico = segundosRestantes <= 120;

  // Con la reserva hecha, los importes son los que calculó el backend. Antes, una estimación con
  // los precios del catálogo.
  const subtotalEstimado = titulares.reduce((acc, t) => acc + t.precio, 0);
  const subtotal = orden ? orden.subtotal : subtotalEstimado;
  const servicio = orden ? orden.servicio : calcularServicio(subtotalEstimado);
  const total = orden ? orden.total : subtotalEstimado + servicio;
  const lineasDelResumen = orden
    ? orden.items.map((it) => ({ sectorId: it.sectorId, cantidad: it.cantidad, precio: it.precioUnitario }))
    : carrito.items.map((it) => ({
        sectorId: it.sectorId,
        cantidad: it.cantidad,
        precio: evento.sectores.find((s) => s.id === it.sectorId)?.precio ?? 0,
      }));

  function actualizarTitular(index: number, campo: 'nombre' | 'apellido' | 'dni', valor: string) {
    setTitulares((prev) => prev.map((t, i) => (i === index ? { ...t, [campo]: valor } : t)));
  }

  // El backend valida lo mismo, pero no dice en qué entrada está el error: por eso se revisa
  // antes acá, que sí puede marcar el campo exacto.
  function validarTitulares(): boolean {
    const errores: typeof erroresTitulares = {};
    const dnisVistos = new Set<string>();
    titulares.forEach((t, i) => {
      const err: { nombre?: string; apellido?: string; dni?: string } = {};
      if (!esNombreValido(t.nombre)) err.nombre = 'Ingresá un nombre válido.';
      if (!esNombreValido(t.apellido)) err.apellido = 'Ingresá un apellido válido.';
      if (!esDNIValido(t.dni)) {
        err.dni = 'El DNI debe tener 7 u 8 dígitos.';
      } else {
        const digits = soloDigitosDNI(t.dni);
        if (dnisVistos.has(digits)) {
          err.dni = 'Ese DNI ya está asignado a otra entrada de esta compra.';
        } else {
          dnisVistos.add(digits);
        }
      }
      if (Object.keys(err).length > 0) errores[i] = err;
    });
    setErroresTitulares(errores);
    return Object.keys(errores).length === 0;
  }

  function validarPago(): boolean {
    if (medioPago !== 'tarjeta') return true;
    const errores: Record<string, string> = {};
    if (!esNumeroTarjetaValido(tarjeta.numero)) errores.numero = 'El número debe tener 16 dígitos.';
    if (!esVencimientoValido(tarjeta.vencimiento)) errores.vencimiento = 'Formato MM/AA.';
    if (!esCVVValido(tarjeta.cvv)) errores.cvv = 'El CVV debe tener 3 dígitos.';
    if (!esNombreValido(tarjeta.titular)) errores.titular = 'Ingresá el nombre del titular.';
    setErroresTarjeta(errores);
    return Object.keys(errores).length === 0;
  }

  // "Continuar": inicia la compra en el backend, que reserva el cupo.
  async function reservar() {
    if (!carrito || !evento || !usuarioActual || reservando || !validarTitulares()) return;
    const datos: NuevaCompra = {
      eventoId: evento.id,
      items: carrito.items,
      titulares: titulares.map((t) => ({ nombre: t.nombre, apellido: t.apellido, dni: t.dni })),
    };
    // Misma clave = mismo pedido. Si se repite el pedido tal cual (doble clic, reintento después
    // de un corte de red) viaja la misma clave y el backend devuelve la venta que ya creó, sin
    // reservar otra vez. Si cambió algo, es otro pedido y lleva una clave nueva: el backend, con
    // una clave repetida, devuelve la venta anterior sin mirar el cuerpo.
    const pedido = JSON.stringify(datos);
    if (ultimoPedido.current?.pedido !== pedido) ultimoPedido.current = { clave: nuevaClave(), pedido };

    setErrorReserva('');
    setSinCupo(false);
    setReservando(true);
    try {
      const reserva = await iniciarCompra(datos, ultimoPedido.current.clave);
      // El pedido ya tuvo respuesta: esa clave no se vuelve a usar.
      ultimoPedido.current = null;
      if (reserva.estado !== 'en_checkout') {
        // Solo pasa si se reintentó con la misma clave una reserva que mientras tanto se cerró.
        aplicarEstado(reserva);
        return;
      }
      // La clave del pago nace con la reserva y se guarda en el carrito, igual que el desfase
      // entre los relojes: los dos sobreviven a recargar.
      const desfase = medirDesfaseReloj(reserva);
      dispatch({
        type: 'SET_CARRITO',
        payload: {
          ...carrito,
          usuarioId: usuarioActual.id,
          ordenId: reserva.id,
          clavePago: nuevaClave(),
          desfaseReloj: desfase,
        },
      });
      mostrarPago(reserva, desfase);
    } catch (error) {
      // En un 400 el detalle está en los errores por campo; en el resto, en el mensaje.
      const campos = error instanceof ErrorApi ? Object.values(error.campos) : [];
      setErrorReserva(campos.length > 0 ? campos.join(' ') : mensajeDeError(error));
      // Sin cupo no sirve reintentar: se ofrece volver al evento, que muestra el stock al día.
      setSinCupo(error instanceof ErrorApi && error.tipo === 'cupo-agotado');
    } finally {
      setReservando(false);
    }
  }

  // Paga, o repite el pago que quedó sin resultado: en ese caso van los mismos datos y la misma
  // clave, así Ventas lo reconoce como el mismo pedido y no cobra dos veces.
  async function pagar() {
    if (!carrito?.clavePago || !orden || !usuarioActual || pagando) return;
    if (!pagoSinResultado && !validarPago()) return;
    const datos = pagoSinResultado ?? { medioPago, token: tokenDePrueba(medioPago, tarjeta.numero) };
    setErrorPago('');
    setPagando(true);
    try {
      const paga = await pagarCompra(orden.id, datos, carrito.clavePago);
      navigate(`/confirmacion/${paga.id}`);
    } catch (error) {
      const tipo = error instanceof ErrorApi ? error.tipo : '';
      const estado = error instanceof ErrorApi ? error.estado : 0;
      if (tipo === 'venta-ya-paga') {
        navigate(`/confirmacion/${orden.id}`);
      } else if (estado === 404) {
        cerrarCompra('no_existe');
      } else if (tipo === 'reserva-vencida') {
        // Ya no se puede pagar: se cancela para devolver el cupo y se muestra cómo quedó.
        await cancelarCompra(orden.id).catch(() => {});
        await consultarEstado(orden.id);
      } else if (tipo === 'pago-rechazado') {
        // El backend confirmó que no cobró: el próximo intento es otro pago y lleva otra clave.
        dispatch({ type: 'SET_CARRITO', payload: { ...carrito, clavePago: nuevaClave(), pagoSinResultado: null } });
        setErrorPago(mensajeDeError(error));
      } else if (estado === 0 || estado >= 500) {
        // Sin respuesta (504), sin conexión o error del servidor: no se sabe si se cobró. Se anota
        // el pago para poder repetirlo con la misma clave y se consulta cómo quedó la venta.
        dispatch({ type: 'SET_CARRITO', payload: { ...carrito, pagoSinResultado: datos } });
        await consultarEstado(orden.id);
      } else {
        // 'operacion-en-curso' (otro pago en curso, o un pago sin resultado que ya no se puede
        // repetir) y el resto: el mensaje del backend. La clave no cambia.
        setErrorPago(mensajeDeError(error));
      }
    } finally {
      setPagando(false);
    }
  }

  async function cancelar() {
    if (!carrito || !evento || !orden || cancelando) return;
    setErrorPago('');
    setCancelando(true);
    try {
      await cancelarCompra(orden.id);
    } catch (error) {
      if (error instanceof ErrorApi && error.tipo === 'venta-ya-paga') {
        navigate(`/confirmacion/${orden.id}`);
        return;
      }
      // Una venta que el backend ya no tiene está tan cancelada como se quería. Cualquier otro
      // error se muestra (por ejemplo 'operacion-en-curso': hay un pago sin resultado).
      if (!(error instanceof ErrorApi && error.estado === 404)) {
        setErrorPago(mensajeDeError(error));
        return;
      }
    } finally {
      setCancelando(false);
    }
    dispatch({ type: 'SET_CARRITO', payload: { ...carrito, ordenId: null, clavePago: null } });
    navigate(rutaDelEvento);
  }

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-semibold tracking-tight text-ink">Checkout</h1>
          {/* La cuenta regresiva aparece recién cuando hay una reserva que pueda vencer. Con un
              pago sin resultado no corre: Ventas retiene la venta hasta saber si se cobró. */}
          {orden && !sinResultado && (
            <p className={`font-mono-label text-sm ${timerCritico ? 'text-accent' : 'text-muted'}`}>
              Reserva: {minutos}:{segundos.toString().padStart(2, '0')}
            </p>
          )}
        </div>

        {/* Sin onVolver: con la reserva hecha no se vuelve a Titulares, porque ya viajaron. */}
        <Stepper pasos={['Titulares', 'Pago', 'Confirmación']} activo={paso} />

        <div className="mt-8 grid grid-cols-1 gap-10 md:grid-cols-[1fr_320px]">
          <div>
            {paso === 0 && (
              <div className="flex flex-col gap-5">
                {titulares.map((t, i) => {
                  const err = erroresTitulares[i] ?? {};
                  const esComprador = i === 0;
                  return (
                    <div key={i} className="border border-wire p-5">
                      <p className="font-mono-label mb-3 text-[11px] text-muted">
                        Entrada {i + 1} · {t.sectorNombre} · {money(t.precio)}
                      </p>
                      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                        <Input
                          label="Nombre"
                          value={t.nombre}
                          disabled={esComprador}
                          onChange={(e) => actualizarTitular(i, 'nombre', e.target.value)}
                          error={err.nombre}
                        />
                        <Input
                          label="Apellido"
                          value={t.apellido}
                          disabled={esComprador}
                          onChange={(e) => actualizarTitular(i, 'apellido', e.target.value)}
                          error={err.apellido}
                        />
                        <Input
                          label="DNI"
                          value={t.dni}
                          disabled={esComprador}
                          onChange={(e) => actualizarTitular(i, 'dni', soloDigitosDNI(e.target.value))}
                          error={err.dni}
                        />
                      </div>
                    </div>
                  );
                })}

                <p className="text-xs text-muted">
                  Al continuar reservamos tus lugares por {MINUTOS_RESERVA} minutos. Después los
                  titulares no se pueden cambiar.
                </p>

                {errorReserva && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                    {errorReserva}
                  </p>
                )}

                <div className="flex flex-wrap gap-3">
                  <Button loading={reservando} loadingText="Reservando…" onClick={reservar}>
                    Continuar
                  </Button>
                  {sinCupo && (
                    <Button variant="secondary" onClick={() => navigate(rutaDelEvento)}>
                      Volver al evento
                    </Button>
                  )}
                </div>
              </div>
            )}

            {/* El carrito tiene una reserva pero todavía no llegó del backend: se está retomando. */}
            {paso === 1 && !orden && (
              errorAlRetomar ? (
                <StatusMessage
                  titulo="No pudimos recuperar tu compra"
                  texto={errorAlRetomar}
                  accion="Reintentar"
                  onAccion={() => {
                    setErrorAlRetomar('');
                    setReintentosDeRetomar((n) => n + 1);
                  }}
                />
              ) : (
                <p role="status" className="text-sm text-muted">
                  Recuperando tu compra…
                </p>
              )
            )}

            {paso === 1 && orden && sinResultado && (
              <div className="flex flex-col gap-5" data-estado="pago-sin-resultado">
                <div className="border border-accent p-5">
                  <p className="text-lg font-medium text-ink">
                    {pagoSinResultado ? 'No sabemos si el pago se acreditó' : 'Tu compra sigue abierta'}
                  </p>
                  <p className="mt-2 text-sm text-graphite">
                    {pagoSinResultado
                      ? 'El cobro no tuvo respuesta. Tus lugares siguen reservados mientras se confirma el resultado: no pagues con otro medio ni empieces otra compra por estas entradas.'
                      : 'Terminó el plazo de la reserva, pero la compra sigue abierta: puede haber un pago sin resultado. Tus lugares siguen reservados hasta que se resuelva.'}
                  </p>
                  <p className="font-mono-label mt-3 text-[11px] text-muted">Orden #{orden.id}</p>
                </div>

                {errorPago && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                    {errorPago}
                  </p>
                )}

                <div className="flex flex-wrap gap-3">
                  {/* Solo si Ventas todavía ofrece pagar la venta y hay un pago para repetir tal cual. */}
                  {pagoSinResultado && orden.sePuedePagar && (
                    <Button loading={pagando} loadingText="Reintentando…" disabled={consultando} onClick={pagar}>
                      Reintentar pago
                    </Button>
                  )}
                  <Button
                    variant="secondary"
                    loading={consultando}
                    loadingText="Consultando…"
                    disabled={pagando}
                    onClick={() => consultarEstado(orden.id)}
                  >
                    Ver estado
                  </Button>
                  <Button variant="secondary" disabled={pagando} onClick={() => navigate('/mis-entradas')}>
                    Ir a Mis entradas
                  </Button>
                  <Button variant="secondary" disabled={pagando} onClick={() => navigate('/')}>
                    Volver al catálogo
                  </Button>
                </div>
              </div>
            )}

            {paso === 1 && orden && !sinResultado && (
              <div className="flex flex-col gap-5">
                <p className="text-xs text-muted">
                  Tus lugares están reservados. Para cambiar los titulares, cancelá la compra y
                  empezá de nuevo.
                </p>

                <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
                  {(
                    [
                      { id: 'pasarela', label: 'Pasarela externa' },
                      { id: 'tarjeta', label: 'Tarjeta' },
                      { id: 'transferencia', label: 'Transferencia' },
                    ] as { id: MedioPago; label: string }[]
                  ).map((opcion) => (
                    <button
                      key={opcion.id}
                      type="button"
                      onClick={() => setMedioPago(opcion.id)}
                      className={`h-16 border px-4 text-left text-sm font-medium ${
                        medioPago === opcion.id ? 'border-2 border-accent' : 'border-edge'
                      }`}
                    >
                      {opcion.label}
                    </button>
                  ))}
                </div>

                <AnimatePresence>
                  {medioPago === 'tarjeta' && (
                    <motion.div
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: 'auto', opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={{ duration: 0.26, ease: [0.16, 1, 0.3, 1] }}
                      className="overflow-hidden"
                    >
                      <div className="grid grid-cols-1 gap-4 border border-wire p-5 sm:grid-cols-2">
                        <Input
                          label="Número de tarjeta"
                          value={tarjeta.numero}
                          onChange={(e) => setTarjeta((p) => ({ ...p, numero: formatearNumeroTarjeta(e.target.value) }))}
                          error={erroresTarjeta.numero}
                          maxLength={19}
                        />
                        <Input
                          label="Titular"
                          value={tarjeta.titular}
                          onChange={(e) => setTarjeta((p) => ({ ...p, titular: e.target.value }))}
                          error={erroresTarjeta.titular}
                        />
                        <Input
                          label="Vencimiento (MM/AA)"
                          value={tarjeta.vencimiento}
                          onChange={(e) => setTarjeta((p) => ({ ...p, vencimiento: e.target.value }))}
                          error={erroresTarjeta.vencimiento}
                          maxLength={5}
                        />
                        <Input
                          label="CVV"
                          value={tarjeta.cvv}
                          onChange={(e) => setTarjeta((p) => ({ ...p, cvv: e.target.value.replace(/\D/g, '').slice(0, 3) }))}
                          error={erroresTarjeta.cvv}
                          maxLength={3}
                        />
                      </div>
                      {/* El cobro es simulado: esta ayuda es para poder mostrar cada caso en la demo. */}
                      <p className="mt-2 text-xs text-muted">
                        Cobro de prueba: una tarjeta terminada en 0002 se rechaza y una terminada en
                        0504 simula que la pasarela no responde. Cualquier otra se aprueba.
                      </p>
                    </motion.div>
                  )}
                </AnimatePresence>

                <p className="text-xs text-muted">Facturación electrónica automática al confirmar el pago.</p>

                {errorPago && (
                  <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
                    {errorPago}
                  </p>
                )}

                <div className="flex flex-wrap gap-3">
                  <Button loading={pagando} loadingText="Procesando pago…" disabled={cancelando} onClick={pagar}>
                    Pagar y confirmar
                  </Button>
                  <Button
                    variant="secondary"
                    loading={cancelando}
                    loadingText="Cancelando…"
                    disabled={pagando}
                    onClick={cancelar}
                  >
                    Cancelar compra
                  </Button>
                </div>
              </div>
            )}
          </div>

          <aside className="h-fit border border-wire p-6 md:sticky md:top-[104px]">
            <h2 className="font-mono-label text-[11px] text-muted">Resumen</h2>
            <div className="mt-4 flex flex-col gap-2">
              {lineasDelResumen.map((linea) => {
                const sector = evento.sectores.find((s) => s.id === linea.sectorId);
                if (!sector) return null;
                return (
                  <div key={linea.sectorId} className="flex justify-between text-sm">
                    <span>
                      {linea.cantidad} × {sector.nombre}
                    </span>
                    <span>{money(linea.cantidad * linea.precio)}</span>
                  </div>
                );
              })}
            </div>
            <div className="mt-4 flex justify-between border-t border-line pt-3 text-sm text-muted">
              <span>Subtotal</span>
              <span>{money(subtotal)}</span>
            </div>
            <div className="mt-2 flex justify-between text-sm text-muted">
              <span>Cargo por servicio (10%)</span>
              <span>{money(servicio)}</span>
            </div>
            <div className="mt-3 flex justify-between border-t border-line pt-3 text-lg font-semibold">
              <span>Total</span>
              <span>{money(total)}</span>
            </div>
          </aside>
        </div>
      </div>

      <Modal
        abierto={cierre !== null}
        onCerrar={() => navigate(rutaDelEvento)}
        titulo={cierre ? TEXTO_DE_CIERRE[cierre].titulo : ''}
      >
        <p className="text-sm text-graphite">{cierre && TEXTO_DE_CIERRE[cierre].texto}</p>
        <Button className="mt-5 w-full" onClick={() => navigate(rutaDelEvento)}>
          Volver al evento
        </Button>
      </Modal>
    </PageTransition>
  );
}
