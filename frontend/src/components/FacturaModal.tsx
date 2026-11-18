import { useEffect, useState } from 'react';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { fecha, money } from '../lib/format';
import { obtenerFactura, type Factura } from '../servicios/facturacion';
import Modal from './Modal';
import Button from './Button';

interface FacturaModalProps {
  // La compra de la que se muestra la factura; null con el modal cerrado.
  ventaId: string | null;
  onCerrar: () => void;
}

type Carga =
  | { fase: 'cargando' }
  | { fase: 'pendiente' }
  | { fase: 'error'; mensaje: string }
  | { fase: 'lista'; factura: Factura };

// Los tipos de comprobante de ARCA que emite Palco. Uno que no esté acá se muestra con su código.
const TIPOS: Record<number, string> = { 6: 'Factura B' };

// Las fechas de la factura vienen como aaaa-mm-dd. Con new Date('2026-11-18') el navegador las
// toma a la medianoche UTC y en Argentina saldría el día anterior: se fija el mediodía local.
const dia = (aaaammdd: string) => fecha(`${aaaammdd}T12:00:00`);

// La factura electrónica de una compra, como la guardó Facturación con el CAE de ARCA. Se pide
// cada vez que se abre: si todavía se está generando, se puede volver a consultar. Quien lo usa le
// pone de key la compra, así cada factura arranca de cero y no se ve un instante la anterior.
export default function FacturaModal({ ventaId, onCerrar }: FacturaModalProps) {
  const [carga, setCarga] = useState<Carga>({ fase: 'cargando' });
  const [vueltas, setVueltas] = useState(0);

  useEffect(() => {
    if (!ventaId) return;
    let vigente = true;
    obtenerFactura(ventaId)
      .then((factura) => vigente && setCarga({ fase: 'lista', factura }))
      .catch((error: unknown) => {
        if (!vigente) return;
        // 404: la factura se pide a ARCA por mensajería después del pago y puede tardar unos segundos.
        if (error instanceof ErrorApi && error.estado === 404) setCarga({ fase: 'pendiente' });
        else setCarga({ fase: 'error', mensaje: mensajeDeError(error) });
      });
    return () => {
      vigente = false;
    };
  }, [ventaId, vueltas]);

  return (
    <Modal abierto={ventaId !== null} onCerrar={onCerrar} titulo="Factura de la compra">
      {carga.fase === 'cargando' && (
        <p role="status" className="text-sm text-muted">
          Buscando la factura…
        </p>
      )}
      {(carga.fase === 'pendiente' || carga.fase === 'error') && (
        <div className="flex flex-col gap-4">
          <p role="alert" className="border-l-2 border-accent pl-2 text-sm text-ink">
            {carga.fase === 'pendiente'
              ? 'La factura todavía se está generando con ARCA. Probá de nuevo en unos segundos.'
              : carga.mensaje}
          </p>
          <Button
            variant="secondary"
            onClick={() => {
              setCarga({ fase: 'cargando' });
              setVueltas((n) => n + 1);
            }}
          >
            Volver a consultar
          </Button>
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
