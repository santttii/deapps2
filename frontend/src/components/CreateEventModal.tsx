import { useEffect, useState, type ChangeEvent } from 'react';
import { useApp } from '../store/AppContext';
import { useToast } from './ToastProvider';
import { ErrorApi, mensajeDeError } from '../lib/api';
import { crearEvento, editarEvento, FOTO_MAXIMO_BYTES, subirImagen, TIPOS_DE_FOTO } from '../servicios/eventos';
import { imagenPorCategoria } from '../data/events';
import type { Categoria, Evento } from '../types';
import Modal from './Modal';
import Input from './Input';
import Button from './Button';

interface CreateEventModalProps {
  abierto: boolean;
  onCerrar: () => void;
  // Con un evento, el formulario lo edita: se abre con sus datos y guarda con PUT. Sin evento, crea.
  evento?: Evento;
}

interface SectorForm {
  // Solo los sectores que ya existen: con id se cambian, sin id se agregan.
  id?: string;
  // Entradas ya vendidas del sector (0 en uno nuevo). Con ventas no se puede quitar, ni cambiar el
  // precio, ni bajar el cupo por debajo de lo vendido: el servidor responde 409.
  vendidas: number;
  nombre: string;
  precio: string;
  cupo: string;
}

const CATEGORIAS: Categoria[] = ['Deportes', 'Música', 'Fiesta', 'Teatro'];

// El servidor no rechaza decimales ni números fuera de rango: esos topes se controlan acá.
const PRECIO_MAXIMO = 100_000_000;
const CUPO_MAXIMO = 1_000_000;

// Errores de sector que el servidor manda sin decir a qué fila corresponden.
const CAMPOS_DE_SECTOR = ['sectores', 'nombre', 'precio', 'cupo'];
// Los campos que tienen dónde mostrar su error. Uno que no esté acá va al aviso general.
const CAMPOS_DEL_FORMULARIO = ['titulo', 'categoria', 'venue', 'fechaISO', 'descripcion', 'imagen', ...CAMPOS_DE_SECTOR];

const AVISO_EN_LINEA = 'border-l-2 border-accent pl-2 text-xs text-ink';

function sectorVacio(): SectorForm {
  return { vendidas: 0, nombre: '', precio: '', cupo: '' };
}

// El input datetime-local muestra aaaa-mm-ddThh:mm; el servidor manda también los segundos.
const paraElInput = (fechaISO: string) => fechaISO.slice(0, 16);

function formularioDe(evento: Evento) {
  return {
    titulo: evento.titulo,
    categoria: evento.categoria,
    venue: evento.venue,
    fecha: paraElInput(evento.fechaISO),
    descripcion: evento.descripcion,
    sectores: evento.sectores.map((s) => ({
      id: s.id,
      vendidas: s.vendidas,
      nombre: s.nombre,
      precio: String(s.precio),
      cupo: String(s.cupo),
    })),
  };
}

// Precio y cupo tienen que ser enteros entre 1 y un máximo. Devuelve el mensaje, o null si está bien.
function errorDeNumero(valor: string, nombre: 'precio' | 'cupo', maximo: number): string | null {
  const numero = Number(valor);
  if (valor.trim() === '' || Number.isNaN(numero) || numero <= 0) return `El ${nombre} tiene que ser mayor a 0.`;
  if (!Number.isInteger(numero)) return `El ${nombre} tiene que ser un número entero.`;
  if (numero > maximo) return `El ${nombre} no puede superar ${maximo.toLocaleString('es-AR')}.`;
  return null;
}

