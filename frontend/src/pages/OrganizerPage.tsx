import { useEffect, useState } from 'react';
import { useApp } from '../store/AppContext';
import { mensajeDeError } from '../lib/api';
import { fecha as formatearFecha, fechaHora, money } from '../lib/format';
import { useReventas } from '../lib/useReventas';
import { obtenerPerfil } from '../servicios/usuarios';
import { listarEscaneos, type Escaneo } from '../servicios/validacion';
import StatCard from '../components/StatCard';
import ProgressBar from '../components/ProgressBar';
import Button from '../components/Button';
import PageTransition from '../components/PageTransition';
import CreateEventModal from '../components/CreateEventModal';

const ITEMS_SIDEBAR = ['Eventos', 'Ventas', 'Reventa', 'Accesos / staff', 'Facturación', 'Configuración'];

const ETIQUETA_RESULTADO: Record<Escaneo['resultado'], string> = {
  VALIDA: 'ingresó',
  YA_USADA: 'ya usada',
  INEXISTENTE: 'no existe',
  OTRO_EVENTO: 'otro evento',
  NO_HABILITADA: 'no habilitada',
  SIN_RESPUESTA: 'sin respuesta',
};

type Accesos = { fase: 'cargando' } | { fase: 'error'; mensaje: string } | { fase: 'lista'; escaneos: Escaneo[] };

