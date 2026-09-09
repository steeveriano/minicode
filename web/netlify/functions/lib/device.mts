/**
 * Shared pieces for the device proxy functions.
 *
 * The panel is a static site, so it cannot hold a secret: anything in the bundle or in browser
 * storage belongs to whoever holds the browser. The device's bearer token therefore lives only in
 * this function's environment, and the browser talks to its own origin. That also keeps the page's
 * `connect-src` pinned to one host no matter how many devices are added, and it means revoking a
 * viewer in `device_storage.viewers` cuts access to the phone itself, not just to stored data.
 */

/** How long we wait on a device before giving up. Netlify caps a synchronous function at 26 s. */
export const DEVICE_TIMEOUT_MS = 22_000;

/** What a device's stored access level permits. Anything absent from these sets is never forwarded. */
export type AccessLevel = 'read' | 'write' | 'full';

/**
 * Browsing. Always permitted, and the only level a device starts at.
 */
const READ_TOOLS = [
  'android_list_storage_locations',
  'android_list_files',
  'android_disk_usage',
  'android_read_file',
  'android_list_apps',
  'android_list_quarantine_batches',
  'android_get_screen_state',
] as const;

/**
 * Changes that can be undone. Quarantine belongs here rather than with deletion: the device treats
 * it as a reversible move, and `android_restore_quarantine_batch` brings a batch back.
 */
const WRITE_TOOLS = [
  'android_write_file',
  'android_append_file',
  'android_file_replace',
  'android_move_file',
  'android_download_from_url',
  'android_quarantine_files',
  'android_restore_quarantine_batch',
] as const;

/**
 * Changes that cannot. Separated on purpose: a panel that can tidy files is a different risk from
 * one that can destroy the only copy of something.
 */
const DESTRUCTIVE_TOOLS = ['android_delete_file', 'android_purge_quarantine_batch'] as const;

/** The read set, exported for tests and for the panel to reason about what it may offer. */
export const ALLOWED_TOOLS: ReadonlySet<string> = new Set(READ_TOOLS);

/** Resolves a stored access level to the exact set of tool names the proxy will forward. */
export function toolsFor(level: AccessLevel): ReadonlySet<string> {
  if (level === 'full') return new Set([...READ_TOOLS, ...WRITE_TOOLS, ...DESTRUCTIVE_TOOLS]);
  if (level === 'write') return new Set([...READ_TOOLS, ...WRITE_TOOLS]);
  return new Set(READ_TOOLS);
}

/** JSON-RPC methods the proxy forwards at all. */
const ALLOWED_METHODS: ReadonlySet<string> = new Set([
  'initialize',
  'notifications/initialized',
  'tools/list',
  'ping',
  'tools/call',
]);

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  });
}

/** Extracts the caller's Supabase access token, or null when the header is absent or malformed. */
export function bearerFrom(req: Request): string | null {
  const raw = req.headers.get('authorization') ?? '';
  if (!raw.toLowerCase().startsWith('bearer ')) return null;
  const token = raw.slice(7).trim();
  return token.length > 0 ? token : null;
}

async function callRpc(name: string, accessToken: string, body: unknown): Promise<unknown> {
  const url = process.env.SUPABASE_URL;
  const key = process.env.SUPABASE_PUBLISHABLE_KEY;
  if (!url || !key) throw new Error('SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY are not configured');

  const res = await fetch(`${url}/rest/v1/rpc/${name}`, {
    method: 'POST',
    headers: {
      apikey: key,
      authorization: `Bearer ${accessToken}`,
      'content-type': 'application/json',
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(8_000),
  });
  if (!res.ok) return null;
  return res.json();
}

/**
 * Asks Supabase whether this caller may reach this device, and with what level.
 *
 * One round trip answers both questions. It runs as the caller, so no service-role key is involved
 * and a bug here cannot escalate: the worst case is a false negative. Null covers "not signed in",
 * "not on the list" and "no such device" alike, so none of the three is distinguishable from
 * outside.
 */
export async function gateFor(accessToken: string, slug: string): Promise<AccessLevel | null> {
  const result = (await callRpc('fleet_gate', accessToken, { p_slug: slug })) as {
    accessLevel?: unknown;
  } | null;
  const level = result?.accessLevel;
  return level === 'read' || level === 'write' || level === 'full' ? level : null;
}

export type Device = { slug: string; url: string; token: string };

/**
 * Resolves a device slug to its endpoint. Both values come from the environment, keyed by slug, so
 * adding a device is two environment variables and a row — never a code change, and never a
 * credential in the database or the bundle.
 */
export function deviceFor(slug: string): Device | null {
  if (!/^[a-z0-9][a-z0-9-]{1,31}$/.test(slug)) return null;
  const key = slug.toUpperCase().replace(/-/g, '_');
  const url = process.env[`DEVICE_${key}_URL`];
  const token = process.env[`DEVICE_${key}_TOKEN`];
  if (!url || !token) return null;
  if (!url.startsWith('https://')) return null;
  return { slug, url: url.replace(/\/+$/, ''), token };
}

/** A single JSON-RPC request, as far as the proxy needs to understand one. */
type RpcMessage = { method?: unknown; params?: { name?: unknown } };

/**
 * Returns null when the payload may be forwarded at [level], or a reason when it may not. Accepts a
 * batch, because the transport allows one — and a batch is exactly where a blocked call would
 * otherwise ride along beside an allowed one.
 */
export function rejectionReason(payload: unknown, level: AccessLevel = 'read'): string | null {
  const permitted = toolsFor(level);
  const messages: RpcMessage[] = Array.isArray(payload) ? payload : [payload as RpcMessage];
  if (messages.length === 0) return 'cuerpo vacío';
  if (messages.length > 20) return 'lote demasiado grande';

  for (const message of messages) {
    if (typeof message !== 'object' || message === null) return 'mensaje inválido';
    const method = message.method;
    if (typeof method !== 'string' || !ALLOWED_METHODS.has(method)) {
      return `método no permitido: ${String(method)}`;
    }
    if (method === 'tools/call') {
      const name = message.params?.name;
      if (typeof name !== 'string' || !permitted.has(name)) {
        return `herramienta no permitida: ${String(name)}`;
      }
    }
  }
  return null;
}
