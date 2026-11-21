import { pedir } from '../lib/api';

// Una función por endpoint de ServicioDeFacturacion (/api/v1/facturas).

// La factura electrónica de una compra, con el CAE que dio ARCA (simulado). Las facturas nacen
// solas cuando se confirma un pago: el frontend solo las consulta.
export interface Factura {
  ventaId: string;
  tipoComprobante: number; // código de ARCA: 6 es factura B
  puntoDeVenta: number;
  numero: number;
  comprobante: string; // punto de venta y número como se imprimen: 00001-00000001
  cae: string;
  vencimientoCae: string; // aaaa-mm-dd
  importe: number; // pesos enteros: el total cobrado
  fecha: string; // aaaa-mm-dd, fecha del comprobante
  emitida: string; // instante en que se guardó
}

// Responde 404 si la compra no tiene factura todavía (se está pidiendo a ARCA) o si no es del
// comprador de la sesión: las dos se ven igual. Si ARCA rechazó el comprobante de esa compra,
// responde 409 con tipo 'comprobante-rechazado': volver a consultar no la va a traer, porque el
// rechazo lo resuelve una persona.
export async function obtenerFactura(ventaId: string): Promise<Factura> {
  return pedir<Factura>(`/api/v1/facturas/${encodeURIComponent(ventaId)}`, { conSesion: true });
}
