#!/usr/bin/env node
/**
 * Captures a storage snapshot from a running Android MCP server.
 *
 *   MCP_URL=https://<tunnel>/mcp MCP_TOKEN=<token> npm run capture
 *
 * Writes `src/data/snapshot.json`, which the page imports at build time. Keeping the
 * capture out of the browser is deliberate: the page is public, and this script needs a token that
 * grants full control of the phone.
 */
import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseUsage, payload } from './parse-usage.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));

const URL_ = process.env.MCP_URL;
const TOKEN = process.env.MCP_TOKEN;
const DEPTH = Number(process.env.MCP_DEPTH ?? 2);
const SUPABASE_URL = process.env.SUPABASE_URL;
const SERVICE_KEY = process.env.SUPABASE_SERVICE_ROLE_KEY;
const DRY_RUN_FILE = process.env.SNAPSHOT_OUT;

if (!URL_ || !TOKEN) {
  console.error('Set MCP_URL and MCP_TOKEN.');
  process.exit(1);
}
if (!DRY_RUN_FILE && (!SUPABASE_URL || !SERVICE_KEY)) {
  console.error(
    'Set SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY, or SNAPSHOT_OUT to write a file instead.',
  );
  process.exit(1);
}

let nextId = 1;

async function call(name, args) {
  const response = await fetch(URL_, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      accept: 'application/json, text/event-stream',
      authorization: `Bearer ${TOKEN}`,
    },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: nextId++,
      method: 'tools/call',
      params: { name, arguments: args },
    }),
  });
  if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
  const body = await response.json();
  if (body.error) throw new Error(`${name}: ${body.error.message}`);
  const text = body.result?.content?.find((c) => c.type === 'text')?.text ?? '';
  return { text, isError: body.result?.isError === true };
}

const initialize = await fetch(URL_, {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
    accept: 'application/json, text/event-stream',
    authorization: `Bearer ${TOKEN}`,
  },
  body: JSON.stringify({
    jsonrpc: '2.0',
    id: 0,
    method: 'initialize',
    params: {
      protocolVersion: '2025-06-18',
      capabilities: {},
      clientInfo: { name: 'mcp-storage-viewer-capture', version: '0.1.0' },
    },
  }),
}).then((r) => r.json());

const serverVersion = initialize.result?.serverInfo
  ? `${initialize.result.serverInfo.name} ${initialize.result.serverInfo.version}`
  : null;

const listed = await call('android_list_storage_locations', {});
const locations = JSON.parse(payload(listed.text));

const out = {
  schemaVersion: 1,
  capturedAt: new Date().toISOString(),
  device: {
    availableBytes: locations.find((l) => l.available_bytes != null)?.available_bytes ?? null,
    serverVersion,
  },
  locations: [],
};

for (const location of locations) {
  process.stderr.write(`  ${location.id} … `);
  const started = Date.now();
  let root = null;
  let error;
  try {
    const usage = await call('android_disk_usage', {
      location_id: location.id,
      path: '',
      max_depth: DEPTH,
    });
    if (usage.isError) error = payload(usage.text);
    else root = parseUsage(usage.text);
  } catch (e) {
    error = e.message;
  }
  process.stderr.write(`${error ? `failed: ${error}` : 'ok'} (${((Date.now() - started) / 1000).toFixed(1)}s)\n`);
  out.locations.push({
    id: location.id,
    name: location.name,
    path: location.path,
    accessLevel: location.access_level ?? 'saf',
    root,
    ...(error ? { error } : {}),
    partial: location.access_level === 'owned_only' || location.access_level === 'partial',
  });
}

if (DRY_RUN_FILE) {
  const target = resolve(HERE, '..', DRY_RUN_FILE);
  mkdirSync(dirname(target), { recursive: true });
  writeFileSync(target, `${JSON.stringify(out, null, 2)}\n`);
  console.error(`\nWrote ${target}`);
} else {
  const { createClient } = await import('@supabase/supabase-js');
  const db = createClient(SUPABASE_URL, SERVICE_KEY, {
    auth: { persistSession: false },
    db: { schema: 'device_storage' },
  });

  const { data: snapshotRow, error: snapshotError } = await db
    .from('snapshots')
    .insert({
      captured_at: out.capturedAt,
      device_available_bytes: out.device.availableBytes,
      server_version: out.device.serverVersion,
    })
    .select('id')
    .single();
  if (snapshotError) throw new Error(`snapshots: ${snapshotError.message}`);
  const snapshotId = snapshotRow.id;

  // Locations first: usage_nodes carries a composite foreign key to them, so inserting nodes for a
  // location that does not exist yet fails the whole capture rather than storing a partial tree.
  const { error: locationsError } = await db.from('locations').insert(
    out.locations.map((l) => ({
      snapshot_id: snapshotId,
      location_id: l.id,
      name: l.name,
      path: l.path,
      access_level: l.accessLevel,
      partial: l.partial,
      error: l.error ?? null,
    })),
  );
  if (locationsError) throw new Error(`locations: ${locationsError.message}`);

  const rows = [];
  for (const location of out.locations) {
    const walk = (node, parentPath, depth) => {
      rows.push({
        snapshot_id: snapshotId,
        location_id: location.id,
        path: node.path,
        parent_path: parentPath,
        depth,
        total_bytes: node.totalBytes,
        file_count: node.fileCount,
      });
      for (const child of node.children) walk(child, node.path, depth + 1);
    };
    if (location.root) walk(location.root, null, 0);
  }

  // Chunked: a deep tree runs to thousands of rows and one oversized request fails the capture
  // after the device work is already done.
  for (let i = 0; i < rows.length; i += 500) {
    const { error } = await db.from('usage_nodes').insert(rows.slice(i, i + 500));
    if (error) throw new Error(`usage_nodes: ${error.message}`);
  }
  console.error(`\nStored snapshot ${snapshotId}: ${rows.length} nodes`);
}

const bytes = out.locations.reduce((s, l) => s + (l.root?.totalBytes ?? 0), 0);
console.error(`${out.locations.length} locations, ${(bytes / 1e9).toFixed(2)} GB`);
