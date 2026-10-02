import { useRef, useState } from 'react';
import { useApp } from '../store/AppContext';
import { useToast } from './ToastProvider';
import { normalizar } from '../lib/format';
import { imagenPorSemilla } from '../data/events';
import type { Categoria } from '../types';
import Modal from './Modal';
import Input from './Input';
import Button from './Button';

interface CreateEventModalProps {
  abierto: boolean;
  onCerrar: () => void;
}

interface SectorForm {
  nombre: string;
  precio: string;
  cupo: string;
}

const CATEGORIAS: Categoria[] = ['Fútbol', 'Música', 'Fiesta', 'Teatro'];

function sectorVacio(): SectorForm {
  return { nombre: '', precio: '', cupo: '' };
}

export default function CreateEventModal({ abierto, onCerrar }: CreateEventModalProps) {
  const { state, dispatch } = useApp();
  const { mostrarToast } = useToast();
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [titulo, setTitulo] = useState('');
  const [categoria, setCategoria] = useState<Categoria>('Fútbol');
  const [venue, setVenue] = useState('');
  const [fecha, setFecha] = useState('');
  const [descripcion, setDescripcion] = useState('');
  const [sectores, setSectores] = useState<SectorForm[]>([sectorVacio()]);
  const [imagenPreview, setImagenPreview] = useState('');
  const [error, setError] = useState('');

  function resetear() {
    setTitulo('');
    setCategoria('Fútbol');
    setVenue('');
    setFecha('');
    setDescripcion('');
    setSectores([sectorVacio()]);
    setImagenPreview('');
    setError('');
  }

  function cerrar() {
    resetear();
    onCerrar();
  }

  function onArchivoImagen(file: File) {
    const lector = new FileReader();
    lector.onload = () => setImagenPreview(String(lector.result));
    lector.readAsDataURL(file);
  }

  function actualizarSector(index: number, campo: keyof SectorForm, valor: string) {
    setSectores((prev) => prev.map((s, i) => (i === index ? { ...s, [campo]: valor } : s)));
  }

  function agregarSector() {
    setSectores((prev) => [...prev, sectorVacio()]);
  }

  function quitarSector(index: number) {
    setSectores((prev) => prev.filter((_, i) => i !== index));
  }

  function crearEvento() {
    if (!titulo.trim() || !venue.trim() || !fecha) {
      setError('Completá título, venue y fecha.');
      return;
    }
    const sectoresValidos = sectores.filter(
      (s) => s.nombre.trim() && Number(s.precio) > 0 && Number(s.cupo) > 0,
    );
    if (sectoresValidos.length === 0) {
      setError('Agregá al menos un sector con nombre, precio y cupo válidos.');
      return;
    }

    const slugBase = normalizar(titulo).replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    let slug = slugBase || `evento-${Date.now()}`;
    let contador = 2;
    while (state.eventos.some((e) => e.slug === slug)) {
      slug = `${slugBase}-${contador}`;
      contador++;
    }
    const id = `ev-${Date.now()}`;

    dispatch({
      type: 'CREAR_EVENTO',
      payload: {
        id,
        slug,
        titulo: titulo.trim(),
        categoria,
        venue: venue.trim(),
        fechaISO: new Date(fecha).toISOString(),
        descripcion: descripcion.trim() || 'Descripción a confirmar.',
        imagenUrl: imagenPreview || imagenPorSemilla(slug),
        sectores: sectoresValidos.map((s, i) => ({
          id: `${id}-sec-${i}`,
          nombre: s.nombre.trim(),
          precio: Math.round(Number(s.precio)),
          cupo: Math.round(Number(s.cupo)),
          vendidas: 0,
        })),
      },
    });

    mostrarToast('Evento creado. Ya está publicado en el catálogo.');
    cerrar();
  }

  return (
    <Modal abierto={abierto} onCerrar={cerrar} titulo="Crear evento">
      <div className="flex max-h-[70vh] flex-col gap-4 overflow-y-auto pr-1">
        <label
          onDrop={(e) => {
            e.preventDefault();
            const file = e.dataTransfer.files[0];
            if (file) onArchivoImagen(file);
          }}
          onDragOver={(e) => e.preventDefault()}
          className="flex h-32 cursor-pointer items-center justify-center overflow-hidden border border-dashed border-edge text-center text-sm text-muted"
        >
          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            className="sr-only"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) onArchivoImagen(file);
            }}
          />
          {imagenPreview ? (
            <img src={imagenPreview} alt="Vista previa del evento" className="h-full w-full object-cover" />
          ) : (
            <span>Imagen del evento · arrastrá o hacé click</span>
          )}
        </label>

        <Input label="Título" value={titulo} onChange={(e) => setTitulo(e.target.value)} />

        <div className="flex flex-col gap-1.5">
          <label htmlFor="categoria-evento" className="font-mono-label text-[11px] text-muted">
            Categoría
          </label>
          <select
            id="categoria-evento"
            value={categoria}
            onChange={(e) => setCategoria(e.target.value as Categoria)}
            className="min-h-[44px] border border-edge bg-paper px-3 text-[15px]"
          >
            {CATEGORIAS.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>

        <Input label="Venue" value={venue} onChange={(e) => setVenue(e.target.value)} />
        <Input label="Fecha y hora" type="datetime-local" value={fecha} onChange={(e) => setFecha(e.target.value)} />

        <div className="flex flex-col gap-1.5">
          <label htmlFor="descripcion-evento" className="font-mono-label text-[11px] text-muted">
            Descripción
          </label>
          <textarea
            id="descripcion-evento"
            value={descripcion}
            onChange={(e) => setDescripcion(e.target.value)}
            rows={2}
            className="border border-edge bg-paper px-3.5 py-2.5 text-[15px]"
          />
        </div>

        <div className="flex flex-col gap-3">
          <p className="font-mono-label text-[11px] text-muted">Sectores</p>
          {sectores.map((s, i) => (
            <div key={i} className="flex flex-wrap items-end gap-2 border border-wire p-3">
              <Input
                label="Nombre"
                hideLabel
                value={s.nombre}
                onChange={(e) => actualizarSector(i, 'nombre', e.target.value)}
                placeholder="Sector"
                className="flex-1"
              />
              <Input
                label="Precio"
                hideLabel
                type="number"
                value={s.precio}
                onChange={(e) => actualizarSector(i, 'precio', e.target.value)}
                placeholder="Precio"
                className="w-28"
              />
              <Input
                label="Cupo"
                hideLabel
                type="number"
                value={s.cupo}
                onChange={(e) => actualizarSector(i, 'cupo', e.target.value)}
                placeholder="Cupo"
                className="w-24"
              />
              {sectores.length > 1 && (
                <button
                  type="button"
                  onClick={() => quitarSector(i)}
                  className="font-mono-label px-2 py-2 text-[11px] text-muted"
                  aria-label="Quitar sector"
                >
                  quitar
                </button>
              )}
            </div>
          ))}
          <Button variant="secondary" type="button" onClick={agregarSector}>
            + Agregar sector
          </Button>
        </div>

        {error && (
          <p role="alert" aria-live="polite" className="border-l-2 border-accent pl-2 text-xs text-ink">
            {error}
          </p>
        )}

        <Button onClick={crearEvento}>Crear evento</Button>
      </div>
    </Modal>
  );
}
