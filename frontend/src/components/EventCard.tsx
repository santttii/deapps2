import { Link } from 'react-router-dom';
import { motion } from 'framer-motion';
import type { Evento } from '../types';
import { fecha, money } from '../lib/format';
import ImagePlaceholder from './ImagePlaceholder';
import Badge from './Badge';

interface EventCardProps {
  evento: Evento;
  tieneReventa: boolean;
  index?: number;
}

export default function EventCard({ evento, tieneReventa, index = 0 }: EventCardProps) {
  const cupoTotal = evento.sectores.reduce((acc, s) => acc + s.cupo, 0);
  const vendidasTotal = evento.sectores.reduce((acc, s) => acc + s.vendidas, 0);
  const restantes = cupoTotal - vendidasTotal;
  const desde = Math.min(...evento.sectores.map((s) => s.precio));

  let stockLabel = 'disponible';
  let stockClass = 'text-muted';
  if (restantes <= 0) {
    stockLabel = 'agotado';
    stockClass = 'text-faint';
  } else if (restantes / cupoTotal < 0.1) {
    stockLabel = 'últimos lugares';
    stockClass = 'text-accent';
  }

  return (
    <motion.article
      initial={{ opacity: 0, y: 22 }}
      animate={{ opacity: 1, y: 0 }}
      transition={{ duration: 0.5, delay: index * 0.045, ease: [0.16, 1, 0.3, 1] }}
      whileHover={{ y: -4 }}
    >
      <Link to={`/evento/${evento.slug}`} className="block">
        <div className="relative">
          <ImagePlaceholder etiqueta="foto del evento" className="aspect-[16/10] w-full" src={evento.imagenUrl} />
          <span className="font-mono-label absolute left-2 top-2 bg-paper px-2 py-1 text-[10px] text-muted">
            {evento.categoria}
          </span>
          {tieneReventa && (
            <span className="absolute right-2 top-2">
              <Badge>reventa</Badge>
            </span>
          )}
        </div>
        <div className="border-b border-ink pb-3" />
        <div className="flex items-center justify-between pt-3">
          <span className="font-mono-label text-[11px] text-muted">{fecha(evento.fechaISO)}</span>
          <span className={`font-mono-label text-[11px] ${stockClass}`}>{stockLabel}</span>
        </div>
        <h3 className="mt-2 text-[21px] font-semibold tracking-tight text-ink">{evento.titulo}</h3>
        <p className="mt-1 text-sm text-muted">{evento.venue}</p>
        <p className="mt-2 text-[15px] font-semibold text-ink">Desde {money(desde)}</p>
      </Link>
    </motion.article>
  );
}
