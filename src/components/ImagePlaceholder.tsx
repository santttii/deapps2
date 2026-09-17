interface ImagePlaceholderProps {
  etiqueta: string;
  className?: string;
  src?: string;
}

export default function ImagePlaceholder({ etiqueta, className = '', src }: ImagePlaceholderProps) {
  if (src) {
    return (
      <div className={`relative overflow-hidden bg-fill ${className}`}>
        <img src={src} alt={etiqueta} className="h-full w-full object-cover" />
      </div>
    );
  }

  return (
    <div className={`image-placeholder relative flex items-end ${className}`}>
      <span className="font-mono-label m-2 bg-paper px-2 py-1 text-[10px] text-muted">{etiqueta}</span>
    </div>
  );
}