export default function CreateEventModal({ abierto, onCerrar, evento }: CreateEventModalProps) {
  const editando = evento !== undefined;
  const { dispatch } = useApp();
  const { mostrarToast } = useToast();

  // Al editar, el formulario arranca con los datos del evento. El panel monta el modal de edición
  // recién al abrirlo, así cada vez que se abre toma los datos actuales.
  const [inicial] = useState(() => (evento ? formularioDe(evento) : null));
  const [titulo, setTitulo] = useState(inicial?.titulo ?? '');
  const [categoria, setCategoria] = useState<Categoria>(inicial?.categoria ?? 'Deportes');
  const [venue, setVenue] = useState(inicial?.venue ?? '');
  const [fecha, setFecha] = useState(inicial?.fecha ?? '');
  const [descripcion, setDescripcion] = useState(inicial?.descripcion ?? '');
  const [sectores, setSectores] = useState<SectorForm[]>(inicial?.sectores ?? [sectorVacio()]);
  // Errores por campo, con las mismas claves que usa el backend (titulo, venue, fechaISO…).
  const [errores, setErrores] = useState<Record<string, string>>({});
  // Errores de la validación local de cada fila de sector, por índice de fila.
  const [erroresDeFila, setErroresDeFila] = useState<Record<number, string[]>>({});
  const [errorGeneral, setErrorGeneral] = useState('');
  const [creando, setCreando] = useState(false);
  // La foto elegida y su vista previa (una URL local del navegador). Se sube recién al guardar.
  const [foto, setFoto] = useState<File | null>(null);
  const [vistaPrevia, setVistaPrevia] = useState<string | null>(null);
  // Al editar: quitar la foto que tiene el evento y volver a la de su categoría.
  const [sinFoto, setSinFoto] = useState(false);

  // La vista previa ocupa memoria hasta que se libera: al cambiarla o al cerrar el formulario.
  useEffect(() => {
    return () => {
      if (vistaPrevia) URL.revokeObjectURL(vistaPrevia);
    };
  }, [vistaPrevia]);

  function elegirFoto(e: ChangeEvent<HTMLInputElement>) {
    const elegida = e.target.files?.[0];
    e.target.value = ''; // así elegir el mismo archivo otra vez también avisa
    if (!elegida) return;
    if (!TIPOS_DE_FOTO.includes(elegida.type)) {
      setErrores((prev) => ({ ...prev, imagen: 'La imagen tiene que ser JPG, PNG o WEBP.' }));
      return;
    }
    if (elegida.size > FOTO_MAXIMO_BYTES) {
      setErrores((prev) => ({ ...prev, imagen: 'La imagen no puede pasar de 2 MB.' }));
      return;
    }
    setErrores((prev) => ({ ...prev, imagen: '' }));
    setFoto(elegida);
    setVistaPrevia(URL.createObjectURL(elegida));
    setSinFoto(false);
  }

  function quitarFoto() {
    setFoto(null);
    setVistaPrevia(null);
    if (editando) setSinFoto(true);
  }

  // Lo que se ve arriba del formulario: la foto elegida, la que ya tiene el evento o la de la categoría.
  const fotoVisible = vistaPrevia ?? (evento && !sinFoto ? evento.imagenUrl : undefined) ?? imagenPorCategoria(categoria);
  const tieneFotoPropia = foto !== null || (editando && !sinFoto && !!evento?.imagenDelServidor);

  function limpiarErrores() {
    setErrores({});
    setErroresDeFila({});
    setErrorGeneral('');
  }

  function vaciar() {
    setTitulo('');
    setCategoria('Deportes');
    setVenue('');
    setFecha('');
    setDescripcion('');
    setSectores([sectorVacio()]);
    setFoto(null);
    setVistaPrevia(null);
    setSinFoto(false);
    limpiarErrores();
  }

  const hayAlgoEscrito =
    foto !== null ||
    sinFoto ||
    (inicial
      ? JSON.stringify(inicial) !== JSON.stringify({ titulo, categoria, venue, fecha, descripcion, sectores })
      : [titulo, venue, fecha, descripcion].some((v) => v.trim() !== '') ||
        sectores.some((s) => s.nombre.trim() !== '' || s.precio !== '' || s.cupo !== ''));

  // Escape y el clic afuera solo ocultan el modal: lo escrito sigue ahí al volver a abrirlo. Lo
  // que descarta el formulario es Cancelar, y pregunta antes si hay algo cargado.
  function cancelar() {
    const pregunta = editando ? '¿Descartar los cambios?' : '¿Descartar el evento que estabas cargando?';
    if (hayAlgoEscrito && !window.confirm(pregunta)) return;
    vaciar();
    onCerrar();
  }

  function actualizarSector(index: number, campo: keyof SectorForm, valor: string) {
    setSectores((prev) => prev.map((s, i) => (i === index ? { ...s, [campo]: valor } : s)));
  }

  // Al agregar o quitar una fila cambian los índices: los errores por fila se vuelven a calcular al enviar.
  function agregarSector() {
    setSectores((prev) => [...prev, sectorVacio()]);
    setErroresDeFila({});
  }

  function quitarSector(index: number) {
    setSectores((prev) => prev.filter((_, i) => i !== index));
    setErroresDeFila({});
  }

  // Mismos textos que el servidor. Además marca la fila exacta de cada sector inválido, cosa
  // que el servidor no puede: sus errores de sector llegan sin número de fila.
  function validar(): boolean {
    const porCampo: Record<string, string> = {};
    if (!titulo.trim()) porCampo.titulo = 'Ingresá el título.';
    if (!venue.trim()) porCampo.venue = 'Ingresá el lugar.';
    if (!fecha) porCampo.fechaISO = 'Ingresá la fecha.';
    if (!descripcion.trim()) porCampo.descripcion = 'Ingresá una descripción.';

    const porFila: Record<number, string[]> = {};
    sectores.forEach((s, i) => {
      const mensajes = [
        s.nombre.trim() ? null : 'Ingresá el nombre del sector.',
        errorDeNumero(s.precio, 'precio', PRECIO_MAXIMO),
        errorDeNumero(s.cupo, 'cupo', CUPO_MAXIMO),
        Number(s.cupo) < s.vendidas ? `El cupo no puede ser menor que las ${s.vendidas} entradas vendidas.` : null,
      ].filter((m) => m !== null);
      if (mensajes.length > 0) porFila[i] = mensajes;
    });

    setErrores(porCampo);
    setErroresDeFila(porFila);
    return Object.keys(porCampo).length === 0 && Object.keys(porFila).length === 0;
  }

  async function enviar() {
    limpiarErrores();
    if (!validar()) {
      setErrorGeneral('Revisá los campos marcados.');
      return;
    }
    setCreando(true);
    let creado: Evento;
    const datos = {
      titulo: titulo.trim(),
      categoria,
      venue: venue.trim(),
      // El valor del input datetime-local (2026-12-01T21:00) es hora local sin zona, que es lo
      // que espera el backend. Convertirlo con toISOString() le agrega la "Z" y lo rechaza.
      fechaISO: fecha,
      descripcion: descripcion.trim(),
      sectores: sectores.map((s) => ({
        id: s.id,
        nombre: s.nombre.trim(),
        precio: Number(s.precio),
        cupo: Number(s.cupo),
      })),
    };
    try {
      // La foto se sube primero: el evento se guarda con la URL que devuelve. Si después falla el
      // evento, la foto queda guardada sin usar, que no molesta a nadie.
      // Al editar sin tocar la foto se manda la que tiene guardada el servidor: PUT reemplaza todo.
      const imagenUrl = foto
        ? await subirImagen(foto)
        : evento && !sinFoto
          ? (evento.imagenDelServidor ?? null)
          : null;
      creado = evento
        ? await editarEvento(evento.id, { ...datos, imagenUrl })
        : await crearEvento({ ...datos, imagenUrl, sectores: datos.sectores.map(({ id: _id, ...resto }) => resto) });
    } catch (error) {
      // El modal queda abierto con lo escrito. Los errores por campo van a su input, y el aviso
      // general no los repite: el detalle del backend suele ser el mismo texto que el del campo.
      const campos = error instanceof ErrorApi ? error.campos : {};
      setErrores(campos);
      const sinLugar = Object.entries(campos)
        .filter(([campo]) => !CAMPOS_DEL_FORMULARIO.includes(campo))
        .map(([, mensaje]) => mensaje);
      if (Object.keys(campos).length === 0) setErrorGeneral(mensajeDeError(error));
      else setErrorGeneral(sinLugar.length > 0 ? sinLugar.join(' ') : 'Revisá los campos marcados.');
      return;
    } finally {
      setCreando(false);
    }
    // El evento ya existe en el servidor: se suma al catálogo en memoria sin volver a pedirlo.
    dispatch({ type: 'EVENTO_RECIBIDO', payload: creado });
    mostrarToast(editando ? 'Cambios guardados. El catálogo ya muestra el evento actualizado.' : 'Evento creado. Ya está publicado en el catálogo.');
    vaciar();
    onCerrar();
  }

  const erroresDeSectorDelServidor = CAMPOS_DE_SECTOR.map((campo) => errores[campo]).filter(Boolean);

  return (
    <Modal abierto={abierto} onCerrar={onCerrar} titulo={editando ? 'Editar evento' : 'Crear evento'}>
      {/* Los botones van fuera de la parte que se desplaza, así siempre están a la vista. Lo que
          se le resta a la altura es lo que ocupan los márgenes del modal, el título y los botones.
          dvh y no vh: en el celular, vh no descuenta la barra del navegador. */}
      <div className="flex max-h-[calc(100dvh-16rem)] flex-col gap-4 overflow-y-auto pr-1 sm:max-h-[calc(100dvh-13rem)]">
        <div>
          <div className="h-32 overflow-hidden border border-edge">
            <img
              src={fotoVisible}
              alt={tieneFotoPropia ? 'Foto del evento' : `Foto de la categoría ${categoria}`}
              className="h-full w-full object-cover"
            />
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-3">
            <label className="font-mono-label inline-flex min-h-[44px] cursor-pointer items-center border border-edge px-3 text-[11px] text-ink focus-within:outline focus-within:outline-2 focus-within:outline-accent">
              {tieneFotoPropia ? 'Cambiar foto' : 'Subir foto'}
              <input
                type="file"
                accept={TIPOS_DE_FOTO.join(',')}
                onChange={elegirFoto}
                className="sr-only"
                aria-describedby="ayuda-foto"
              />
            </label>
            {tieneFotoPropia && (
              <button type="button" onClick={quitarFoto} className="font-mono-label min-h-[44px] px-2 text-[11px] text-muted">
                quitar foto
              </button>
            )}
          </div>
          <p id="ayuda-foto" className="mt-1.5 text-xs text-muted">
            {foto
              ? `${foto.name} · se sube al guardar.`
              : 'JPG, PNG o WEBP, hasta 2 MB. Sin foto propia se usa la de la categoría.'}
          </p>
          {errores.imagen && (
            <p role="alert" className={AVISO_EN_LINEA}>
              {errores.imagen}
            </p>
          )}
        </div>

        <Input
          label="Título"
          value={titulo}
          onChange={(e) => setTitulo(e.target.value)}
          error={errores.titulo}
          maxLength={200}
        />

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
          {errores.categoria && (
            <p role="alert" className={AVISO_EN_LINEA}>
              {errores.categoria}
            </p>
          )}
        </div>

        <Input
          label="Lugar"
          value={venue}
          onChange={(e) => setVenue(e.target.value)}
          error={errores.venue}
          maxLength={200}
        />
        <Input
          label="Fecha y hora"
          type="datetime-local"
          value={fecha}
          onChange={(e) => setFecha(e.target.value)}
          error={errores.fechaISO}
          max="9999-12-31T23:59"
        />

        <div className="flex flex-col gap-1.5">
          <label htmlFor="descripcion-evento" className="font-mono-label text-[11px] text-muted">
            Descripción
          </label>
          <textarea
            id="descripcion-evento"
            value={descripcion}
            onChange={(e) => setDescripcion(e.target.value)}
            rows={2}
            maxLength={2000}
            aria-invalid={!!errores.descripcion}
            className={`border bg-paper px-3.5 py-2.5 text-[15px] ${errores.descripcion ? 'border-accent' : 'border-edge'}`}
          />
          {errores.descripcion && (
            <p role="alert" className={AVISO_EN_LINEA}>
              {errores.descripcion}
            </p>
          )}
        </div>

        <div className="flex flex-col gap-3">
          <p className="font-mono-label text-[11px] text-muted">Sectores</p>
          {sectores.map((s, i) => (
            <div key={i} role="group" aria-label={`Sector ${i + 1}`} className="flex flex-col gap-2 border border-wire p-3">
              <div className="flex items-end gap-2">
                <div className="min-w-0 flex-1">
                  <Input
                    label="Nombre del sector"
                    value={s.nombre}
                    onChange={(e) => actualizarSector(i, 'nombre', e.target.value)}
                    className="w-full min-w-0"
                    maxLength={120}
                  />
                </div>
                {sectores.length > 1 && s.vendidas === 0 && (
                  <button
                    type="button"
                    onClick={() => quitarSector(i)}
                    className="font-mono-label min-h-[44px] px-2 text-[11px] text-muted"
                    aria-label={`Quitar el sector ${i + 1}`}
                  >
                    quitar
                  </button>
                )}
              </div>
              <div className="grid grid-cols-2 gap-2">
                <Input
                  label="Precio ($)"
                  type="number"
                  min={1}
                  step={1}
                  value={s.precio}
                  onChange={(e) => actualizarSector(i, 'precio', e.target.value)}
                  className="w-full min-w-0"
                  disabled={s.vendidas > 0}
                />
                <Input
                  label="Cupo"
                  type="number"
                  min={Math.max(1, s.vendidas)}
                  step={1}
                  value={s.cupo}
                  onChange={(e) => actualizarSector(i, 'cupo', e.target.value)}
                  className="w-full min-w-0"
                />
              </div>
              {s.vendidas > 0 && (
                <p className="text-xs text-muted">
                  Ya tiene {s.vendidas.toLocaleString('es-AR')} {s.vendidas === 1 ? 'entrada vendida' : 'entradas vendidas'}: no
                  se puede quitar ni cambiar su precio, y el cupo no puede bajar de ese número.
                </p>
              )}
              {erroresDeFila[i] && (
                <p role="alert" className={AVISO_EN_LINEA}>
                  {erroresDeFila[i].join(' ')}
                </p>
              )}
            </div>
          ))}
          {erroresDeSectorDelServidor.length > 0 && (
            <p role="alert" className={AVISO_EN_LINEA}>
              {erroresDeSectorDelServidor.join(' ')}
            </p>
          )}
          <Button variant="secondary" type="button" onClick={agregarSector}>
            + Agregar sector
          </Button>
        </div>

        {errorGeneral && (
          <p role="alert" aria-live="polite" className={AVISO_EN_LINEA}>
            {errorGeneral}
          </p>
        )}
      </div>

      <div className="mt-4 flex flex-col gap-2 sm:flex-row-reverse">
        <Button
          onClick={enviar}
          loading={creando}
          loadingText={editando ? 'Guardando…' : 'Creando evento…'}
          className="sm:flex-1"
        >
          {editando ? 'Guardar cambios' : 'Crear evento'}
        </Button>
        <Button variant="secondary" type="button" onClick={cancelar} disabled={creando} className="sm:flex-1">
          Cancelar
        </Button>
      </div>
    </Modal>
  );
}
