import { pedir } from '../lib/api';
import { soloDigitosDNI } from '../lib/validate';
import type { Asistente, EstadoPublicacion, Publicacion } from '../types';
import type { DatosDePago } from './ventas';

// Una función por operación de Reventa (/api/v1/reventas). El listado de un evento es público;
// el resto pide sesión con rol user.
//
// El rango de precio (50% a 110% de lo pagado) y el cierre 3 horas antes del evento los valida el
// backend con ReglasDeNegocio. La pantalla los controla antes solo para avisar sin ir al servidor:
// la respuesta que vale es la del backend.

// Así llega una publicación. "estado" solo viene en /mias: el listado de un evento trae solo activas.
interface PublicacionDelServidor {
  id: string;
  entradaId: string;
  eventoId: string;
  sectorId: string;
  precio: number;
  precioMaximo: number;
  publicada: string;
  estado?: string; // ACTIVA, VENDIDA, RETIRADA o ANULADA
}

// Así llega la entrada que recibe quien compra en la reventa: nueva, con su QR y su titular.
export interface EntradaRevendida {
  id: string;
  codigoQr: string;
  titular: { nombre: string; dni: string };
  eventoId: string;
  sectorId: string;
  precioPagado: number;
}

export interface CompraDeReventa {
  publicacion: Publicacion;
  entrada: EntradaRevendida;
  ventaId: string;
}

// Único lugar donde una publicación del servidor pasa al tipo del frontend.
function aPublicacion(p: PublicacionDelServidor): Publicacion {
  return {
    id: p.id,
    entradaId: p.entradaId,
    eventoId: p.eventoId,
    sectorId: p.sectorId,
    precio: p.precio,
    precioMaximo: p.precioMaximo,
    estado: (p.estado?.toLowerCase() ?? 'activa') as EstadoPublicacion,
    publicadaISO: p.publicada,
  };
}

function rutaDePublicacion(publicacionId: string): string {
  return `/api/v1/reventas/${encodeURIComponent(publicacionId)}`;
}

// Publicaciones activas de un evento. No pide sesión: se ven sin haber ingresado.
export async function listarReventas(eventoId: string): Promise<Publicacion[]> {
  const lista = await pedir<PublicacionDelServidor[]>('/api/v1/reventas', { consulta: { eventoId } });
  return lista.map(aPublicacion);
}

// Las publicaciones del usuario de la sesión, en todos los estados.
export async function listarMisPublicaciones(): Promise<Publicacion[]> {
  const lista = await pedir<PublicacionDelServidor[]>('/api/v1/reventas/mias', { conSesion: true });
  return lista.map(aPublicacion);
}

// Publica una entrada propia VALIDA. Mientras está publicada no sirve para entrar.
export async function publicarEntrada(entradaId: string, precio: number): Promise<Publicacion> {
  const creada = await pedir<PublicacionDelServidor>('/api/v1/reventas', {
    metodo: 'POST',
    cuerpo: { entradaId, precio },
    conSesion: true,
  });
  return aPublicacion(creada);
}

// Retira la publicación: la entrada vuelve a VALIDA.
export async function retirarPublicacion(publicacionId: string): Promise<void> {
  await pedir<void>(rutaDePublicacion(publicacionId), { metodo: 'DELETE', conSesion: true });
}

// Compra una publicación: cobra el precio más el cargo de servicio y emite una entrada nueva a
// nombre del titular. Va con clave de idempotencia por la misma razón que el pago de una compra:
// si la respuesta se pierde, repetir con la misma clave no cobra dos veces.
export async function comprarReventa(
  publicacionId: string,
  titular: Asistente,
  pago: DatosDePago,
  clave: string,
): Promise<CompraDeReventa> {
  const r = await pedir<{ publicacion: PublicacionDelServidor; entrada: EntradaRevendida; ventaId: string }>(
    `${rutaDePublicacion(publicacionId)}/compra`,
    {
      metodo: 'POST',
      cuerpo: {
        titular: { nombre: `${titular.nombre.trim()} ${titular.apellido.trim()}`, dni: soloDigitosDNI(titular.dni) },
        medioDePago: pago.medioPago,
        token: pago.token,
      },
      conSesion: true,
      claveIdempotencia: clave,
    },
  );
  return { publicacion: aPublicacion(r.publicacion), entrada: r.entrada, ventaId: r.ventaId };
}
