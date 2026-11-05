import type { Evento, Publicacion, Sector } from '../types';
import { money } from '../lib/format';
import { AVISO_REVENTA_CERRADA, reventaCerrada } from '../servicios/reventa';
import Button, { ButtonLink } from './Button';
import ProgressBar from './ProgressBar';

interface ListingCardProps {
  publicacion: Publicacion;
  evento: Evento;
  sector: Sector;
  onComprar?: () => void;
  href?: string;
}

export default function ListingCard({ publicacion, evento, sector, onComprar, href }: ListingCardProps) {
  // El tope lo calcula Reventa (lo pagado + 10%) y viaja en la publicación.
  const tope = publicacion.precioMaximo;
  const porcentaje = (publicacion.precio / tope) * 100;

  return (
    <div className="border border-darkLine bg-ink p-5 text-paper">
      <p className="font-mono-label text-[10px] text-onDark">
        {sector.nombre} · {evento.titulo}
      </p>
      <p className="mt-3 text-2xl font-semibold tracking-tight">{money(publicacion.precio)}</p>
      <p className="font-mono-label mt-1 text-[10px] text-onDark">tope {money(tope)}</p>
      <div className="mt-3">
        <ProgressBar porcentaje={porcentaje} sobreOscuro etiqueta="Precio sobre el tope" />
      </div>
      {/* El listado de Reventa trae también las de un evento con la reventa cerrada; comprarlas
          da error, así que no se ofrecen. */}
      {reventaCerrada(evento.fechaISO) ? (
        <p className="mt-4 text-sm text-onDark">{AVISO_REVENTA_CERRADA}</p>
      ) : href ? (
        <ButtonLink to={href} variant="ghost" className="mt-4 w-full">
          Comprar
        </ButtonLink>
      ) : (
        <Button variant="ghost" className="mt-4 w-full" onClick={onComprar}>
          Comprar
        </Button>
      )}
    </div>
  );
}
