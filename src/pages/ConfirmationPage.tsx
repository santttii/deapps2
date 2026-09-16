import { useEffect } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import { motion } from 'framer-motion';
import { useApp } from '../store/AppContext';
import { fecha, formatearDNI, money } from '../lib/format';
import { calcularTope } from '../lib/format';
import QRCode from '../components/QRCode';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';

export default function ConfirmationPage() {
  const { ordenId } = useParams();
  const { state, dispatch } = useApp();

  useEffect(() => {
    dispatch({ type: 'SET_CARRITO', payload: null });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const orden = state.ordenes.find((o) => o.id === ordenId);
  const entradas = state.entradas.filter((e) => e.ordenId === ordenId);
  const evento = orden ? state.eventos.find((e) => e.id === orden.eventoId) : undefined;

  if (!orden || !evento || entradas.length === 0) return <Navigate to="/mis-entradas" replace />;

  const primeraEntrada = entradas[0];
  const sector = evento.sectores.find((s) => s.id === primeraEntrada.sectorId);
  const tope = calcularTope(primeraEntrada.precioPagado);

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
            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-ink">{evento.titulo}</h1>

            <div className="mt-6 grid grid-cols-1 gap-4 border-t border-line pt-5 sm:grid-cols-3">
              <div>
                <p className="font-mono-label text-[10px] text-muted">Titular</p>
                <p className="text-sm text-ink">
                  {primeraEntrada.titular.nombre} {primeraEntrada.titular.apellido}
                </p>
              </div>
              <div>
                <p className="font-mono-label text-[10px] text-muted">Documento</p>
                <p className="text-sm text-ink">{formatearDNI(primeraEntrada.titular.dni)}</p>
              </div>
              <div>
                <p className="font-mono-label text-[10px] text-muted">Sector</p>
                <p className="text-sm text-ink">{sector?.nombre}</p>
              </div>
            </div>

            <h2 className="mt-8 text-lg font-semibold tracking-tight text-ink">Tu entrada</h2>
            <div className="mt-4 flex flex-col items-start gap-5 sm:flex-row">
              <motion.div
                initial={{ opacity: 0, scale: 0.86, filter: 'blur(8px)' }}
                animate={{ opacity: 1, scale: 1, filter: 'blur(0px)' }}
                transition={{ duration: 0.5, ease: [0.16, 1, 0.3, 1] }}
              >
                <QRCode id={primeraEntrada.id} />
              </motion.div>
              <ul className="flex flex-col gap-2 text-sm text-graphite">
                <li>Válida para un único ingreso.</li>
                <li>En puerta se contrasta contra el documento del titular.</li>
                <li>No compartas este código: es personal e intransferible.</li>
              </ul>
            </div>

            <div className="mt-8 flex flex-wrap gap-3">
              <Link to="/mis-entradas">
                <Button>Ver en mis entradas</Button>
              </Link>
              <Button variant="secondary" onClick={() => window.print()}>
                Descargar PDF
              </Button>
              <Button variant="secondary">Agregar al calendario</Button>
            </div>
          </div>

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
                <span>Medio de pago</span>
                <span className="capitalize">{orden.medioPago}</span>
              </div>
              <div className="flex justify-between text-muted">
                <span>Factura</span>
                <span>Emitida</span>
              </div>
              <div className="flex justify-between text-muted">
                <span>Fecha</span>
                <span>{fecha(orden.creadaISO)}</span>
              </div>
            </div>
            <p className="mt-4 border-t border-line pt-3 text-xs text-muted">
              Podés revender esta entrada hasta {money(tope)} (tope +10%) desde Mis
              entradas.
            </p>
          </aside>
        </div>
      </div>
    </PageTransition>
  );
}
