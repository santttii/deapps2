/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        paper: '#FAF9F7',
        desk: '#EDEBE6',
        ink: '#1A1917',
        graphite: '#4A473F',
        muted: '#6B675E',
        faint: '#9A968C',
        line: '#E3E0D9',
        edge: '#D9D5CC',
        wire: '#C9C6BF',
        fill: '#F2F0EA',
        slab: '#E8E5DE',
        accent: '#2E5BFF',
        accentDk: '#1B3FCC',
        accentLt: '#7E97FF',
        onDark: '#A8A49A',
        darkLine: '#35332E',
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
