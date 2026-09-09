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
const OUT = resolve(HERE, '..', 'src', 'data', 'snapshot.json');

const URL_ = process.env.MCP_URL;
const TOKEN = process.env.MCP_TOKEN;
const DEPTH = Number(process.env.MCP_DEPTH ?? 2);

if (!URL_ || !TOKEN) {
  console.error('Set MCP_URL and MCP_TOKEN.');
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

mkdirSync(dirname(OUT), { recursive: true });
writeFileSync(OUT, `${JSON.stringify(out, null, 2)}\n`);
const bytes = out.locations.reduce((s, l) => s + (l.root?.totalBytes ?? 0), 0);
console.error(`\nWrote ${OUT}: ${out.locations.length} locations, ${(bytes / 1e9).toFixed(2)} GB`);
