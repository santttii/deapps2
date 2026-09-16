import { useEffect, useMemo, useState } from 'react';
import { Navigate, useNavigate } from 'react-router-dom';
import { AnimatePresence, motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { money, sleep } from '../lib/format';
import {
  esCVVValido,
  esDNIValido,
  esNombreValido,
  esNumeroTarjetaValido,
  esVencimientoValido,
  formatearNumeroTarjeta,
  soloDigitosDNI,
} from '../lib/validate';
import { MINUTOS_RESERVA, TASA_SERVICIO, type Asistente, type Entrada, type MedioPago, type Orden } from '../types';
import Stepper from '../components/Stepper';
import Input from '../components/Input';
import Button from '../components/Button';
import Modal from '../components/Modal';
import PageTransition from '../components/PageTransition';

interface FilaTitular {
  sectorId: string;
  sectorNombre: string;
  precio: number;
  nombre: string;
  apellido: string;
  dni: string;
  asignarDespues: boolean;
}

function generarId(prefijo: string, largo: number): string {
  const alfabeto = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let out = '';
  for (let i = 0; i < largo; i++) out += alfabeto[Math.floor(Math.random() * alfabeto.length)];
  return `${prefijo}-${out}`;
}

export default function CheckoutPage() {
  const { state, dispatch, usuarioActual } = useApp();
  const navigate = useNavigate();
  const carrito = state.carrito;
  const evento = carrito ? state.eventos.find((e) => e.id === carrito.eventoId) : undefined;

  const [paso, setPaso] = useState(0);
  const [segundosRestantes, setSegundosRestantes] = useState(MINUTOS_RESERVA * 60);
  const [reservaVencida, setReservaVencida] = useState(false);
  const [medioPago, setMedioPago] = useState<MedioPago>('pasarela');
  const [tarjeta, setTarjeta] = useState({ numero: '', vencimiento: '', cvv: '', titular: '' });
  const [erroresTarjeta, setErroresTarjeta] = useState<Record<string, string>>({});
  const [procesando, setProcesando] = useState(false);

  const filasIniciales: FilaTitular[] = useMemo(() => {
    if (!carrito || !evento) return [];
    const filas: FilaTitular[] = [];
    carrito.items.forEach((item) => {
      const sector = evento.sectores.find((s) => s.id === item.sectorId);
      if (!sector) return;
      for (let i = 0; i < item.cantidad; i++) {
        filas.push({
          sectorId: sector.id,
          sectorNombre: sector.nombre,
          precio: sector.precio,
          nombre: i === 0 && filas.length === 0 ? usuarioActual?.nombre ?? '' : '',
          apellido: i === 0 && filas.length === 0 ? usuarioActual?.apellido ?? '' : '',
          dni: i === 0 && filas.length === 0 ? usuarioActual?.dni ?? '' : '',
          asignarDespues: false,
        });
      }
    });
    return filas;
  }, [carrito, evento, usuarioActual]);

  const [titulares, setTitulares] = useState<FilaTitular[]>(filasIniciales);
  const [erroresTitulares, setErroresTitulares] = useState<Record<number, { nombre?: string; apellido?: string; dni?: string }>>({});

  useEffect(() => {
    setTitulares(filasIniciales);
  }, [filasIniciales]);

  useEffect(() => {
    if (reservaVencida) return;
    const interval = setInterval(() => {
      setSegundosRestantes((prev) => {
        if (prev <= 1) {
          setReservaVencida(true);
          clearInterval(interval);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(interval);
  }, [reservaVencida]);

  if (!carrito || !evento) return <Navigate to="/" replace />;

  const minutos = Math.floor(segundosRestantes / 60);
  const segundos = segundosRestantes % 60;
  const timerCritico = segundosRestantes <= 120;

  const subtotal = titulares.reduce((acc, t) => acc + t.precio, 0);
  const servicio = Math.round(subtotal * TASA_SERVICIO);
  const total = subtotal + servicio;

  function actualizarTitular(index: number, campo: keyof FilaTitular, valor: string | boolean) {
    setTitulares((prev) => prev.map((t, i) => (i === index ? { ...t, [campo]: valor } : t)));
  }

  function validarTitulares(): boolean {
    const errores: typeof erroresTitulares = {};
    const dnisVistos = new Map<string, number>();
    titulares.forEach((t, i) => {
      if (t.asignarDespues) return;
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
          dnisVistos.set(digits, i);
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

  function irAPaso2() {
    if (validarTitulares()) setPaso(1);
  }

  async function confirmarPago() {
    if (!carrito || !evento || !validarPago()) return;
    setProcesando(true);
    await sleep(1400);

    const idOrden = `A-${Math.floor(10000 + Math.random() * 89999)}`;

    const asistentes: Asistente[] = titulares.map((t) => ({
      nombre: t.asignarDespues ? '' : t.nombre,
      apellido: t.asignarDespues ? '' : t.apellido,
      dni: t.asignarDespues ? '' : soloDigitosDNI(t.dni),
    }));

    const orden: Orden = {
      id: idOrden,
      eventoId: evento.id,
      items: carrito.items.map((it) => ({
        sectorId: it.sectorId,
        cantidad: it.cantidad,
        precioUnitario: evento.sectores.find((s) => s.id === it.sectorId)?.precio ?? 0,
      })),
      asistentes,
      subtotal,
      servicio,
      total,
      medioPago,
      creadaISO: new Date().toISOString(),
    };

    const entradas: Entrada[] = titulares.map((t, i) => ({
      id: generarId('PLC', 6),
      ordenId: idOrden,
      eventoId: evento.id,
      sectorId: t.sectorId,
      fila: String(1 + (i % 30)),
      precioPagado: t.precio,
      titular: asistentes[i],
      estado: 'valida',
      origen: 'compra',
      emitidaISO: new Date().toISOString(),
    }));

    const stockDescontado = carrito.items.map((it) => ({
      eventoId: evento.id,
      sectorId: it.sectorId,
      cantidad: it.cantidad,
    }));

    dispatch({ type: 'CREAR_ORDEN', payload: { orden, entradas, stockDescontado } });
    setProcesando(false);
    navigate(`/confirmacion/${idOrden}`);
  }

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <div className="mb-6 flex items-center justify-between">
          <h1 className="text-2xl font-semibold tracking-tight text-ink">Checkout</h1>
          <p className={`font-mono-label text-sm ${timerCritico ? 'text-accent' : 'text-muted'}`}>
            Reserva: {minutos}:{segundos.toString().padStart(2, '0')}
          </p>
        </div>

        <Stepper
          pasos={['Titulares', 'Pago', 'Confirmación']}
          activo={paso}
          onVolver={(i) => setPaso(i)}
        />

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
                          disabled={esComprador || t.asignarDespues}
                          onChange={(e) => actualizarTitular(i, 'nombre', e.target.value)}
                          error={err.nombre}
                        />
                        <Input
                          label="Apellido"
                          value={t.apellido}
                          disabled={esComprador || t.asignarDespues}
                          onChange={(e) => actualizarTitular(i, 'apellido', e.target.value)}
                          error={err.apellido}
                        />
                        <Input
                          label="DNI"
                          value={t.dni}
                          disabled={esComprador || t.asignarDespues}
                          onChange={(e) => actualizarTitular(i, 'dni', soloDigitosDNI(e.target.value))}
                          error={err.dni}
                        />
                      </div>
                      {!esComprador && (
                        <label className="mt-3 flex items-center gap-2 text-sm text-muted">
                          <input
                            type="checkbox"
                            checked={t.asignarDespues}
                            onChange={(e) => {
                              actualizarTitular(i, 'asignarDespues', e.target.checked);
                              if (e.target.checked) {
                                actualizarTitular(i, 'nombre', '');
                                actualizarTitular(i, 'apellido', '');
                                actualizarTitular(i, 'dni', '');
                              }
                            }}
                          />
                          asignar después por link
                        </label>
                      )}
                    </div>
                  );
                })}
                <Button className="self-start" onClick={irAPaso2}>
                  Continuar
                </Button>
              </div>
            )}

            {paso === 1 && (
              <div className="flex flex-col gap-5">
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
                    </motion.div>
                  )}
                </AnimatePresence>

                <p className="text-xs text-muted">Facturación electrónica automática al confirmar el pago.</p>

                <Button
                  className="self-start"
                  loading={procesando}
                  loadingText="Procesando pago…"
                  onClick={confirmarPago}
                >
                  Pagar y confirmar
                </Button>
              </div>
            )}
          </div>

          <aside className="h-fit border border-wire p-6 md:sticky md:top-[104px]">
            <h2 className="font-mono-label text-[11px] text-muted">Resumen</h2>
            <div className="mt-4 flex flex-col gap-2">
              {carrito.items.map((it) => {
                const sector = evento.sectores.find((s) => s.id === it.sectorId);
                if (!sector) return null;
                return (
                  <div key={it.sectorId} className="flex justify-between text-sm">
                    <span>
                      {it.cantidad} × {sector.nombre}
                    </span>
                    <span>{money(it.cantidad * sector.precio)}</span>
                  </div>
                );
              })}
            </div>
            <div className="mt-4 flex justify-between border-t border-line pt-3 text-sm text-muted">
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

      <Modal abierto={reservaVencida} onCerrar={() => navigate(`/evento/${evento.slug}`)} titulo="Se liberó tu reserva">
        <p className="text-sm text-graphite">
          Pasaron {MINUTOS_RESERVA} minutos y liberamos los lugares reservados. Volvé a
          elegir tus entradas.
        </p>
        <Button className="mt-5 w-full" onClick={() => navigate(`/evento/${evento.slug}`)}>
          Volver al evento
        </Button>
      </Modal>
    </PageTransition>
  );
}
