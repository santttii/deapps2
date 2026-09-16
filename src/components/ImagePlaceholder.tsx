interface ImagePlaceholderProps {
  etiqueta: string;
  className?: string;
}

export default function ImagePlaceholder({ etiqueta, className = '' }: ImagePlaceholderProps) {
  return (
    <div className={`image-placeholder relative flex items-end ${className}`}>
      <span className="font-mono-label m-2 bg-paper px-2 py-1 text-[10px] text-muted">{etiqueta}</span>
    </div>
  );
}
