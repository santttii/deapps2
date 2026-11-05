interface ImagePlaceholderProps {
  etiqueta: string;
  className?: string;
  src?: string;
  // La foto principal de la pantalla: se pide primero en lugar de esperar a que se vea.
  prioritaria?: boolean;
}

export default function ImagePlaceholder({ etiqueta, className = '', src, prioritaria = false }: ImagePlaceholderProps) {
  if (src) {
    // alt vacío: la foto va siempre junto al nombre del evento, que es lo que la identifica; con un
    // alt, el lector de pantalla diría lo mismo dos veces. La foto va absoluta para cubrir la caja
    // entera: el alto lo da la clase de la caja, y un h-full no se resuelve contra un alto mínimo.
    return (
      <div className={`relative overflow-hidden bg-fill ${className}`}>
        <img
          src={src}
          alt=""
          loading={prioritaria ? 'eager' : 'lazy'}
          decoding={prioritaria ? 'auto' : 'async'}
          fetchPriority={prioritaria ? 'high' : 'auto'}
          className="absolute inset-0 h-full w-full object-cover"
        />
      </div>
    );
  }

  return (
    <div className={`image-placeholder relative flex items-end ${className}`}>
      <span className="font-mono-label m-2 bg-paper px-2 py-1 text-[10px] text-muted">{etiqueta}</span>
    </div>
  );
}
