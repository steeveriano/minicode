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
 * True for an address that cannot leave the local network.
 *
 * This is the whole basis for admitting `http://` at all: a plaintext request to 192.168.x.x never
 * crosses a router, so there is no wire to tap that the attacker is not already sitting on. The
 * same request to a public host would put a device token on the open internet in the clear.
 */
export function isPrivateHost(host: string): boolean {
  const name = host.toLowerCase().replace(/^\[|\]$/g, '');
  if (name === 'localhost' || name.endsWith('.local') || name.endsWith('.localhost')) return true;
  if (name === '::1') return true;

  const octets = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(name);
  if (!octets) return false;
  const parts = octets.slice(1, 5).map(Number);
  if (parts.some((n) => Number.isNaN(n) || n > 255)) return false;
  const [a = -1, b = -1] = parts;

  if (a === 127) return true; // loopback
  if (a === 10) return true; // 10.0.0.0/8
  if (a === 192 && b === 168) return true; // 192.168.0.0/16
  if (a === 172 && b >= 16 && b <= 31) return true; // 172.16.0.0/12
  if (a === 169 && b === 254) return true; // link-local
  return false;
}

/**
 * Resolves a device slug to its endpoint. Both values come from the environment, keyed by slug, so
 * adding a device is two environment variables and a row — never a code change, and never a
 * credential in the database or the bundle.
 *
 * `https://` is required for anything reachable from outside. Plain `http://` is admitted only for a
 * private address, which is what makes "LAN mode" possible: run this function locally and it can
 * talk to the phone across the room at full speed, with the token still on the server side of the
 * boundary rather than in the page.
 */
export function deviceFor(slug: string): Device | null {
  if (!/^[a-z0-9][a-z0-9-]{1,31}$/.test(slug)) return null;
  const key = slug.toUpperCase().replace(/-/g, '_');
  const raw = process.env[`DEVICE_${key}_URL`];
  const token = process.env[`DEVICE_${key}_TOKEN`];
  if (!raw || !token) return null;

  let parsed: URL;
  try {
    parsed = new URL(raw);
  } catch {
    return null;
  }
  if (parsed.protocol === 'https:') return { slug, url: trimSlash(raw), token };
  if (parsed.protocol === 'http:' && isPrivateHost(parsed.hostname)) {
    return { slug, url: trimSlash(raw), token };
  }
  return null;
}

function trimSlash(url: string): string {
  return url.replace(/\/+$/, '');
}

/**
 * Calls one tool on a device from trusted server code, with a tool name this file chose.
 *
 * Deliberately not routed through the allowlist: that gate exists to bound what a *browser* may
 * ask for, and the names used here are literals in our own functions. `android_share_file_via_web`
 * in particular stays out of the allowlist on purpose — the browser must not be able to mint
 * public, unauthenticated URLs for arbitrary device files, so the only path to a file's bytes is
 * the one that re-serves them from this origin.
 *
 * @returns the text of the tool's first content block, banner stripped.
 */
export async function callDeviceTool(
  device: Device,
  name: string,
  args: Record<string, unknown>,
): Promise<string> {
  const res = await fetch(`${device.url}/mcp`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${device.token}`,
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 1,
      method: 'tools/call',
      params: { name, arguments: args },
    }),
    signal: AbortSignal.timeout(DEVICE_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`device answered ${res.status}`);

  const payload = (await res.json()) as {
    result?: { content?: { text?: string }[] };
  };
  const text = payload.result?.content?.[0]?.text;
  if (typeof text !== 'string') throw new Error('device returned no text content');
  const newline = text.startsWith('CAUTION:') ? text.indexOf('\n') : -1;
  return newline === -1 ? text : text.slice(newline + 1);
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
