// Cada color lee su variable de src/index.css, que cambia según el tema (normal, claro u oscuro).
// Las variables van en canales R G B, así que <alpha-value> deja seguir usando opacidades como
// bg-paper/95. Los valores de cada tema y su contraste están comentados en src/index.css.
const color = (variable) => `rgb(var(--color-${variable}) / <alpha-value>)`;

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        paper: color('paper'),
        desk: color('desk'),
        panel: color('panel'),
        ink: color('ink'),
        graphite: color('graphite'),
        muted: color('muted'),
        // Lo más claro que se usa para texto: en el tema normal da 4,57:1 sobre paper, el mínimo de
        // WCAG AA es 4,5:1.
        faint: color('faint'),
        line: color('line'),
        edge: color('edge'),
        wire: color('wire'),
        fill: color('fill'),
        slab: color('slab'),
        accent: color('accent'),
        accentDk: color('accent-dk'),
        accentLt: color('accent-lt'),
        onAccent: color('on-accent'),
        // La superficie oscura (encabezado, franja de la reventa, fondo de los modales) y su texto.
        dark: color('dark'),
        onDarkStrong: color('on-dark-strong'),
        onDark: color('on-dark'),
        darkLine: color('dark-line'),
      },
      fontFamily: {
        sans: ['Archivo', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'monospace'],
      },
      borderRadius: {
        none: '0px',
        DEFAULT: '0px',
        dot: '6px',
      },
      keyframes: {
        spin: {
          to: { transform: 'rotate(360deg)' },
        },
        tick: {
          '0%': { transform: 'scale(0.4)', opacity: '0' },
          '60%': { transform: 'scale(1.12)', opacity: '1' },
          '100%': { transform: 'scale(1)', opacity: '1' },
        },
        qrIn: {
          '0%': { opacity: '0', transform: 'scale(0.86)', filter: 'blur(8px)' },
          '100%': { opacity: '1', transform: 'scale(1)', filter: 'blur(0)' },
        },
        shake: {
          '0%, 100%': { transform: 'translateX(0)' },
          '20%': { transform: 'translateX(-6px)' },
          '40%': { transform: 'translateX(6px)' },
          '60%': { transform: 'translateX(-6px)' },
          '80%': { transform: 'translateX(6px)' },
        },
        pulseDot: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0.35' },
        },
        scanLine: {
          '0%': { transform: 'translateY(0)' },
          '100%': { transform: 'translateY(100%)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
      },
      animation: {
        spin: 'spin 0.7s linear infinite',
        tick: 'tick 0.4s cubic-bezier(0.16,1,0.3,1)',
        qrIn: 'qrIn 0.5s cubic-bezier(0.16,1,0.3,1)',
        shake: 'shake 0.32s cubic-bezier(0.16,1,0.3,1)',
        pulseDot: 'pulseDot 2s ease-in-out infinite',
        scanLine: 'scanLine 2s ease-in-out infinite alternate',
        shimmer: 'shimmer 1.2s infinite linear',
      },
    },
  },
  plugins: [],
};