// Panel del organizador. Todo lo que muestra sale del backend: cupo y vendidas por sector de
// Eventos (GET /api/eventos, ya cargado en el catálogo), las publicaciones activas de Reventa y el
// registro de la puerta de Validación (GET /api/v1/validacion/escaneos?eventoId=). Lo que todavía
// no tiene servicio (ventas por día, liquidación) no se muestra en vez de mostrarse con datos fijos.
export default function OrganizerPage() {
  const { state } = useApp();
  const [seccionActiva, setSeccionActiva] = useState('Eventos');
  const [modalCrearAbierto, setModalCrearAbierto] = useState(false);
  const [eventoElegido, setEventoElegido] = useState('');
  const [accesos, setAccesos] = useState<Accesos>({ fase: 'cargando' });
  const [vueltasAccesos, setVueltasAccesos] = useState(0);

  const reventas = useReventas(state.eventos.map((e) => e.id));
  const evento = state.eventos.find((e) => e.id === eventoElegido) ?? state.eventos[0];
  const eventoId = evento?.id;

  useEffect(() => {
    if (!eventoId) return;
    let vigente = true;
    listarEscaneos(eventoId)
      .then((escaneos) => vigente && setAccesos({ fase: 'lista', escaneos }))
      .catch((error: unknown) => vigente && setAccesos({ fase: 'error', mensaje: mensajeDeError(error) }));
    return () => {
      vigente = false;
    };
  }, [eventoId, vueltasAccesos]);

  function elegirEvento(id: string) {
    setEventoElegido(id);
    setAccesos({ fase: 'cargando' });
  }

  function abrirModalCrear() {
    // Se confirma la sesión antes de que el organizador escriba. Si el token ya no sirve (por
    // ejemplo, porque se reinició el servidor), api.ts cierra la sesión acá y no recién al
    // enviar el formulario. El resultado no se usa.
    obtenerPerfil().catch(() => undefined);
    setModalCrearAbierto(true);
  }

  const sectores = state.eventos.flatMap((e) => e.sectores);
  const vendidas = sectores.reduce((acc, s) => acc + s.vendidas, 0);
  const cupo = sectores.reduce((acc, s) => acc + s.cupo, 0);
  // A precio de lista: no incluye el cargo de servicio ni lo que se movió en la reventa.
  const recaudado = sectores.reduce((acc, s) => acc + s.vendidas * s.precio, 0);

  const publicacionesDelEvento = reventas.publicaciones.filter((p) => p.eventoId === eventoId);
  const promedioReventa =
    publicacionesDelEvento.length > 0
      ? publicacionesDelEvento.reduce((acc, p) => acc + p.precio, 0) / publicacionesDelEvento.length
      : 0;

  const sectoresEvento = evento?.sectores ?? [];
  const vendidasEvento = sectoresEvento.reduce((acc, s) => acc + s.vendidas, 0);
  const cupoEvento = sectoresEvento.reduce((acc, s) => acc + s.cupo, 0);
  const porcentajeEvento = cupoEvento > 0 ? Math.round((vendidasEvento / cupoEvento) * 100) : 0;

  const escaneos = accesos.fase === 'lista' ? accesos.escaneos : [];
  const ingresos = escaneos.filter((e) => e.resultado === 'VALIDA').length;
  const rechazos = escaneos.filter((e) => e.resultado !== 'VALIDA' && e.resultado !== 'SIN_RESPUESTA').length;

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

        <div className="min-w-0 flex-1">
          <div className="flex items-center justify-between">
            <h1 className="text-2xl font-semibold tracking-tight text-ink">Eventos</h1>
            <Button onClick={abrirModalCrear}>+ Crear evento</Button>
          </div>

          <div className="mt-8 grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-4">
            <StatCard etiqueta="Entradas vendidas" valor={vendidas} />
            <StatCard etiqueta="Recaudado (precio de lista)" valor={recaudado} formatear={money} />
            <StatCard etiqueta="Cupo restante" valor={cupo - vendidas} />
            <StatCard etiqueta="En reventa" valor={reventas.publicaciones.length} />
          </div>

          <h2 className="mt-10 text-lg font-semibold tracking-tight text-ink">Mis eventos</h2>
          <div className="mt-4 overflow-x-auto border border-wire">
            <table className="w-full text-sm">
              <thead className="bg-fill">
                <tr>
                  {['Evento', 'Fecha', 'Vendidas', 'Cupo'].map((h) => (
                    <th key={h} className="font-mono-label px-4 py-3 text-left text-[10px] text-muted">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {state.eventos.map((ev) => (
                  <tr key={ev.id} className="border-t border-wire">
                    <td className="px-4 py-3">{ev.titulo}</td>
                    <td className="px-4 py-3">{formatearFecha(ev.fechaISO)}</td>
                    <td className="px-4 py-3">
                      {ev.sectores.reduce((acc, s) => acc + s.vendidas, 0).toLocaleString('es-AR')}
                    </td>
                    <td className="px-4 py-3">
                      {ev.sectores.reduce((acc, s) => acc + s.cupo, 0).toLocaleString('es-AR')}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {evento && (
            <>
              <div className="mt-10 flex flex-wrap items-center justify-between gap-3">
                <h2 className="text-lg font-semibold tracking-tight text-ink">Detalle por evento</h2>
                <select
                  aria-label="Evento"
                  value={evento.id}
                  onChange={(e) => elegirEvento(e.target.value)}
                  className="min-h-[44px] max-w-full border border-edge bg-paper px-3 text-sm"
                >
                  {state.eventos.map((ev) => (
                    <option key={ev.id} value={ev.id}>
                      {ev.titulo}
                    </option>
                  ))}
                </select>
              </div>

              <h3 className="mt-6 text-sm font-semibold text-ink">Ocupación por sector</h3>
              <div className="mt-3 flex flex-col gap-4 border border-wire p-5">
                {sectoresEvento.map((s) => (
                  <div key={s.id} className="flex items-center gap-4">
                    <span className="w-32 shrink-0 text-sm">{s.nombre}</span>
                    <div className="flex-1">
                      <ProgressBar porcentaje={s.cupo > 0 ? (s.vendidas / s.cupo) * 100 : 0} altura={8} />
                    </div>
                    <span className="font-mono-label w-28 shrink-0 text-right text-[11px] text-muted">
                      {s.vendidas.toLocaleString('es-AR')} / {s.cupo.toLocaleString('es-AR')}
                    </span>
                  </div>
                ))}
                <p className="font-mono-label mt-2 border-t border-wire pt-3 text-right text-[11px] text-ink">
                  {vendidasEvento.toLocaleString('es-AR')} / {cupoEvento.toLocaleString('es-AR')} · {porcentajeEvento}%
                </p>
              </div>

              <h3 className="mt-8 text-sm font-semibold text-ink">Reventa oficial</h3>
              <div className="mt-3 border border-wire p-5">
                {reventas.error ? (
                  <p className="text-sm text-muted">{reventas.error}</p>
                ) : (
                  <>
                    <p className="text-sm text-graphite">
                      <span className="text-2xl font-semibold text-accent">{publicacionesDelEvento.length}</span>{' '}
                      publicaciones activas
                      {publicacionesDelEvento.length > 0 && (
                        <>
                          {' '}
                          · precio promedio <span className="font-semibold">{money(promedioReventa)}</span>
                        </>
                      )}
                    </p>
                    <p className="mt-4 text-xs text-muted">
                      El tope lo fija la plataforma: el organizador no puede habilitar reventa sin techo.
                    </p>
                  </>
                )}
              </div>

              <h3 className="mt-8 text-sm font-semibold text-ink">Accesos en puerta</h3>
              {accesos.fase === 'cargando' && (
                <p role="status" className="mt-3 text-sm text-muted">
                  Cargando el registro de la puerta…
                </p>
              )}
              {accesos.fase === 'error' && (
                <p role="alert" className="mt-3 text-sm text-muted">
                  {accesos.mensaje}{' '}
                  <button
                    type="button"
                    className="text-accent underline"
                    onClick={() => {
                      setAccesos({ fase: 'cargando' });
                      setVueltasAccesos((n) => n + 1);
                    }}
                  >
                    Reintentar
                  </button>
                </p>
              )}
              {accesos.fase === 'lista' && (
                <>
                  <div className="mt-3 grid grid-cols-3 gap-3 text-center">
                    <div className="border border-wire p-4">
                      <p className="text-xl font-semibold text-ink">{ingresos.toLocaleString('es-AR')}</p>
                      <p className="font-mono-label text-[10px] text-muted">ingresos</p>
                    </div>
                    <div className="border border-wire p-4">
                      <p className="text-xl font-semibold text-ink">{rechazos.toLocaleString('es-AR')}</p>
                      <p className="font-mono-label text-[10px] text-muted">rechazos</p>
                    </div>
                    <div className="border border-wire p-4">
                      <p className="text-xl font-semibold text-ink">
                        {Math.max(0, vendidasEvento - ingresos).toLocaleString('es-AR')}
                      </p>
                      <p className="font-mono-label text-[10px] text-muted">por ingresar</p>
                    </div>
                  </div>
                  <div className="mt-4 overflow-x-auto border border-wire">
                    <table className="w-full text-sm">
                      <thead className="bg-fill">
                        <tr>
                          {['Código', 'Resultado', 'Staff', 'Hora'].map((h) => (
                            <th key={h} className="font-mono-label px-4 py-3 text-left text-[10px] text-muted">
                              {h}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {escaneos.length === 0 && (
                          <tr>
                            <td colSpan={4} className="px-4 py-3 text-muted">
                              Todavía no se escaneó ninguna entrada.
                            </td>
                          </tr>
                        )}
                        {escaneos.slice(0, 20).map((e) => (
                          <tr key={e.id} className="border-t border-wire">
                            <td className="font-mono-label px-4 py-3 text-[11px]">…{e.codigoParcial}</td>
                            <td className="px-4 py-3">{ETIQUETA_RESULTADO[e.resultado] ?? e.resultado}</td>
                            <td className="px-4 py-3">{e.escaneadoPor}</td>
                            <td className="px-4 py-3">{fechaHora(e.escaneado)}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </>
              )}
            </>
          )}
        </div>
      </div>

      <CreateEventModal abierto={modalCrearAbierto} onCerrar={() => setModalCrearAbierto(false)} />
    </PageTransition>
  );
}
