import { Link } from 'react-router-dom';
import type { Evento, Publicacion, Sector } from '../types';
import { calcularTope, money } from '../lib/format';
import Button from './Button';
import ProgressBar from './ProgressBar';

interface ListingCardProps {
  publicacion: Publicacion;
  evento: Evento;
  sector: Sector;
  onComprar?: () => void;
  href?: string;
}

export default function ListingCard({ publicacion, evento, sector, onComprar, href }: ListingCardProps) {
  const tope = calcularTope(publicacion.precioOriginal);
  const porcentaje = (publicacion.precio / tope) * 100;

  return (
    <div className="border border-darkLine bg-ink p-5 text-paper">
      <p className="font-mono-label text-[10px] text-onDark">
        {sector.nombre} · {evento.titulo}
      </p>
      <p className="mt-3 text-2xl font-semibold tracking-tight">{money(publicacion.precio)}</p>
      <p className="font-mono-label mt-1 text-[10px] text-onDark">tope {money(tope)}</p>
      <div className="mt-3">
        <ProgressBar porcentaje={porcentaje} sobreOscuro />
      </div>
      {href ? (
        <Link to={href}>
          <Button variant="ghost" className="mt-4 w-full">
            Comprar
          </Button>
        </Link>
      ) : (
        <Button variant="ghost" className="mt-4 w-full" onClick={onComprar}>
          Comprar
        </Button>
      )}
    </div>
  );
}
