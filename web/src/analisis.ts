import datos from './data/analisis.json';

/**
 * El pase determinista sobre el celular, tal como lo dejó `clasificar_celular.py`.
 *
 * Viaja en el bundle en vez de consultarse en vivo porque es una foto de un recorrido que tardó
 * horas: 47 761 archivos leídos uno por uno a través del túnel. Recalcularlo al abrir la pestaña
 * sería pedirle al teléfono el trabajo entero otra vez para mostrar lo mismo.
 *
 * Lo que sí es en vivo es el navegador, que lee del dispositivo en el momento. Las dos vistas
 * responden preguntas distintas: ésta dice qué hay y qué conviene hacer; aquélla, qué hay ahora.
 */

/** Qué hacer con una carpeta. Comparte vocabulario con las propuestas, pero no es una de ellas. */
export type Sugerencia = 'IMPORTAR_PC' | 'BORRAR' | 'ARCHIVAR' | 'MOVER' | 'REVISAR';

export type CarpetaAnalizada = {
  /** Id de ubicación del dispositivo. Va aparte de `dir` porque lleva barras dentro. */
  loc: string;
  /** Ruta relativa a la raíz de la ubicación, que es la forma que aceptan las herramientas. */
  dir: string;
  /** Archivos directamente en la carpeta. */
  n: number;
  b: number;
  fam: string | null;
  org: string | null;
  /** Cuántos de sus archivos quedaron en revisión. */
  rev: number;
  sug: Sugerencia;
  por: string;
};

type Cuenta = { files: number; bytes: number };

export type Analisis = {
  generado: string;
  policy_version: string;
  totales: { files: number; dirs: number; bytes: number; folders_with_files: number };
  por_familia: Record<string, Cuenta>;
  por_origen: Record<string, Cuenta>;
  por_regla: Record<string, Cuenta>;
  por_estado: Record<string, Cuenta>;
  por_sugerencia: Record<string, Cuenta>;
  por_ubicacion: Record<string, Cuenta>;
  techo_confianza: number;
  peso_declarado: number;
  fuera_de_politica: { files: number; bytes: number };
  necesitan_ia: number;
  sensibles: number;
  /** Carpetas que el recorrido no pudo leer. Su ausencia no es vacío: es desconocido. */
  pendientes: string[];
  carpetas: CarpetaAnalizada[];
};

export const analisis = datos as Analisis;

export const SUGERENCIA_LABEL: Record<Sugerencia, string> = {
  IMPORTAR_PC: 'Importar al PC',
  BORRAR: 'Borrar',
  ARCHIVAR: 'Archivar',
  MOVER: 'Mover',
  REVISAR: 'Revisar',
};

/** El tono con el que se pinta cada sugerencia, reusando los chips que ya tiene el panel. */
export const SUGERENCIA_TONO: Record<Sugerencia, string> = {
  IMPORTAR_PC: 'chip ok',
  BORRAR: 'chip critical',
  ARCHIVAR: 'chip warning',
  MOVER: 'chip',
  REVISAR: 'chip',
};

/** La ubicación sin el prefijo del proveedor, que no aporta nada al leerla. */
export function ubicacionCorta(loc: string): string {
  const tail = loc.split(':').pop() ?? loc;
  return tail === '' ? loc : tail;
}

export function rutaLegible(c: CarpetaAnalizada): string {
  const base = ubicacionCorta(c.loc);
  return c.dir === '' ? `${base}/` : `${base}/${c.dir}`;
}

/** El nombre de la carpeta, para la propuesta. La raíz de una ubicación se nombra por ella. */
export function nombreCarpeta(c: CarpetaAnalizada): string {
  if (c.dir === '') return ubicacionCorta(c.loc);
  return c.dir.split('/').pop() ?? c.dir;
}
