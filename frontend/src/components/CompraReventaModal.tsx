import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { calcularServicio, money } from '../lib/format';
import { esDNIValido, esNombreValido, esNumeroTarjetaValido, formatearNumeroTarjeta, soloDigitosDNI } from '../lib/validate';
import { comprarReventa } from '../servicios/reventa';
import { nuevaClave, tokenDePrueba } from '../servicios/ventas';
import { useApp } from '../store/AppContext';
import type { Asistente, Evento, MedioPago, Publicacion } from '../types';
import Button from './Button';
import Input from './Input';
import Modal from './Modal';
import { useToast } from './ToastProvider';

interface Props {
  publicacion: Publicacion | null; // null: cerrado
  evento: Evento | undefined;
  onCerrar: () => void;
  // Se llama cuando la publicación ya no se puede comprar (vendida, retirada, reventa cerrada),
  // para que la pantalla vuelva a pedir el listado.
  onListadoVencido: () => void;
}

const MEDIOS: { id: MedioPago; label: string }[] = [
  { id: 'pasarela', label: 'Pasarela externa' },
  { id: 'tarjeta', label: 'Tarjeta' },
  { id: 'transferencia', label: 'Transferencia' },
];

// Errores después de los cuales la publicación ya no está a la venta para este usuario.
const TIPOS_QUE_VENCEN_EL_LISTADO = ['publicacion-no-activa', 'reventa-cerrada', 'compra-propia', 'no-encontrado'];

// Compra de una publicación de la reventa oficial: titular, medio de pago y POST
// /api/v1/reventas/{id}/compra. La entrada nueva se emite a nombre del titular con un QR nuevo y
// aparece en "Mis entradas" porque Reventa la registra como una venta del comprador.
export default function CompraReventaModal({ publicacion, evento, onCerrar, onListadoVencido }: Props) {
  // El modal se monta de nuevo con cada publicación (key), así el formulario y la clave
  // de idempotencia empiezan limpios.
  return (
    <Modal abierto={!!publicacion} onCerrar={onCerrar} titulo="Comprar en la reventa oficial">
      {publicacion && (
        <Formulario
          key={publicacion.id}
          publicacion={publicacion}
          evento={evento}
          onCerrar={onCerrar}
          onListadoVencido={onListadoVencido}
        />
      )}
    </Modal>
  );
}

function Formulario({ publicacion, evento, onCerrar, onListadoVencido }: Props & { publicacion: Publicacion }) {
  const { usuarioActual } = useApp();
  const { mostrarToast } = useToast();
  const navigate = useNavigate();
  const [titular, setTitular] = useState<Asistente>({
    nombre: usuarioActual?.nombre ?? '',
    apellido: usuarioActual?.apellido ?? '',
    dni: usuarioActual?.dni ?? '',
  });
  const [medioPago, setMedioPago] = useState<MedioPago>('pasarela');
  const [tarjeta, setTarjeta] = useState('');
  const [errores, setErrores] = useState<Record<string, string>>({});
  const [errorCompra, setErrorCompra] = useState('');
  const [comprando, setComprando] = useState(false);
  // Una clave por intento de compra. Si el pedido termina sin resultado (504 o sin conexión) se
  // repite con la misma clave: el backend no cobra dos veces. Después de un rechazo se usa otra.
  const [clave, setClave] = useState(nuevaClave);

  const sector = evento?.sectores.find((s) => s.id === publicacion.sectorId);
  const cargo = calcularServicio(publicacion.precio);

  function validar(): boolean {
    const e: Record<string, string> = {};
    if (!esNombreValido(titular.nombre)) e.nombre = 'Ingresá el nombre.';
    if (!esNombreValido(titular.apellido)) e.apellido = 'Ingresá el apellido.';
    if (!esDNIValido(titular.dni)) e.dni = 'El DNI tiene 7 u 8 dígitos.';
    if (medioPago === 'tarjeta' && !esNumeroTarjetaValido(tarjeta)) e.tarjeta = 'El número debe tener 16 dígitos.';
    setErrores(e);
    return Object.keys(e).length === 0;
  }

  async function confirmar() {
    if (!validar()) return;
    setComprando(true);
    setErrorCompra('');
    try {
      await comprarReventa(publicacion.id, titular, { medioPago, token: tokenDePrueba(medioPago, tarjeta) }, clave);
      mostrarToast('Compraste la entrada en la reventa oficial.');
      onCerrar();
      navigate('/mis-entradas');
    } catch (error) {
      setErrorCompra(mensajeDeError(error));
      const sinResultado = error instanceof ErrorApi && (error.estado === 504 || error.estado === 0);
      if (!sinResultado) setClave(nuevaClave());
      if (error instanceof ErrorApi && TIPOS_QUE_VENCEN_EL_LISTADO.includes(error.tipo)) onListadoVencido();
      setComprando(false);
    }
  }

  return (
    <div className="flex flex-col gap-4 text-sm text-graphite">
      <p>
        {evento?.titulo ?? publicacion.eventoId} · {sector?.nombre ?? publicacion.sectorId}
      </p>
      <div className="grid grid-cols-3 gap-2 border border-wire p-3 text-xs">
        <div>
          <p className="font-mono-label text-[10px] text-muted">precio</p>
          <p className="font-semibold text-ink">{money(publicacion.precio)}</p>
        </div>
        <div>
          <p className="font-mono-label text-[10px] text-muted">servicio (estimado)</p>
          <p>{money(cargo)}</p>
        </div>
        <div>
          <p className="font-mono-label text-[10px] text-muted">total</p>
          <p className="font-semibold text-ink">{money(publicacion.precio + cargo)}</p>
        </div>
      </div>
      <p className="text-xs text-muted">
        La entrada se emite de nuevo, con otro QR, a nombre de quien va a asistir. El importe final lo
        calcula el servidor.
      </p>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Input
          label="Nombre"
          value={titular.nombre}
          onChange={(e) => setTitular((t) => ({ ...t, nombre: e.target.value }))}
          error={errores.nombre}
        />
        <Input
          label="Apellido"
          value={titular.apellido}
          onChange={(e) => setTitular((t) => ({ ...t, apellido: e.target.value }))}
          error={errores.apellido}
        />
        <Input
          label="DNI"
          inputMode="numeric"
          value={titular.dni}
          onChange={(e) => setTitular((t) => ({ ...t, dni: soloDigitosDNI(e.target.value) }))}
          error={errores.dni}
        />
      </div>

      <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
        {MEDIOS.map((m) => (
          <button
            key={m.id}
            type="button"
            onClick={() => setMedioPago(m.id)}
            className={`h-12 border px-3 text-left text-sm font-medium ${
              medioPago === m.id ? 'border-2 border-accent' : 'border-edge'
            }`}
          >
            {m.label}
          </button>
        ))}
      </div>
      {medioPago === 'tarjeta' && (
        <Input
          label="Número de tarjeta"
          value={tarjeta}
          onChange={(e) => setTarjeta(formatearNumeroTarjeta(e.target.value))}
          error={errores.tarjeta}
          maxLength={19}
          hint="Cobro de prueba: terminada en 0002 se rechaza y en 0504 la pasarela no responde."
        />
      )}

      {errorCompra && (
        <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
          {errorCompra}
        </p>
      )}

      <Button loading={comprando} loadingText="Procesando pago…" onClick={confirmar}>
        Confirmar compra
      </Button>
    </div>
  );
}
