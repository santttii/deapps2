import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'secondary' | 'ghost';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  loading?: boolean;
  loadingText?: string;
  children: ReactNode;
}

const base =
  'inline-flex items-center justify-center gap-2 px-5 py-3 text-sm font-medium tracking-tight transition-transform duration-200 disabled:cursor-not-allowed disabled:opacity-40 min-h-[44px]';

const variants: Record<Variant, string> = {
  primary: 'bg-accent text-white hover:-translate-y-0.5 hover:bg-accentDk active:translate-y-0',
  secondary: 'border border-edge text-ink hover:-translate-y-0.5 active:translate-y-0',
  ghost: 'border border-white text-white hover:bg-white hover:text-ink',
};

export default function Button({
  variant = 'primary',
  loading = false,
  loadingText = 'Procesando…',
  disabled,
  className = '',
  children,
  ...rest
}: ButtonProps) {
  return (
    <button
      className={`${base} ${variants[variant]} ${className}`}
      disabled={disabled || loading}
      {...rest}
    >
      {loading && (
        <span
          className="h-3.5 w-3.5 animate-spin border-2 border-current border-t-transparent"
          aria-hidden="true"
        />
      )}
      {loading ? loadingText : children}
    </button>
  );
}
