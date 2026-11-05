import { useEffect, useState, type FormEvent } from 'react';
import { useApp } from '../store/AppContext';
import { mensajeDeError } from '../lib/api';
import { fecha as formatearFecha, fechaHora, money } from '../lib/format';
import { useReventas } from '../lib/useReventas';
import { obtenerPerfil } from '../servicios/usuarios';
import { listarMisEventos } from '../servicios/eventos';
import { liquidacionDelEvento, type LiquidacionDeEvento } from '../servicios/ventas';
import {
  asignarPuerta,
  equipoDePuerta,
  listarEscaneos,
  quitarPuerta,
  type Escaneo,
  type PuertaAsignada,
} from '../servicios/validacion';
import type { Evento, Sector } from '../types';
import StatCard from '../components/StatCard';
import ProgressBar from '../components/ProgressBar';
import Button from '../components/Button';
import Input from '../components/Input';
import PageTransition from '../components/PageTransition';
import CreateEventModal from '../components/CreateEventModal';

// Cada ítem lleva a su sección de esta misma página. Reventa y Accesos existen solo cuando hay
// un evento elegido.
const SECCIONES = [
  { id: 'mis-eventos', nombre: 'Eventos', necesitaEvento: false },
  { id: 'reventa', nombre: 'Reventa', necesitaEvento: true },
  { id: 'accesos', nombre: 'Accesos / staff', necesitaEvento: true },
];

// Que el título de la sección no quede tapado por el encabezado fijo al saltar desde la barra.
const ANCLA = 'scroll-mt-40';

// Validación devuelve como mucho los últimos 500 escaneos de un evento. Si llegan 500 puede haber
// más, y contar sobre la lista daría totales de menos.
const TOPE_DEL_REGISTRO = 500;
const ESCANEOS_EN_TABLA = 20;

const ETIQUETA_RESULTADO: Record<Escaneo['resultado'], string> = {
  VALIDA: 'ingresó',
  YA_USADA: 'ya usada',
  INEXISTENTE: 'no existe',
  OTRO_EVENTO: 'otro evento',
  NO_HABILITADA: 'no habilitada',
  SIN_RESPUESTA: 'sin respuesta',
};

type Accesos = { fase: 'cargando' } | { fase: 'error'; mensaje: string } | { fase: 'lista'; escaneos: Escaneo[] };
type MisEventos = { fase: 'cargando' } | { fase: 'error'; mensaje: string } | { fase: 'lista'; eventos: Evento[] };
type Equipo = { fase: 'cargando' } | { fase: 'error'; mensaje: string } | { fase: 'lista'; cuentas: PuertaAsignada[] };

const ocupadas = (sectores: Sector[]) => sectores.reduce((acc, s) => acc + s.vendidas, 0);
const cupoTotal = (sectores: Sector[]) => sectores.reduce((acc, s) => acc + s.cupo, 0);
const numero = (n: number) => n.toLocaleString('es-AR');

