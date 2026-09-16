import { useState } from 'react';
import { motion } from 'framer-motion';
import { organizerMetrics as m } from '../data/organizer';
import { money } from '../lib/format';
import StatCard from '../components/StatCard';
import ProgressBar from '../components/ProgressBar';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';

const ITEMS_SIDEBAR = ['Eventos', 'Ventas', 'Reventa', 'Accesos / staff', 'Facturación', 'Configuración'];

export default function OrganizerPage() {
  const [seccionActiva, setSeccionActiva] = useState('Eventos');
  const maxVentas = Math.max(...m.ventasUltimos10Dias);
  const indicesTop2 = [...m.ventasUltimos10Dias]
    .map((v, i) => [v, i] as const)
    .sort((a, b) => b[0] - a[0])
    .slice(0, 2)
    .map(([, i]) => i);

  const totalOcupacion = m.ocupacionPorSector.reduce((acc, s) => acc + s.vendidas, 0);
  const totalCupoOcupacion = m.ocupacionPorSector.reduce((acc, s) => acc + s.cupo, 0);
  const porcentajeOcupacion = Math.round((totalOcupacion / totalCupoOcupacion) * 100);

  return (
    <PageTransition>
      <div className="mx-auto flex max-w-[1240px] gap-10 px-7 py-10">
        <aside className="hidden w-[220px] shrink-0 border border-wire md:block">
          <p className="font-mono-label border-b border-wire px-4 py-3 text-[10px] text-muted">
            Organizador
          </p>
          <nav className="flex flex-col">
            {ITEMS_SIDEBAR.map((item) => (
              <button
                key={item}
                onClick={() => setSeccionActiva(item)}
                className={`px-4 py-3 text-left text-sm ${
                  seccionActiva === item ? 'bg-fill font-medium text-ink' : 'text-graphite'
                }`}
              >
                {item}
              </button>
            ))}
          </nav>
        </aside>

        <div className="flex-1">
          <div className="flex items-center justify-between">
            <h1 className="text-2xl font-semibold tracking-tight text-ink">Eventos</h1>
            <Button>+ Crear evento</Button>
          </div>

          <div className="mt-8 grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-4">
            <StatCard etiqueta="Entradas vendidas" valor={m.entradasVendidas} />
            <StatCard etiqueta="Recaudado" valor={m.recaudado} formatear={money} />
            <StatCard etiqueta="Cupo restante" valor={m.cupoRestante} />
            <StatCard etiqueta="En reventa" valor={m.enReventa} />
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">Ventas por día</h2>
          <div className="mt-4 flex h-40 items-end gap-3 border border-wire p-5">
            {m.ventasUltimos10Dias.map((v, i) => (
              <motion.div
                key={i}
                className={`flex-1 origin-bottom ${indicesTop2.includes(i) ? 'bg-accent' : 'bg-slab'}`}
                style={{ height: `${(v / maxVentas) * 100}%` }}
                initial={{ scaleY: 0 }}
                animate={{ scaleY: 1 }}
                transition={{ duration: 0.4, delay: i * 0.04, ease: [0.16, 1, 0.3, 1] }}
                title={`${v} entradas`}
              />
            ))}
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">Mis eventos</h2>
          <div className="mt-4 overflow-x-auto border border-wire">
            <table className="w-full text-sm">
              <thead className="bg-fill">
                <tr>
                  {['Evento', 'Fecha', 'Vendidas', 'Cupo', 'Estado'].map((h) => (
                    <th key={h} className="font-mono-label px-4 py-3 text-left text-[10px] text-muted">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {m.misEventos.map((e) => (
                  <tr key={e.titulo} className={`border-t border-wire ${e.estado === 'borrador' ? 'text-faint' : ''}`}>
                    <td className="px-4 py-3">{e.titulo}</td>
                    <td className="px-4 py-3">{e.fecha}</td>
                    <td className="px-4 py-3">{e.vendidas.toLocaleString('es-AR')}</td>
                    <td className="px-4 py-3">{e.cupo.toLocaleString('es-AR')}</td>
                    <td className="px-4 py-3 capitalize">{e.estado}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">Ocupación por sector</h2>
          <div className="mt-4 flex flex-col gap-4 border border-wire p-5">
            {m.ocupacionPorSector.map((s) => (
              <div key={s.nombre} className="flex items-center gap-4">
                <span className="w-32 shrink-0 text-sm">{s.nombre}</span>
                <div className="flex-1">
                  <ProgressBar porcentaje={(s.vendidas / s.cupo) * 100} altura={8} />
                </div>
                <span className="font-mono-label w-28 shrink-0 text-right text-[11px] text-muted">
                  {s.vendidas.toLocaleString('es-AR')} / {s.cupo.toLocaleString('es-AR')}
                </span>
              </div>
            ))}
            <p className="font-mono-label mt-2 border-t border-wire pt-3 text-right text-[11px] text-ink">
              {totalOcupacion.toLocaleString('es-AR')} / {totalCupoOcupacion.toLocaleString('es-AR')} · {porcentajeOcupacion}%
            </p>
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">Monitor de reventa oficial</h2>
          <div className="mt-4 border border-wire p-5">
            <div className="grid grid-cols-3 gap-4 text-center">
              <div>
                <p className="text-2xl font-semibold text-accent">{m.enReventa}</p>
                <p className="font-mono-label text-[10px] text-muted">activas</p>
              </div>
              <div>
                <p className="text-2xl font-semibold text-ink">{m.vendidasPorReventa}</p>
                <p className="font-mono-label text-[10px] text-muted">vendidas</p>
              </div>
              <div>
                <p className="text-2xl font-semibold text-ink">{m.rechazadasPorTope}</p>
                <p className="font-mono-label text-[10px] text-muted">rechazadas por tope</p>
              </div>
            </div>
            <p className="mt-5 text-sm text-graphite">
              Precio promedio de reventa: <span className="font-semibold">{money(m.precioPromedioReventa)}</span>
            </p>
            <div className="mt-2">
              <ProgressBar porcentaje={96} />
            </div>
            <p className="mt-4 text-xs text-muted">
              El tope lo fija la plataforma: el organizador no puede habilitar reventa
              sin techo.
            </p>
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">
            Accesos en vivo · staff en puerta
          </h2>
          <div className="mt-4 overflow-x-auto border border-wire">
            <table className="w-full text-sm">
              <thead className="bg-fill">
                <tr>
                  {['Puesto', 'Dispositivo', 'Escaneos', 'Estado'].map((h) => (
                    <th key={h} className="font-mono-label px-4 py-3 text-left text-[10px] text-muted">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {m.puestosStaff.map((p) => (
                  <tr key={p.puesto} className="border-t border-wire">
                    <td className="px-4 py-3">{p.puesto}</td>
                    <td className="px-4 py-3">{p.dispositivo}</td>
                    <td className="px-4 py-3">{p.escaneos.toLocaleString('es-AR')}</td>
                    <td className="px-4 py-3">
                      {p.estado === 'en línea' ? (
                        <span className="flex items-center gap-2 text-accent">
                          <span className="h-1.5 w-1.5 animate-pulseDot rounded-dot bg-accent" /> en línea
                        </span>
                      ) : (
                        <span className="text-faint">offline · sincroniza</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="mt-4 flex flex-wrap gap-3">
            <Button>+ Invitar staff</Button>
            <Button variant="secondary">Exportar registro de accesos</Button>
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">Liquidación</h2>
          <div className="mt-4 grid grid-cols-1 gap-4 border border-wire p-5 sm:grid-cols-4">
            <div>
              <p className="font-mono-label text-[10px] text-muted">Venta bruta</p>
              <p className="text-lg font-semibold">{money(m.liquidacion.bruto)}</p>
            </div>
            <div>
              <p className="font-mono-label text-[10px] text-muted">Comisión plataforma</p>
              <p className="text-lg font-semibold">{money(m.liquidacion.comision)}</p>
            </div>
            <div>
              <p className="font-mono-label text-[10px] text-muted">Reembolsos</p>
              <p className="text-lg font-semibold">{money(m.liquidacion.reembolsos)}</p>
            </div>
            <div>
              <p className="font-mono-label text-[10px] text-muted">A liquidar</p>
              <p className="text-lg font-semibold text-accent">{money(m.liquidacion.aLiquidar)}</p>
            </div>
          </div>
          <p className="mt-3 text-sm text-muted">
            Factura emitida · acreditación estimada en 48 hs.
          </p>
          <Button variant="secondary" className="mt-4 border-accent text-accent">
            Descargar comprobantes
          </Button>
        </div>
      </div>
    </PageTransition>
  );
}
