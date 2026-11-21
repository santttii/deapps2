import { useEffect, useState } from 'react';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { fecha, money } from '../lib/format';
import { obtenerFactura, type Factura } from '../servicios/facturacion';
import Modal from './Modal';
import Button from './Button';

interface FacturaModalProps {
  // La compra de la que se muestra la factura; null con el modal cerrado.
  ventaId: string | null;
  // Cuándo se hizo esa compra (la venta se creó). Con ella se distingue una factura en camino de
  // una demorada; sin ella, la que no está se muestra siempre como en camino.
  compradaISO?: string;
  onCerrar: () => void;
}

type Carga =
  | { fase: 'cargando' }
  | { fase: 'pendiente' }
  | { fase: 'demorada' }
  | { fase: 'rechazada' }
  | { fase: 'error'; mensaje: string }
  | { fase: 'lista'; factura: Factura };

// Lo que se le dice a quien compró cuando la factura no está. Ninguno es un error suyo y las
// entradas valen igual: la factura no es parte de la compra.
const AVISOS = {
  pendiente: 'La factura todavía se está generando con ARCA. Probá de nuevo en unos segundos.',
  demorada:
    'La factura está demorada y la estamos revisando. Va a aparecer acá apenas ARCA la autorice; tus entradas valen igual.',
  rechazada:
    'ARCA rechazó la factura de esta compra y la estamos revisando: no tenés que hacer nada. Tus entradas valen igual.',
} as const;

// Pasado este tiempo desde la compra, una factura que no está ya no es la espera normal: ARCA
// responde en milisegundos y, si no contesta, el broker deja de reintentar en menos de un minuto y
// el mensaje queda en la cola de fallidos (DLQ) hasta que se lo reprocese. Se cuenta desde que se
// creó la venta (la fecha que tiene esta pantalla) y el pago llega después, por eso son minutos y
// no segundos. Con ARCA andando, quien pagó tarde no ve el aviso: la factura sale enseguida.
const MINUTOS_HASTA_DEMORA = 5;

// Qué mostrar cuando la consulta no trae la factura. Facturación responde 409
// comprobante-rechazado si ARCA la rechazó (volver a consultar no la va a traer) y 404 mientras no
// hay factura: en camino, unos segundos después del pago, o demorada, si ARCA no respondió.
function sinFactura(error: unknown, compradaISO: string | undefined): Carga {
  if (error instanceof ErrorApi && error.estado === 409 && error.tipo === 'comprobante-rechazado') {
    return { fase: 'rechazada' };
  }
  if (error instanceof ErrorApi && error.estado === 404) {
    // Sin la fecha de la compra (o con una que no se entiende) queda NaN, la comparación da false y
    // se muestra como en camino, igual que antes de conocerla.
    const compra = compradaISO ? new Date(compradaISO).getTime() : Number.NaN;
    const demorada = Date.now() - compra > MINUTOS_HASTA_DEMORA * 60_000;
    return { fase: demorada ? 'demorada' : 'pendiente' };
  }
  return { fase: 'error', mensaje: mensajeDeError(error) };
}

// Los tipos de comprobante de ARCA que emite Palco. Uno que no esté acá se muestra con su código.
const TIPOS: Record<number, string> = { 6: 'Factura B' };

// Las fechas de la factura vienen como aaaa-mm-dd. Con new Date('2026-11-18') el navegador las
// toma a la medianoche UTC y en Argentina saldría el día anterior: se fija el mediodía local.
const dia = (aaaammdd: string) => fecha(`${aaaammdd}T12:00:00`);

// La factura electrónica de una compra, como la guardó Facturación con el CAE de ARCA. Se pide
// cada vez que se abre: si todavía se está generando o está demorada, se puede volver a consultar;
// si ARCA la rechazó, no, porque eso lo resuelve una persona. Quien lo usa le pone de key la compra,
// así cada factura arranca de cero y no se ve un instante la anterior.
export default function FacturaModal({ ventaId, compradaISO, onCerrar }: FacturaModalProps) {
  const [carga, setCarga] = useState<Carga>({ fase: 'cargando' });
  const [vueltas, setVueltas] = useState(0);

  useEffect(() => {
    if (!ventaId) return;
    let vigente = true;
    obtenerFactura(ventaId)
      .then((factura) => vigente && setCarga({ fase: 'lista', factura }))
      .catch((error: unknown) => vigente && setCarga(sinFactura(error, compradaISO)));
    return () => {
      vigente = false;
    };
  }, [ventaId, compradaISO, vueltas]);

  return (
    <Modal abierto={ventaId !== null} onCerrar={onCerrar} titulo="Factura de la compra">
      {carga.fase === 'cargando' && (
        <p role="status" className="text-sm text-muted">
          Buscando la factura…
        </p>
      )}
      {carga.fase !== 'cargando' && carga.fase !== 'lista' && (
        <div className="flex flex-col gap-4">
          <p role="alert" className="border-l-2 border-accent pl-2 text-sm text-ink">
            {carga.fase === 'error' ? carga.mensaje : AVISOS[carga.fase]}
          </p>
          {carga.fase !== 'rechazada' && (
            <Button
              variant="secondary"
              onClick={() => {
                setCarga({ fase: 'cargando' });
                setVueltas((n) => n + 1);
              }}
            >
              Volver a consultar
            </Button>
          )}
        </div>
      )}
      {carga.fase === 'lista' && (
        <div className="flex flex-col gap-4">
          <div className="flex items-baseline justify-between gap-3 border-b border-dashed border-edge pb-3">
            <p className="text-lg font-semibold text-ink">
              {TIPOS[carga.factura.tipoComprobante] ?? `Comprobante tipo ${carga.factura.tipoComprobante}`}
            </p>
            <p className="font-mono-label text-[12px] text-ink">N.º {carga.factura.comprobante}</p>
          </div>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
            <div>
              <dt className="font-mono-label text-[10px] text-muted">Fecha</dt>
              <dd>{dia(carga.factura.fecha)}</dd>
            </div>
            <div>
              <dt className="font-mono-label text-[10px] text-muted">Orden</dt>
              <dd className="break-all">#{carga.factura.ventaId}</dd>
            </div>
            <div>
              <dt className="font-mono-label text-[10px] text-muted">CAE</dt>
              <dd className="font-mono-label text-[13px]">{carga.factura.cae}</dd>
            </div>
            <div>
              <dt className="font-mono-label text-[10px] text-muted">Vencimiento del CAE</dt>
              <dd>{dia(carga.factura.vencimientoCae)}</dd>
            </div>
          </dl>
          <div className="flex items-baseline justify-between border-t border-line pt-3">
            <span className="font-mono-label text-[11px] text-muted">Total</span>
            <span className="text-xl font-semibold text-ink">{money(carga.factura.importe)}</span>
          </div>
          <p className="text-xs text-muted">
            Factura electrónica autorizada por ARCA con su CAE. En este entorno, ARCA es un servicio de
            prueba: el comprobante no tiene validez fiscal.
          </p>
        </div>
      )}
      <Button variant="secondary" className="mt-5 w-full" onClick={onCerrar}>
        Cerrar
      </Button>
    </Modal>
  );
}