// Panel del organizador. Todo lo que muestra sale del backend y es solo de sus eventos:
// - lo vendido y lo recaudado, de Ventas (GET /api/v1/resumenes): solo compras pagadas;
// - el cupo y la ocupación por sector, de Eventos (GET /api/eventos/mios). Eventos cuenta como
//   ocupado también lo reservado por una compra en curso, por eso se rotula ocupación y no venta;
// - las publicaciones activas de Reventa;
// - el registro de la puerta y el equipo de puerta, de Validación.
// Lo que todavía no tiene pantalla (ventas por día, facturación) no se muestra en vez de
// mostrarse con datos fijos.
export default function OrganizerPage() {
  const { state } = useApp();
  const [modalCrearAbierto, setModalCrearAbierto] = useState(false);
  const [eventoElegido, setEventoElegido] = useState('');
  const [accesos, setAccesos] = useState<Accesos>({ fase: 'cargando' });
  const [vueltasAccesos, setVueltasAccesos] = useState(0);
  const [misEventos, setMisEventos] = useState<MisEventos>({ fase: 'cargando' });
  const [vueltasEventos, setVueltasEventos] = useState(0);
  // Lo cobrado de cada evento, por id. Un evento que todavía no está acá se muestra como cargando.
  const [liquidaciones, setLiquidaciones] = useState<Record<string, LiquidacionDeEvento>>({});
  const [errorVentas, setErrorVentas] = useState('');
  const [vueltasVentas, setVueltasVentas] = useState(0);
  const [equipo, setEquipo] = useState<Equipo>({ fase: 'cargando' });
  const [emailNuevo, setEmailNuevo] = useState('');
  const [errorEquipo, setErrorEquipo] = useState('');
  const [guardandoEquipo, setGuardandoEquipo] = useState(false);

  // Se vuelve a pedir cuando cambia el catálogo: así aparece el evento recién creado.
  useEffect(() => {
    let vigente = true;
    listarMisEventos()
      .then((lista) => vigente && setMisEventos({ fase: 'lista', eventos: lista }))
      .catch((error: unknown) => vigente && setMisEventos({ fase: 'error', mensaje: mensajeDeError(error) }));
    return () => {
      vigente = false;
    };
  }, [state.eventos, vueltasEventos]);

  const eventos = misEventos.fase === 'lista' ? misEventos.eventos : [];
  const reventas = useReventas(eventos.map((e) => e.id));
  const evento = eventos.find((e) => e.id === eventoElegido) ?? eventos[0];
  const eventoId = evento?.id;

  // Ventas resume un evento por pedido. Si falla alguno, los totales no se muestran: sumar solo
  // los que respondieron daría un número de menos que parece cierto.
  const idsDeEventos = misEventos.fase === 'lista' ? eventos.map((e) => e.id).join(',') : null;
  useEffect(() => {
    if (idsDeEventos === null) return;
    const ids = idsDeEventos ? idsDeEventos.split(',') : [];
    let vigente = true;
    Promise.allSettled(ids.map((id) => liquidacionDelEvento(id))).then((resultados) => {
      if (!vigente) return;
      const porEvento: Record<string, LiquidacionDeEvento> = {};
      resultados.forEach((r, i) => {
        if (r.status === 'fulfilled') porEvento[ids[i]] = r.value;
      });
      const fallo = resultados.find((r) => r.status === 'rejected');
      setLiquidaciones(porEvento);
      setErrorVentas(fallo ? mensajeDeError(fallo.reason) : '');
    });
    return () => {
      vigente = false;
    };
  }, [idsDeEventos, vueltasVentas]);

  useEffect(() => {
    if (!eventoId) return;
    let vigente = true;
    equipoDePuerta(eventoId)
      .then((cuentas) => vigente && setEquipo({ fase: 'lista', cuentas }))
      .catch((error: unknown) => vigente && setEquipo({ fase: 'error', mensaje: mensajeDeError(error) }));
    return () => {
      vigente = false;
    };
  }, [eventoId]);

  async function asignar(e: FormEvent) {
    e.preventDefault();
    if (!eventoId || !emailNuevo.trim()) return;
    setGuardandoEquipo(true);
    setErrorEquipo('');
    try {
      const nueva = await asignarPuerta(eventoId, emailNuevo);
      setEquipo((actual) => {
        const cuentas = actual.fase === 'lista' ? actual.cuentas : [];
        const sinRepetir = cuentas.filter((c) => c.staffEmail !== nueva.staffEmail);
        return { fase: 'lista', cuentas: [...sinRepetir, nueva].sort((a, b) => a.staffEmail.localeCompare(b.staffEmail)) };
      });
      setEmailNuevo('');
    } catch (error) {
      setErrorEquipo(mensajeDeError(error));
    } finally {
      setGuardandoEquipo(false);
    }
  }

  async function quitar(email: string) {
    if (!eventoId) return;
    setErrorEquipo('');
    try {
      await quitarPuerta(eventoId, email);
      setEquipo((actual) =>
        actual.fase === 'lista' ? { fase: 'lista', cuentas: actual.cuentas.filter((c) => c.staffEmail !== email) } : actual,
      );
    } catch (error) {
      setErrorEquipo(mensajeDeError(error));
    }
  }

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
    setEquipo({ fase: 'cargando' });
    setErrorEquipo('');
  }

  function reintentarEventos() {
    setMisEventos({ fase: 'cargando' });
    setVueltasEventos((n) => n + 1);
  }

  function reintentarVentas() {
    setLiquidaciones({});
    setErrorVentas('');
    setVueltasVentas((n) => n + 1);
  }

  function abrirModalCrear() {
    // Se confirma la sesión antes de que el organizador escriba. Si el token ya no sirve (por
    // ejemplo, porque se reinició el servidor), api.ts cierra la sesión acá y no recién al
    // enviar el formulario. El resultado no se usa.
    obtenerPerfil().catch(() => undefined);
    setModalCrearAbierto(true);
  }

  const eventosListos = misEventos.fase === 'lista';
  const ventasCompletas = eventosListos && !errorVentas && eventos.every((e) => e.id in liquidaciones);
  const delPanel = eventos.map((e) => liquidaciones[e.id]).filter((l) => l !== undefined);
  const vendidas = ventasCompletas ? delPanel.reduce((acc, l) => acc + l.entradas, 0) : null;
  const recaudado = ventasCompletas ? delPanel.reduce((acc, l) => acc + l.aLiquidarAlOrganizador, 0) : null;
  const sectores = eventos.flatMap((e) => e.sectores);
  const cupoRestante = eventosListos ? cupoTotal(sectores) - ocupadas(sectores) : null;
  const enReventa = eventosListos && !reventas.cargando ? reventas.publicaciones.length : null;

  const publicacionesDelEvento = reventas.publicaciones.filter((p) => p.eventoId === eventoId);
  const promedioReventa =
    publicacionesDelEvento.length > 0
      ? publicacionesDelEvento.reduce((acc, p) => acc + p.precio, 0) / publicacionesDelEvento.length
      : 0;

  const sectoresEvento = evento?.sectores ?? [];
  const ocupadasEvento = ocupadas(sectoresEvento);
  const cupoEvento = cupoTotal(sectoresEvento);
  const porcentajeEvento = cupoEvento > 0 ? Math.round((ocupadasEvento / cupoEvento) * 100) : 0;
  const vendidasEvento = eventoId ? liquidaciones[eventoId]?.entradas : undefined;

  const escaneos = accesos.fase === 'lista' ? accesos.escaneos : [];
  const registroCortado = escaneos.length >= TOPE_DEL_REGISTRO;
  const ingresos = escaneos.filter((e) => e.resultado === 'VALIDA').length;
  const rechazos = escaneos.filter((e) => e.resultado !== 'VALIDA' && e.resultado !== 'SIN_RESPUESTA').length;
  // Sin el registro completo o sin lo vendido según Ventas no hay cuenta que hacer.
  const porIngresar =
    registroCortado || vendidasEvento === undefined ? null : Math.max(0, vendidasEvento - ingresos);

  // Lo que se muestra en la columna Vendidas de un evento mientras Ventas no lo resumió.
  function vendidasDeLaFila(id: string): string {
    const liquidacion = liquidaciones[id];
    if (liquidacion) return numero(liquidacion.entradas);
    return errorVentas ? '—' : '…';
  }

  return (
    <PageTransition>
      <div className="mx-auto flex max-w-[1240px] gap-10 px-7 py-10">
        <aside className="hidden w-[220px] shrink-0 border border-wire md:block">
          <p className="font-mono-label border-b border-wire px-4 py-3 text-[10px] text-muted">
            Organizador
          </p>
          <nav aria-label="Secciones del panel" className="flex flex-col">
            {SECCIONES.filter((s) => !s.necesitaEvento || evento).map((s) => (
              <a key={s.id} href={`#${s.id}`} className="px-4 py-3 text-sm text-graphite hover:bg-fill hover:text-ink">
                {s.nombre}
              </a>
            ))}
          </nav>
        </aside>

        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <h1 className="text-2xl font-semibold tracking-tight text-ink">Eventos</h1>
            <Button onClick={abrirModalCrear}>+ Crear evento</Button>
          </div>

          {misEventos.fase !== 'error' && (
            <div className="mt-8 grid grid-cols-[repeat(auto-fit,minmax(200px,1fr))] gap-4">
              <StatCard
                etiqueta="Entradas vendidas"
                valor={vendidas}
                error={errorVentas}
                onReintentar={reintentarVentas}
              />
              <StatCard
                etiqueta="Recaudado (sin cargo de servicio)"
                valor={recaudado}
                formatear={money}
                error={errorVentas}
                onReintentar={reintentarVentas}
              />
              <StatCard etiqueta="Cupo restante" valor={cupoRestante} />
              <StatCard
                etiqueta="En reventa"
                valor={enReventa}
                error={reventas.error}
                onReintentar={reventas.recargar}
              />
            </div>
          )}

          <h2 id="mis-eventos" className={`mt-10 text-lg font-semibold tracking-tight text-ink ${ANCLA}`}>
            Mis eventos
          </h2>
          {misEventos.fase === 'cargando' && (
            <p role="status" className="mt-4 text-sm text-muted">
              Cargando tus eventos…
            </p>
          )}
          {misEventos.fase === 'error' && (
            <p role="alert" className="mt-4 text-sm text-muted">
              {misEventos.mensaje}{' '}
              <button type="button" className="text-accent underline" onClick={reintentarEventos}>
                Reintentar
              </button>
            </p>
          )}
          {eventosListos && eventos.length === 0 && (
            <p className="mt-4 text-sm text-muted">Todavía no creaste ningún evento.</p>
          )}
          {eventos.length > 0 && (
            <>
              <div
                className="mt-4 overflow-x-auto border border-wire"
                tabIndex={0}
                role="region"
                aria-label="Tabla de mis eventos"
              >
                <table className="w-full min-w-[520px] text-sm">
                  <thead className="bg-fill">
                    <tr>
                      {['Evento', 'Fecha', 'Vendidas', 'Ocupación'].map((h) => (
                        <th key={h} className="font-mono-label px-4 py-3 text-left text-[10px] text-muted">
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {eventos.map((ev) => (
                      <tr key={ev.id} className="border-t border-wire">
                        <td className="px-4 py-3">{ev.titulo}</td>
                        <td className="whitespace-nowrap px-4 py-3">{formatearFecha(ev.fechaISO)}</td>
                        <td className="px-4 py-3">{vendidasDeLaFila(ev.id)}</td>
                        <td className="whitespace-nowrap px-4 py-3">
                          {numero(ocupadas(ev.sectores))} / {numero(cupoTotal(ev.sectores))}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {/* Ventas no separa la reventa: la compra en la reventa oficial es una venta más del
                  evento, así que una entrada revendida suma en las dos compras. */}
              <p className="mt-2 text-xs text-muted">
                Vendidas: entradas de compras pagadas, contando también las compras en la reventa oficial.
                Ocupación: lugares tomados sobre el cupo, contando las compras que todavía se están pagando.
              </p>
            </>
          )}

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
                  {eventos.map((ev) => (
                    <option key={ev.id} value={ev.id}>
                      {ev.titulo}
                    </option>
                  ))}
                </select>
              </div>

              <h3 className="mt-6 text-sm font-semibold text-ink">Ocupación por sector</h3>
              <div className="mt-3 flex flex-col gap-4 border border-wire p-5">
                {/* En un celular la barra baja a su propio renglón: al lado del nombre y los números
                    no le queda ancho. */}
                {sectoresEvento.map((s) => (
                  <div key={s.id} className="flex flex-wrap items-center gap-x-4 gap-y-1.5">
                    <span className="order-1 min-w-0 flex-1 truncate text-sm sm:w-32 sm:flex-none" title={s.nombre}>
                      {s.nombre}
                    </span>
                    <div className="order-3 w-full sm:order-2 sm:w-auto sm:flex-1">
                      <ProgressBar
                        porcentaje={s.cupo > 0 ? (s.vendidas / s.cupo) * 100 : 0}
                        altura={8}
                        etiqueta={`Ocupación de ${s.nombre}`}
                      />
                    </div>
                    <span className="font-mono-label order-2 shrink-0 text-right text-[11px] text-muted sm:order-3 sm:w-28">
                      {numero(s.vendidas)} / {numero(s.cupo)}
                    </span>
                  </div>
                ))}
                <p className="font-mono-label mt-2 border-t border-wire pt-3 text-right text-[11px] text-ink">
                  {numero(ocupadasEvento)} / {numero(cupoEvento)} · {porcentajeEvento}%
                </p>
              </div>

              <h3 id="reventa" className={`mt-8 text-sm font-semibold text-ink ${ANCLA}`}>
                Reventa oficial
              </h3>
              <div className="mt-3 border border-wire p-5">
                {reventas.error ? (
                  <p role="alert" className="text-sm text-muted">
                    {reventas.error}{' '}
                    <button type="button" className="text-accent underline" onClick={reventas.recargar}>
                      Reintentar
                    </button>
                  </p>
                ) : reventas.cargando ? (
                  <p role="status" className="text-sm text-muted">
                    Cargando las publicaciones…
                  </p>
                ) : (
                  <>
                    <p className="text-sm text-graphite">
                      <span className="text-2xl font-semibold text-accent">{publicacionesDelEvento.length}</span>{' '}
                      {publicacionesDelEvento.length === 1 ? 'publicación activa' : 'publicaciones activas'}
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

              <section id="accesos" aria-labelledby="titulo-accesos" className={ANCLA}>
                <h3 id="titulo-accesos" className="mt-8 text-sm font-semibold text-ink">
                  Accesos en puerta
                </h3>
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
                    {registroCortado && (
                      <p className="mt-3 border-l-2 border-accent pl-2 text-xs text-ink">
                        El registro trae solo los últimos {numero(TOPE_DEL_REGISTRO)} escaneos y este evento
                        puede tener más: ingresos y rechazos cuentan solo esos, y lo que falta por ingresar
                        no se puede calcular.
                      </p>
                    )}
                    <div className="mt-3 grid grid-cols-3 gap-3 text-center">
                      <div className="border border-wire p-4">
                        <p className="text-xl font-semibold text-ink">{numero(ingresos)}</p>
                        <p className="font-mono-label text-[10px] text-muted">
                          {registroCortado ? 'ingresos en esos escaneos' : 'ingresos'}
                        </p>
                      </div>
                      <div className="border border-wire p-4">
                        <p className="text-xl font-semibold text-ink">{numero(rechazos)}</p>
                        <p className="font-mono-label text-[10px] text-muted">
                          {registroCortado ? 'rechazos en esos escaneos' : 'rechazos'}
                        </p>
                      </div>
                      <div className="border border-wire p-4">
                        <p className="text-xl font-semibold text-ink">{porIngresar === null ? '—' : numero(porIngresar)}</p>
                        <p className="font-mono-label text-[10px] text-muted">por ingresar</p>
                      </div>
                    </div>
                    <div
                      className="mt-4 overflow-x-auto border border-wire"
                      tabIndex={0}
                      role="region"
                      aria-label="Registro de la puerta"
                    >
                      <table className="w-full min-w-[560px] text-sm">
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
                          {escaneos.slice(0, ESCANEOS_EN_TABLA).map((e) => (
                            <tr key={e.id} className="border-t border-wire">
                              {/* El backend ya manda el código recortado, con los puntos suspensivos. */}
                              <td className="font-mono-label px-4 py-3 text-[11px]">{e.codigoParcial}</td>
                              <td className="px-4 py-3">{ETIQUETA_RESULTADO[e.resultado] ?? e.resultado}</td>
                              <td className="px-4 py-3">{e.escaneadoPor}</td>
                              <td className="whitespace-nowrap px-4 py-3">{fechaHora(e.escaneado)}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {escaneos.length > ESCANEOS_EN_TABLA && (
                      <p className="mt-2 text-xs text-muted">
                        Se muestran los {ESCANEOS_EN_TABLA} más recientes de{' '}
                        {registroCortado ? `${numero(TOPE_DEL_REGISTRO)} o más` : numero(escaneos.length)}.
                      </p>
                    )}
                  </>
                )}

                <h3 className="mt-8 text-sm font-semibold text-ink">Equipo de puerta</h3>
                <div className="mt-3 border border-wire p-5">
                  <p className="text-xs text-muted">
                    Cuentas asignadas a la puerta de este evento. Pueden escanear entradas y ver el registro
                    solo si son cuentas de personal de puerta: al asignar no se comprueba que el email
                    exista ni qué rol tiene.
                  </p>
                  {equipo.fase === 'cargando' && (
                    <p role="status" className="mt-3 text-sm text-muted">
                      Cargando el equipo…
                    </p>
                  )}
                  {equipo.fase === 'error' && (
                    <p role="alert" className="mt-3 text-sm text-muted">
                      {equipo.mensaje}
                    </p>
                  )}
                  {equipo.fase === 'lista' && (
                    <ul className="mt-3 flex flex-col divide-y divide-wire border border-wire">
                      {equipo.cuentas.length === 0 && (
                        <li className="px-4 py-3 text-sm text-muted">Nadie asignado todavía.</li>
                      )}
                      {equipo.cuentas.map((c) => (
                        <li key={c.staffEmail} className="flex items-center justify-between gap-3 px-4 py-3 text-sm">
                          <span className="min-w-0 truncate">{c.staffEmail}</span>
                          <button
                            type="button"
                            className="shrink-0 text-accent underline"
                            onClick={() => quitar(c.staffEmail)}
                          >
                            Quitar
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                  <form onSubmit={asignar} className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-end">
                    <div className="flex-1">
                      <Input
                        label="Email de la cuenta de puerta"
                        type="email"
                        value={emailNuevo}
                        onChange={(e) => setEmailNuevo(e.target.value)}
                        placeholder="puerta@palco.test"
                        autoComplete="off"
                      />
                    </div>
                    <Button type="submit" disabled={guardandoEquipo || !emailNuevo.trim()}>
                      {guardandoEquipo ? 'Asignando…' : 'Asignar'}
                    </Button>
                  </form>
                  {errorEquipo && (
                    <p role="alert" className="mt-3 text-sm text-muted">
                      {errorEquipo}
                    </p>
                  )}
                </div>
              </section>
            </>
          )}
        </div>
      </div>

      <CreateEventModal abierto={modalCrearAbierto} onCerrar={() => setModalCrearAbierto(false)} />
    </PageTransition>
  );
}
