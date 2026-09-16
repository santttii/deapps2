export const organizerMetrics = {
  entradasVendidas: 12480,
  recaudado: 284_600_000,
  cupoRestante: 3520,
  enReventa: 186,
  vendidasPorReventa: 124,
  rechazadasPorTope: 31,
  precioPromedioReventa: 19240,
  ventasUltimos10Dias: [30, 45, 38, 62, 80, 55, 100, 48, 35, 52],
  puestosStaff: [
    { puesto: 'Portón 1 · Popular norte', dispositivo: 'tablet-04', escaneos: 2310, estado: 'en línea' as const },
    { puesto: 'Portón 3 · Plateas', dispositivo: 'tablet-07', escaneos: 1884, estado: 'en línea' as const },
    { puesto: 'Portón 5 · Palcos', dispositivo: 'tablet-11', escaneos: 412, estado: 'offline' as const },
  ],
  liquidacion: {
    bruto: 284_600_000,
    comision: 17_076_000,
    reembolsos: 1_240_000,
    aLiquidar: 266_284_000,
  },
  ocupacionPorSector: [
    { nombre: 'Popular norte', vendidas: 3680, cupo: 4000 },
    { nombre: 'Popular sur', vendidas: 3120, cupo: 4000 },
    { nombre: 'Platea A', vendidas: 2000, cupo: 2000 },
    { nombre: 'Platea B', vendidas: 1440, cupo: 2000 },
    { nombre: 'Palcos', vendidas: 90, cupo: 200 },
  ],
  misEventos: [
    { titulo: 'Atlético Norte vs. Racing del Sur', fecha: '12/09/2026', vendidas: 10330, cupo: 12200, estado: 'publicado' as const },
    { titulo: 'Kiroshi — Tour Continental', fecha: '25/09/2026', vendidas: 6100, cupo: 8500, estado: 'publicado' as const },
    { titulo: 'Festival de invierno 2027', fecha: 'sin definir', vendidas: 0, cupo: 0, estado: 'borrador' as const },
  ],
};
