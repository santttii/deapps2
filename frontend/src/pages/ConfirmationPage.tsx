import { useEffect, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { ErrorApi } from '../lib/api';
import { fecha, formatearDNI, money } from '../lib/format';
import { obtenerOrden } from '../servicios/ventas';
import type { Entrada, Orden } from '../types';
import QRCode from '../components/QRCode';
import Button from '../components/Button';
import StatusMessage from '../components/StatusMessage';
import PageTransition from '../components/PageTransition';

// La confirmación sale del backend, no de lo guardado en el navegador: por eso recargar la página
// la vuelve a mostrar. Al pagar, la venta queda 'confirmada' y las entradas se emiten después, en
// un proceso aparte del backend. Hasta que la venta pasa a 'emitida' se la vuelve a consultar:
// una vez por segundo y hasta 30 veces (hoy la emisión tarda menos de un segundo).
const PAUSA_MS = 1000;
const MAX_CONSULTAS = 30;

// 'esperando': todavía no hay entradas para mostrar (primera consulta o emisión en curso).
// 'lista': la venta está emitida y llegaron sus entradas.
// 'demorada': se agotaron las consultas.
// 'no_disponible': el usuario no tiene una compra paga con ese id.
type Fase = 'esperando' | 'lista' | 'demorada' | 'no_disponible';

export default function ConfirmationPage() {
  const { ordenId } = useParams();
  const { state, dispatch, usuarioActual } = useApp();
  const compradorId = usuarioActual?.id;
  const [fase, setFase] = useState<Fase>('esperando');
  // La venta como la devolvió el backend la última vez; null hasta la primera respuesta.
  const [orden, setOrden] = useState<Orden | null>(null);
  const [entradas, setEntradas] = useState<Entrada[]>([]);
  const [vueltas, setVueltas] = useState(0); // sube con "Volver a consultar"

  // Llegar a la confirmación cierra la compra en curso: se vacía el carrito de esta venta. Un
  // carrito de otra compra no se toca.
  useEffect(() => {
    if (state.carrito?.ordenId === ordenId) dispatch({ type: 'SET_CARRITO', payload: null });
  }, [state.carrito, ordenId, dispatch]);

  useEffect(() => {
    if (!ordenId || !compradorId) return;
    let vigente = true;
    let temporizador: ReturnType<typeof setTimeout> | undefined;
    let consultas = 0;

    const consultar = async () => {
      consultas += 1;
      try {
        const recibida = await obtenerOrden(ordenId);
        if (!vigente) return;
        if (recibida.estado !== 'confirmada' && recibida.estado !== 'emitida') {
          setFase('no_disponible'); // sin pagar, cancelada o vencida: no hay nada que confirmar
          return;
        }
        setOrden(recibida);
        // La venta trae sus entradas; se muestran cuando está emitida, porque antes no tienen QR.
        if (recibida.estado === 'emitida') {
          setEntradas(recibida.entradas);
          setFase('lista');
          return;
        }
      } catch (error) {
        if (!vigente) return;
        // 404: la venta no existe o es de otra persona. 403: la cuenta no es de comprador.
        if (error instanceof ErrorApi && (error.estado === 404 || error.estado === 403)) {
          setFase('no_disponible');
          return;
        }
        // Cualquier otra falla (sin conexión, Ventas reiniciándose) cuenta como una consulta más.
      }
      if (consultas < MAX_CONSULTAS) temporizador = setTimeout(consultar, PAUSA_MS);
      else setFase('demorada');
    };

    consultar();
    return () => {
      vigente = false;
      clearTimeout(temporizador);
    };
  }, [ordenId, compradorId, vueltas]);

  function volverAConsultar() {
    setFase('esperando');
    setVueltas((n) => n + 1);
  }

  if (fase === 'no_disponible') return <Navigate to="/mis-entradas" replace />;

  // Todavía no llegó ninguna respuesta del backend.
  if (!orden) {
    return (
      <PageTransition>
        <div className="mx-auto max-w-[1240px] px-7 py-10">
          {fase === 'demorada' ? (
            <StatusMessage
              titulo="No pudimos consultar tu compra"
              texto="Probá de nuevo en un momento. Si el pago se aprobó, tus entradas van a aparecer en Mis entradas."
              accion="Volver a consultar"
              onAccion={volverAConsultar}
            />
          ) : (
            <p role="status" className="text-sm text-muted">
              Consultando tu compra…
            </p>
          )}
        </div>
      </PageTransition>
    );
  }

  const evento = state.eventos.find((e) => e.id === orden.eventoId);
  const cantidad = orden.items.reduce((acc, it) => acc + it.cantidad, 0);

  return (
    <PageTransition>
      <div className="mx-auto max-w-[1240px] px-7 py-10">
        <div className="grid grid-cols-1 gap-10 md:grid-cols-[1fr_320px]">
          <div className="border border-accent p-7">
            <motion.div
              initial={{ scale: 0.4, opacity: 0 }}
              animate={{ scale: [0.4, 1.12, 1], opacity: 1 }}
              transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
              className="flex h-12 w-12 items-center justify-center rounded-dot bg-accent text-white"
              aria-hidden="true"
            >
              ✓
            </motion.div>
            <p className="mt-4 text-lg font-medium text-ink">Pago aprobado · orden #{orden.id}</p>
            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-ink">{evento?.titulo}</h1>

            {fase === 'esperando' && (
              <p role="status" className="mt-6 border-t border-line pt-5 text-sm text-graphite">
                Emitiendo {cantidad === 1 ? 'tu entrada' : `tus ${cantidad} entradas`}…
              </p>
            )}

            {fase === 'demorada' && (
              <div className="mt-6 border-t border-line pt-5">
                <p role="status" className="text-sm text-graphite">
                  Tu pago está aprobado. La emisión está tardando más de lo normal; tus entradas
                  van a aparecer en Mis entradas.
                </p>
                <div className="mt-5 flex flex-wrap gap-3">
                  <Button onClick={volverAConsultar}>Volver a consultar</Button>
                  <Link to="/mis-entradas">
                    <Button variant="secondary">Ir a Mis entradas</Button>
                  </Link>
                </div>
              </div>
            )}

            {fase === 'lista' && (
              <>
                <h2 className="mt-8 text-lg font-semibold tracking-tight text-ink">
                  {entradas.length === 1 ? 'Tu entrada' : 'Tus entradas'}
                </h2>
                <div className="mt-2 flex flex-col">
                  {entradas.map((entrada) => (
                    <motion.div
                      key={entrada.id}
                      initial={{ opacity: 0, scale: 0.96 }}
                      animate={{ opacity: 1, scale: 1 }}
                      transition={{ duration: 0.4, ease: [0.16, 1, 0.3, 1] }}
                      className="flex flex-col items-start gap-5 border-t border-line py-5 sm:flex-row"
                    >
                      <QRCode id={entrada.codigoQr ?? entrada.id} size={160} />
                      <div className="grid grid-cols-2 gap-4 text-sm text-ink">
                        <div>
                          <p className="font-mono-label text-[10px] text-muted">Entrada</p>
                          <p>{entrada.id}</p>
                        </div>
                        <div>
                          <p className="font-mono-label text-[10px] text-muted">Sector</p>
                          <p>{evento?.sectores.find((s) => s.id === entrada.sectorId)?.nombre ?? '—'}</p>
                        </div>
                        <div>
                          <p className="font-mono-label text-[10px] text-muted">Titular</p>
                          <p>
                            {entrada.titular.nombre} {entrada.titular.apellido}
                          </p>
                        </div>
                        <div>
                          <p className="font-mono-label text-[10px] text-muted">Documento</p>
                          <p>{formatearDNI(entrada.titular.dni)}</p>
                        </div>
                      </div>
                    </motion.div>
                  ))}
                </div>

                <ul className="flex flex-col gap-2 border-t border-line pt-5 text-sm text-graphite">
                  <li>Cada entrada es válida para un único ingreso.</li>
                  <li>En puerta se contrasta contra el documento del titular.</li>
                  <li>No compartas estos códigos: son personales e intransferibles.</li>
                </ul>

                <div className="mt-8 flex flex-wrap gap-3">
                  <Link to="/mis-entradas">
                    <Button>Ver en mis entradas</Button>
                  </Link>
                  <Button variant="secondary" onClick={() => window.print()}>
                    Descargar PDF
                  </Button>
                  <Button variant="secondary">Agregar al calendario</Button>
                </div>
              </>
            )}
          </div>

          {/* Los importes y la fecha son los de la venta en el backend. */}
          <aside className="h-fit border border-wire p-6">
            <h2 className="font-mono-label text-[11px] text-muted">Comprobante</h2>
            <div className="mt-4 flex flex-col gap-2 text-sm">
              <div className="flex justify-between">
                <span className="text-muted">Subtotal</span>
                <span>{money(orden.subtotal)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted">Servicio</span>
                <span>{money(orden.servicio)}</span>
              </div>
              <div className="flex justify-between border-t border-line pt-2 font-semibold">
                <span>Total pagado</span>
                <span>{money(orden.total)}</span>
              </div>
              <div className="mt-3 flex justify-between text-muted">
                <span>Fecha</span>
                <span>{fecha(orden.creadaISO)}</span>
              </div>
            </div>
            <p className="mt-4 border-t border-line pt-3 text-xs text-muted">
              Podés revender cada entrada hasta un 10% por encima de lo que pagaste, desde Mis
              entradas.
            </p>
          </aside>
        </div>
      </div>
    </PageTransition>
  );
}
