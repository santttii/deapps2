const GRID = 21;

function hash(str: string): number {
  let h = 0;
  for (let i = 0; i < str.length; i++) {
    h = (h << 5) - h + str.charCodeAt(i);
    h |= 0;
  }
  return h;
}

function celdaActiva(id: string, x: number, y: number): boolean {
  const n = hash(`${id}-${x}-${y}`);
  return (n & 1) === 0;
}

function enOjoOrientacion(x: number, y: number): boolean {
  const zonas = [
    [0, 0],
    [GRID - 7, 0],
    [0, GRID - 7],
  ];
  return zonas.some(([zx, zy]) => x >= zx && x < zx + 7 && y >= zy && y < zy + 7);
}

function celdaOjo(x: number, y: number): boolean {
  const zonas = [
    [0, 0],
    [GRID - 7, 0],
    [0, GRID - 7],
  ];
  for (const [zx, zy] of zonas) {
    if (x < zx || x >= zx + 7 || y < zy || y >= zy + 7) continue;
    const lx = x - zx;
    const ly = y - zy;
    const enBorde = lx === 0 || lx === 6 || ly === 0 || ly === 6;
    const enCentro = lx >= 2 && lx <= 4 && ly >= 2 && ly <= 4;
    return enBorde || enCentro;
  }
  return false;
}

interface QRCodeProps {
  id: string;
  size?: number;
}

export default function QRCode({ id, size = 200 }: QRCodeProps) {
  const cell = size / GRID;
  const cells: { x: number; y: number }[] = [];
  for (let y = 0; y < GRID; y++) {
    for (let x = 0; x < GRID; x++) {
      if (enOjoOrientacion(x, y)) {
        if (celdaOjo(x, y)) cells.push({ x, y });
      } else if (celdaActiva(id, x, y)) {
        cells.push({ x, y });
      }
    }
  }

  return (
    <svg
      width={size}
      height={size}
      viewBox={`0 0 ${size} ${size}`}
      role="img"
      aria-label={`Código QR de la entrada ${id}`}
      className="bg-white p-2"
    >
      {cells.map(({ x, y }) => (
        <rect key={`${x}-${y}`} x={x * cell} y={y * cell} width={cell} height={cell} fill="#1A1917" />
      ))}
    </svg>
  );
}
