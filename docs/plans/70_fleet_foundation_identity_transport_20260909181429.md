<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Fleet foundation: device identity, pairing, outbound transport, content hashing

Supersedes the foundation half of plan 69, whose review findings are recorded in that document.
Scope here is deliberately the layer that requires an APK rebuild and that carries the security
surface: device identity, pairing, the outbound job queue, and content hashing. Ingest, the panel
screens, the APPROVE inbox and the scheduler follow in plan 71, written against this layer once it
runs.

Contract source of truth: `steeveriano/gestion-data` — `HANDOFF-PANEL.md`, `POLITICA-MEDIOS.md`,
`policy.yaml`, `schemas/*.json` (`policy_version: 1.1`, `engine_version: 0.1.0`). Task 0.1 vendors
the parts this repository must not drift from.

## Contract invariants binding on this plan

| Invariant | Consequence here |
|---|---|
| `media_id = sha256(bytes)`, lowercase hex, 64 chars | `fleet.media.media_id` carries `check (media_id ~ '^[a-f0-9]{64}$')`. Paths are per-device attributes. |
| The **PC manifest** never crosses the cable | No endpoint here accepts PC manifest rows. The **mobile media index** does cross, in server-capped batches — stated plainly because plan 69 claimed otherwise while doing exactly this. |
| `safe_to_purge_staging` / `safe_to_release_mobile` are the engine's verdicts | Not present in this plan at all. Nothing here reads, writes or approximates them. |
| `PC → móvil` never automatic | No job kind in this plan writes to a device. There is no `destructive` column and no approval machinery: destructive work cannot be represented until plan 71 adds approvals. |
| Every engine record carries `policy_version` and `engine_version` | `fleet.media` records both on first sight, so a policy change cannot silently reinterpret stored rows. |

## Corrections carried from plan 69's review

`for update skip locked` follows `limit` (C11). Pairing codes come from `gen_random_bytes`, not
`random()` (C14). A code minted before revocation cannot resurrect a revoked device (C15).
`fleet_complete_job` caps its payload server-side (C16). There is no half-built destructive gate to
bypass (C17). `getAllLocations()`, not `listLocations()` (C8). A streaming read API is added rather
than assumed (C9). `FleetIdentity` carries the publishable key (C10). WorkManager is already a
dependency and is not re-added (W12). The hash index does not live in the settings DataStore (C20).
Job kinds are constrained by the schema, not by application code (W24). Leases outlast job budgets
and completion is fenced (W21, W33). Tests use JUnit 5 `@TempDir` (W10).

## Threat model

The device holds a long-lived credential and calls Supabase with no user session.

- Device tokens are 32 random bytes, shown once at pairing, stored only as a sha256 digest.
- Device-facing RPCs are `security definer`, resolve the device by token digest, and are granted to
  `anon`; they are the only reachable surface. What protects the tables is `revoke all on schema`
  plus the schemas being absent from PostgREST's exposed set — **not** RLS. RLS is enabled and
  forced as defence in depth, which plan 69 misstated as the primary control (W42).
- Pairing codes are single-use, 10-minute TTL, minted from a CSPRNG, and bound to the minting viewer.
- Revoking a device also consumes its outstanding codes.

---

## User story 0 — Migration tooling and the vendored contract

**Why**: plan 69 wrote migrations `0002`–`0006` into a `supabase/` directory that does not exist, and
bound every task to a contract that lived in another repository. Neither the implementer nor
`code-reviewer` could verify anything. Both gaps close before any schema work.

**Acceptance criteria**

- [ ] `supabase/migrations/` exists with `0001` reflecting the schema already live.
- [ ] `docs/TOOLS.md` states the exact command that applies a migration.
- [ ] The engine contract and its four schemas are vendored under `docs/contract/` at a recorded
      commit, so plan compliance is checkable inside this repository.

### Task 0.1 — Vendor the contract

**Action 0.1.1** — create `docs/contract/README.md`

```markdown
# Engine ↔ panel contract (vendored)

Upstream: `steeveriano/gestion-data`, commit `3e6e06de4d1ded2522600e62e8e156bcecd732b0`.
`policy_version: 1.1` · `engine_version: 0.1.0`.

These files are a **copy**. The engine repository is authoritative. Refresh with the commit recorded
above updated; never edit them here.

| File | Purpose |
|---|---|
| `HANDOFF-PANEL.md` | The division of responsibility and what crosses the cable |
| `policy.yaml` | Sites, units, assets, confidence weights, video profiles. Data, not code. |
| `manifest.schema.json` | Permanent per-file state, local to the engine |
| `plan.schema.json` | The immutable artefact that gets approved, by hash |
| `verification.schema.json` | The verdict that authorises deletion |
| `execution.schema.json` | What actually happened, and how to revert it |

**Golden rule, quoted:** if the panel needs to recompute something the engine already decided, the
design is wrong.
```

**Action 0.1.2** — copy from the upstream clone into `docs/contract/`:
`HANDOFF-PANEL.md`, `policy.yaml`, `schemas/manifest.schema.json`, `schemas/plan.schema.json`,
`schemas/verification.schema.json`, `schemas/execution.schema.json`.

Do **not** copy `POLITICA-MEDIOS.md` verbatim; reference it by upstream path. It is prose about the
engine's own operation and would go stale here.

**Definition of Done**

- [ ] `docs/contract/` contains the six files and the README naming the upstream commit.
- [ ] No file under `docs/contract/` is referenced by application code — documentation only.

### Task 0.2 — Migration tooling

**Action 0.2.1** — create `supabase/config.toml`

```toml
project_id = "fqrrcoupmwssqbatgxrs"

[db]
major_version = 17
```

**Action 0.2.2** — create `supabase/migrations/0001_device_storage.sql`

Backfill of the schema already applied out of band: schema `device_storage`, tables `viewers`
(email primary key, lowercase check), `snapshots`, `locations`, `usage_nodes`, RLS enabled with no
policies, and `public.device_storage_latest_snapshot()`. Guard every statement with
`if not exists` / `create or replace` so re-applying it against the live project is a no-op.

**Action 0.2.3** — modify `docs/TOOLS.md`: add a "Database migrations" section stating that
migrations are applied with the Supabase MCP `apply_migration` tool (one file per call, in numeric
order), that files are immutable once applied, and that a correction is a new file rather than an
edit.

**Definition of Done**

- [ ] Re-applying `0001` against the live project changes nothing and raises no error.
- [ ] `docs/TOOLS.md` names the apply mechanism.

---

## User story 1 — Device identity and pairing

**Why**: the engine's `device` field is `PC|ANDROID|IOS` and cannot tell two phones apart. The
cross-device join, per-device jobs and per-device opportunities all need a stable id the engine
emits and the panel resolves to a human name. Pairing is assigned wholly to the panel by the
contract (§6). The PC pairs as a device like any other, so it is a first-class row in the join.

**Acceptance criteria**

- [ ] A viewer mints a pairing code in the panel and sees it with its expiry.
- [ ] The app redeems that code and persists a device token it never logs.
- [ ] Re-pairing an existing device keeps `device_id` and rotates only the token.
- [ ] Expired, consumed and unknown codes are refused with one indistinguishable message.
- [ ] A revoked device fails its next call, and any code minted for it before revocation is dead.
- [ ] `device.schema.json` documents the shape the engine must emit.

### Task 1.1 — Schema `device_registry`

**Action 1.1.1** — create `supabase/migrations/0002_device_registry.sql`

```sql
create extension if not exists pgcrypto with schema extensions;

create schema if not exists device_registry;
revoke all on schema device_registry from anon, authenticated;

create table device_registry.devices (
  device_id      uuid primary key default gen_random_uuid(),
  owner_email    text not null references device_storage.viewers(email) on delete cascade,
  alias          text not null check (length(alias) between 1 and 64),
  platform       text not null check (platform in ('ANDROID','IOS','PC')),
  app_version    text check (app_version is null or length(app_version) <= 64),
  token_hash     bytea not null,
  capacity_bytes bigint check (capacity_bytes is null or capacity_bytes >= 0),
  free_bytes     bigint check (free_bytes is null or free_bytes >= 0),
  -- Rendered in the panel, so the scheme is constrained at the column rather than trusted.
  live_url       text check (live_url is null or
                             (live_url ~ '^https://[A-Za-z0-9._~:/?#@!$&''()*+,;=%-]{1,500}$')),
  last_seen_at   timestamptz,
  created_at     timestamptz not null default now(),
  revoked_at     timestamptz
);
create unique index devices_token_hash_idx
  on device_registry.devices (token_hash) where revoked_at is null;
create index devices_owner_idx on device_registry.devices (owner_email);

create table device_registry.pairing_codes (
  code        text primary key check (code ~ '^[0-9A-HJKMNP-TV-Z]{8}$'),
  owner_email text not null references device_storage.viewers(email) on delete cascade,
  device_id   uuid references device_registry.devices(device_id) on delete cascade,
  alias       text check (alias is null or length(alias) between 1 and 64),
  created_at  timestamptz not null default now(),
  expires_at  timestamptz not null,
  consumed_at timestamptz
);
create index pairing_codes_owner_idx on device_registry.pairing_codes (owner_email);
create index pairing_codes_expiry_idx on device_registry.pairing_codes (expires_at)
  where consumed_at is null;

alter table device_registry.devices enable row level security;
alter table device_registry.devices force row level security;
alter table device_registry.pairing_codes enable row level security;
alter table device_registry.pairing_codes force row level security;
```

`alias` is stored on the code (plan 69 took the parameter and discarded it — I1), so the panel names
a device before it ever connects.

**Action 1.1.2** — append to `0002_device_registry.sql`: caller identity

```sql
-- Null for anonymous, unconfirmed and non-viewer callers alike. Every user-facing function below
-- turns null into the same generic error, so viewer membership is not enumerable.
create or replace function device_registry.current_viewer_email()
returns text language sql stable security definer set search_path = '' as $$
  select v.email
  from auth.users u
  join device_storage.viewers v on v.email = lower(u.email)
  where u.id = auth.uid() and u.email_confirmed_at is not null;
$$;
```

**Action 1.1.3** — append to `0002_device_registry.sql`: CSPRNG code generation

```sql
-- Crockford base32 minus I, L, O and U: unambiguous read off a screen and typed on a phone.
-- gen_random_bytes, never random(): this value is a step in issuing a device credential, and
-- PostgreSQL's random() is a seedable, observable PRNG.
create or replace function device_registry.new_pairing_code()
returns text language sql volatile security definer set search_path = '' as $$
  select string_agg(
           substr('0123456789ABCDEFGHJKMNPQRSTVWXYZ',
                  1 + (get_byte(extensions.gen_random_bytes(1), 0) % 32), 1), '')
  from generate_series(1, 8);
$$;
```

**Action 1.1.4** — append to `0002_device_registry.sql`: minting

```sql
create or replace function public.fleet_mint_pairing_code(
  p_alias text, p_platform text default 'ANDROID', p_device_id uuid default null)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_email text; v_code text; v_expires timestamptz; v_attempt int := 0;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then raise exception 'not authorised' using errcode = '42501'; end if;
  if p_alias is null or length(trim(p_alias)) not between 1 and 64 then
    raise exception 'alias must be 1..64 characters' using errcode = '22023';
  end if;
  if p_platform not in ('ANDROID','IOS','PC') then
    raise exception 'invalid platform' using errcode = '22023';
  end if;
  if p_device_id is not null and not exists (
       select 1 from device_registry.devices d
       where d.device_id = p_device_id and d.owner_email = v_email and d.revoked_at is null)
  then raise exception 'not authorised' using errcode = '42501'; end if;

  v_expires := now() + interval '10 minutes';
  -- A primary-key collision is a retry, not a 500. Eight base32 characters over a ten-minute
  -- window make it vanishingly rare, but "rare" is not "handled".
  loop
    v_attempt := v_attempt + 1;
    v_code := device_registry.new_pairing_code();
    begin
      insert into device_registry.pairing_codes (code, owner_email, device_id, alias, expires_at)
      values (v_code, v_email, p_device_id, trim(p_alias), v_expires);
      exit;
    exception when unique_violation then
      if v_attempt >= 5 then raise; end if;
    end;
  end loop;

  return jsonb_build_object('code', v_code, 'expiresAt', v_expires, 'alias', trim(p_alias));
end;
$$;
revoke all on function public.fleet_mint_pairing_code(text, text, uuid) from public, anon;
grant execute on function public.fleet_mint_pairing_code(text, text, uuid) to authenticated;
```

**Action 1.1.5** — append to `0002_device_registry.sql`: redemption

```sql
create or replace function public.fleet_claim_pairing_code(
  p_code text, p_platform text, p_app_version text)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_row device_registry.pairing_codes%rowtype; v_token text; v_device_id uuid;
begin
  if p_platform not in ('ANDROID','IOS','PC') then
    raise exception 'invalid or expired code' using errcode = '22023';
  end if;
  if p_app_version is not null and length(p_app_version) > 64 then
    raise exception 'invalid or expired code' using errcode = '22023';
  end if;

  select * into v_row from device_registry.pairing_codes
   where code = upper(trim(coalesce(p_code, ''))) for update;

  -- One message for absent, consumed and expired: the caller is anonymous, so a distinguishable
  -- error would confirm which codes exist.
  if v_row.code is null or v_row.consumed_at is not null or v_row.expires_at < now() then
    raise exception 'invalid or expired code' using errcode = '22023';
  end if;

  v_token := encode(extensions.gen_random_bytes(32), 'hex');

  if v_row.device_id is null then
    insert into device_registry.devices (owner_email, alias, platform, app_version, token_hash)
    values (v_row.owner_email, coalesce(v_row.alias, 'Dispositivo'), p_platform, p_app_version,
            extensions.digest(v_token, 'sha256'))
    returning device_id into v_device_id;
  else
    -- `revoked_at is null` is the whole point: a code minted before a revocation must not
    -- resurrect the device it was minted for.
    update device_registry.devices
       set token_hash = extensions.digest(v_token, 'sha256'),
           alias = coalesce(v_row.alias, alias),
           app_version = p_app_version
     where device_id = v_row.device_id and revoked_at is null
    returning device_id into v_device_id;

    if v_device_id is null then
      update device_registry.pairing_codes set consumed_at = now() where code = v_row.code;
      raise exception 'invalid or expired code' using errcode = '22023';
    end if;
  end if;

  update device_registry.pairing_codes set consumed_at = now() where code = v_row.code;
  return jsonb_build_object('deviceId', v_device_id, 'deviceToken', v_token,
                            'alias', coalesce(v_row.alias, 'Dispositivo'));
end;
$$;
revoke all on function public.fleet_claim_pairing_code(text, text, text) from public;
grant execute on function public.fleet_claim_pairing_code(text, text, text) to anon, authenticated;
```

**Action 1.1.6** — append to `0002_device_registry.sql`: token resolution

```sql
create or replace function device_registry.device_for_token(p_token text)
returns uuid language sql stable security definer set search_path = '' as $$
  select d.device_id from device_registry.devices d
   where d.token_hash = extensions.digest(coalesce(p_token, ''), 'sha256')
     and d.revoked_at is null;
$$;
```

Lookup is an index probe rather than a constant-time compare. For a 32-byte random secret with no
oracle over partial matches the timing channel carries nothing useful — recorded here because
PROJECT.md is explicit about constant-time comparison for the bearer token and the difference should
be deliberate, not accidental (I19).

**Action 1.1.7** — append to `0002_device_registry.sql`: heartbeat, listing, revocation

```sql
create or replace function public.fleet_device_heartbeat(
  p_token text, p_capacity_bytes bigint, p_free_bytes bigint, p_live_url text default null)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;
  update device_registry.devices
     set capacity_bytes = p_capacity_bytes,
         free_bytes = p_free_bytes,
         live_url = p_live_url,          -- null clears a stale tunnel URL
         last_seen_at = now()
   where device_id = v_device;
  return jsonb_build_object('deviceId', v_device, 'ok', true);
end;
$$;
revoke all on function public.fleet_device_heartbeat(text, bigint, bigint, text) from public;
grant execute on function public.fleet_device_heartbeat(text, bigint, bigint, text)
  to anon, authenticated;

create or replace function public.fleet_devices()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_email text;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then return null; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
             'deviceId', d.device_id, 'alias', d.alias, 'platform', d.platform,
             'appVersion', d.app_version, 'capacityBytes', d.capacity_bytes,
             'freeBytes', d.free_bytes, 'liveUrl', d.live_url,
             'lastSeenAt', d.last_seen_at, 'createdAt', d.created_at)
           order by d.created_at)
    from device_registry.devices d
    where d.owner_email = v_email and d.revoked_at is null), '[]'::jsonb);
end;
$$;
revoke all on function public.fleet_devices() from public, anon;
grant execute on function public.fleet_devices() to authenticated;

create or replace function public.fleet_revoke_device(p_device_id uuid)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare v_email text; v_found boolean;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then raise exception 'not authorised' using errcode = '42501'; end if;

  update device_registry.devices set revoked_at = now()
   where device_id = p_device_id and owner_email = v_email and revoked_at is null;
  v_found := found;

  -- Kill outstanding codes too: revocation that leaves a redeemable code is not a revocation.
  update device_registry.pairing_codes set consumed_at = now()
   where device_id = p_device_id and consumed_at is null;

  return v_found;
end;
$$;
revoke all on function public.fleet_revoke_device(uuid) from public, anon;
grant execute on function public.fleet_revoke_device(uuid) to authenticated;
```

**Action 1.1.8** — append to `0002_device_registry.sql`: expiry sweep

```sql
create or replace function public.fleet_sweep_pairing_codes()
returns integer language plpgsql volatile security definer set search_path = '' as $$
declare v_deleted integer;
begin
  delete from device_registry.pairing_codes
   where expires_at < now() - interval '1 day';
  get diagnostics v_deleted = row_count;
  return v_deleted;
end;
$$;
revoke all on function public.fleet_sweep_pairing_codes() from public, anon;
grant execute on function public.fleet_sweep_pairing_codes() to authenticated;
```

Consumed and expired codes would otherwise accumulate forever under an 8-character primary key,
shrinking the usable space and leaking minting history (W44).

**Definition of Done**

- [ ] Migration applies once, cleanly, against the live project.
- [ ] `fleet_devices()` returns `null` for anonymous and for an authenticated non-viewer, verified
      with `set local role authenticated` + `set local request.jwt.claims`. **Manual QA Step**;
      automated coverage is Task 1.7.
- [ ] Expired, consumed and unknown codes all raise the identical message.
- [ ] Re-pairing preserves `device_id`; re-pairing a revoked device is refused and burns the code.
- [ ] `fleet_revoke_device` consumes the device's outstanding codes.
- [ ] A `live_url` of `javascript:alert(1)` is rejected by the column constraint.

### Task 1.2 — `device.schema.json`

**Action 1.2.1** — create `docs/schemas/device.schema.json`

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "device.schema.json",
  "title": "Device — identidad persistente de un dispositivo emparejado",
  "description": "El motor emite device_id; el panel lo resuelve a un nombre humano. Se une a manifest.device, que solo distingue plataforma.",
  "type": "object",
  "required": ["device_id", "alias", "platform"],
  "additionalProperties": false,
  "properties": {
    "device_id": { "type": "string", "format": "uuid" },
    "alias": { "type": "string", "minLength": 1, "maxLength": 64 },
    "platform": { "enum": ["ANDROID", "IOS", "PC"] },
    "app_version": { "type": ["string", "null"], "maxLength": 64 },
    "capacity_bytes": { "type": ["integer", "null"], "minimum": 0 },
    "free_bytes": { "type": ["integer", "null"], "minimum": 0 },
    "live_url": { "type": ["string", "null"], "pattern": "^https://" },
    "last_seen_at": { "type": ["string", "null"], "format": "date-time" },
    "created_at": { "type": "string", "format": "date-time" }
  }
}
```

**Definition of Done**

- [ ] Validates a representative device and rejects an unknown platform, a 65-character alias and a
      non-`https` `live_url`.

### Task 1.3 — App: identity model and repository

**Action 1.3.1** — create
`app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/FleetIdentity.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val fleetIdentityJson = Json { ignoreUnknownKeys = true }

/**
 * Pairing state for the fleet panel.
 *
 * [deviceToken] authenticates every outbound call and is never logged, rendered, or included in an
 * error message. [publishableKey] is the panel's Supabase publishable key: it is inlined in the
 * panel's own bundle and is not a secret, but PostgREST rejects a request without it, so the device
 * must persist it alongside the URL.
 */
@Serializable
data class FleetIdentity(
    val deviceId: String = "",
    val deviceToken: String = "",
    val panelUrl: String = "",
    val publishableKey: String = "",
    val alias: String = "",
    val pairedAtMillis: Long = 0L,
) {
    val paired: Boolean
        get() = deviceId.isNotBlank() &&
            deviceToken.isNotBlank() &&
            panelUrl.isNotBlank() &&
            publishableKey.isNotBlank()

    companion object {
        fun fromJsonOrDefault(json: String): FleetIdentity =
            try {
                fleetIdentityJson.decodeFromString(serializer(), json)
            } catch (
                @Suppress("TooGenericExceptionCaught") _: Exception,
            ) {
                FleetIdentity()
            }
    }

    fun toJson(): String = fleetIdentityJson.encodeToString(serializer(), this)
}
```

**Action 1.3.2** — create `.../data/repository/FleetIdentityRepository.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.data.model.FleetIdentity
import kotlinx.coroutines.flow.Flow

/** Persists the device's pairing state. The stored token is a credential: never log it. */
interface FleetIdentityRepository {
    val identity: Flow<FleetIdentity>

    suspend fun get(): FleetIdentity

    suspend fun save(identity: FleetIdentity)

    suspend fun clear()
}
```

**Action 1.3.3** — create `.../data/repository/FleetIdentityRepositoryImpl.kt`

Modelled on `EventChannelSettingsImpl`. Single `stringPreferencesKey("fleet_identity")` in the
DataStore provided by `AppModule.provideDataStore`. `identity` maps the stored string through
`FleetIdentity.fromJsonOrDefault`; `save` writes `toJson()`; `clear` removes the key.

The `SettingsChangeLogger.submit` call passes a `render` lambda emitting only
`deviceId` and `paired` — the whole point of that lambda is that the value never reaches the log
(I16). It must not stringify the `FleetIdentity`.

**Action 1.3.4** — modify `.../di/AppModule.kt`: add, in the existing `@Binds` block,

```kotlin
@Binds
@Singleton
abstract fun bindFleetIdentityRepository(impl: FleetIdentityRepositoryImpl): FleetIdentityRepository
```

**Definition of Done**

- [ ] Pairing survives an app restart; `clear()` returns the device to unpaired.
- [ ] `grep -r "deviceToken" app/src/main` finds no call site passing it to `Logger` or to
      `ServerLogRepository`.

### Task 1.4 — App: build configuration for the panel defaults

**Action 1.4.1** — modify `app/build.gradle.kts`: inside `defaultConfig`,

```kotlin
buildConfigField(
    "String",
    "FLEET_PANEL_URL",
    "\"${project.findProperty("FLEET_PANEL_URL") ?: "https://fqrrcoupmwssqbatgxrs.supabase.co"}\"",
)
buildConfigField(
    "String",
    "FLEET_PUBLISHABLE_KEY",
    "\"${project.findProperty("FLEET_PUBLISHABLE_KEY") ?: ""}\"",
)
```

**Action 1.4.2** — modify `gradle.properties`: add `FLEET_PANEL_URL` with the project URL and
`FLEET_PUBLISHABLE_KEY` with the publishable key, plus a comment stating that both are publishable
by design — the same two values the panel's own bundle already carries — and that the service-role
key must never appear here.

A blank default is deliberate: a build without the property produces an app that cannot pair rather
than one that silently points somewhere unintended.

**Definition of Done**

- [ ] `./gradlew :app:assembleDebug` produces `BuildConfig.FLEET_PANEL_URL` and
      `BuildConfig.FLEET_PUBLISHABLE_KEY`.
- [ ] Neither value is the service-role key, verified by asserting the stored key does not start
      with `sb_secret_` or decode as a JWT with `"role":"service_role"`.

### Task 1.5 — App: the fleet client

**Action 1.5.1** — create `.../services/fleet/FleetClient.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.fleet

import com.danielealbano.androidremotecontrolmcp.data.model.FleetIdentity
import kotlinx.serialization.json.JsonObject

/**
 * The device's outbound calls to the panel. Every method is a PostgREST RPC POST carrying the
 * publishable key as `apikey`; the device token travels in the body, never in a header, so it is
 * not recorded by intermediaries that log headers.
 */
interface FleetClient {
    /** Redeems a pairing code. The only call that does not need an existing identity. */
    suspend fun claimPairingCode(
        panelUrl: String,
        publishableKey: String,
        code: String,
        appVersion: String,
    ): Result<FleetIdentity>

    suspend fun heartbeat(
        capacityBytes: Long,
        freeBytes: Long,
        liveUrl: String?,
    ): Result<Unit>

    /** Returns null when the queue is empty. */
    suspend fun claimJob(leaseSeconds: Int): Result<FleetJob?>

    suspend fun completeJob(
        jobId: String,
        attempt: Int,
        ok: Boolean,
        result: JsonObject?,
        error: String?,
    ): Result<Unit>

    suspend fun reportMedia(rows: List<HashedFile>): Result<Int>
}
```

**Action 1.5.2** — create `.../services/fleet/FleetClientImpl.kt`

Constructor takes the Ktor `HttpClient` already provided by Hilt and `FleetIdentityRepository`.
A private `rpc(fn: String, body: JsonObject): Result<JsonElement>` posts to
`"$panelUrl/rest/v1/rpc/$fn"` with `apikey` and `Content-Type: application/json`, reading the URL
and key from the stored identity (or from the parameters, for `claimPairingCode`).

Rules the implementation must honour:

- Every method except `claimPairingCode` returns `Result.failure` immediately when
  `identity.paired` is false, without an HTTP call.
- The code is uppercased and stripped of hyphens and whitespace before it is sent.
- On a non-2xx response the server's `message` field becomes the failure message; the response body
  is otherwise discarded.
- No failure message, log line or exception may contain `deviceToken` or `publishableKey`. The
  helper builds messages from the server's text only, never by interpolating the request body.
- `panelUrl` is rejected unless it starts with `https://` and parses as an absolute URL (W40).

**Action 1.5.3** — modify `.../di/AppModule.kt`: add `@Binds` for `FleetClient`.

**Definition of Done**

- [ ] An unpaired device makes no HTTP call from any method but `claimPairingCode`.
- [ ] A malformed or non-`https` panel URL fails before any request is sent.
- [ ] `claimPairingCode` sends `ABCD1234` for the input `abcd-1234 `.

### Task 1.6 — App: pairing UI

**Action 1.6.1** — modify `app/src/main/res/values/strings.xml` and
`app/src/main/res/values-es/strings.xml`: add `fleet_settings_title`, `fleet_panel_url_label`,
`fleet_code_label`, `fleet_alias_label`, `fleet_pair_action`, `fleet_unpair_action`,
`fleet_unpair_confirm_title`, `fleet_unpair_confirm_message`, `fleet_paired_since`,
`fleet_last_sync`, `fleet_code_help`, `fleet_panel_url_help`, `fleet_error_invalid_url`.

Spanish strings go in `values-es/`; English in `values/`. No literal user-facing text in a
composable (W19).

**Action 1.6.2** — create `.../ui/screens/settings/FleetSettingsScreen.kt`

Stateless composable:

```kotlin
@Composable
fun FleetSettingsScreen(
    state: FleetSettingsUiState,
    onPair: (panelUrl: String, code: String) -> Unit,
    onUnpair: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateBack: () -> Unit,
)
```

State hoisted entirely; the screen holds only field text in `rememberSaveable` (W20). Unpaired
state shows the panel URL field (prefilled from `BuildConfig.FLEET_PANEL_URL`), the code field
(uppercased, displayed grouped as `XXXX-XXXX`, submit enabled at 8 valid characters) and a pair
button. Paired state shows alias, truncated device id, paired-at, last sync, and unpair behind a
confirmation dialog. Uses `SettingsSection`, `SettingsRow` and `HelpHint` from
`ui/components/`; 48dp targets and `contentDescription` throughout.

**Action 1.6.3** — create `.../ui/viewmodels/FleetSettingsViewModel.kt`

`@HiltViewModel`, exposing `StateFlow<FleetSettingsUiState>` built from
`FleetIdentityRepository.identity` plus local `busy` and `error`. `pair` calls
`FleetClient.claimPairingCode`, then `FleetIdentityRepository.save`, then
`FleetSyncScheduler.schedule()`. `unpair` calls `FleetSyncScheduler.cancel()` then
`FleetIdentityRepository.clear()` — scheduling is driven from here, not from `Application` (W17).

**Action 1.6.4** — modify `.../ui/navigation/Routes.kt` and
`.../ui/screens/settings/SettingsIndexScreen.kt`: add the fleet route and its index entry.

**Definition of Done**

- [ ] A valid code moves the screen to the paired state without restarting the app.
- [ ] An invalid code shows the server's message and leaves the device unpaired.
- [ ] No user-facing literal appears in the composable; both locales resolve.
- [ ] Dark mode renders correctly; every control is at least 48dp.

### Task 1.7 — Tests for user story 1

**File**: `app/src/test/kotlin/.../data/repository/FleetIdentityRepositoryImplTest.kt`

**Setup**: JUnit 5 `@TempDir`; `PreferenceDataStoreFactory.create { tempDir.resolve("t.preferences_pb") }`;
`repo = FleetIdentityRepositoryImpl(dataStore, changeLogger)` with a relaxed MockK logger.

| Test | Verifies |
|---|---|
| `get returns unpaired identity when nothing is stored` | Default; `paired == false` |
| `save then get round-trips every field` | All six fields persist |
| `paired is false when publishableKey is blank` | The key is part of the readiness predicate |
| `clear returns the device to unpaired` | Key removed |
| `malformed stored json falls back to the default` | **Setup**: write `"{"` to the key directly |
| `identity flow emits once per save` | Turbine |
| `change logger never receives the device token` | Capture the `render` lambda's output and assert the token substring is absent |

**File**: `app/src/test/kotlin/.../services/fleet/FleetClientImplTest.kt`

**Setup**: Ktor `MockEngine` with a per-test response queue; `client = FleetClientImpl(httpClient, repo)`.

| Test | Verifies |
|---|---|
| `claimPairingCode posts to the rpc path with the apikey header` | URL and header |
| `claimPairingCode normalises the code` | Body carries `ABCD1234` for `" abcd-1234 "` |
| `claimPairingCode rejects a non-https panel url before any request` | `MockEngine` records zero calls |
| `claimPairingCode maps a 400 to the server message` | Failure text from `message` |
| `claimPairingCode returns an identity carrying the publishable key` | The key passed in is persisted in the result |
| `heartbeat fails fast when unpaired` | Zero calls |
| `heartbeat sends a null live url when none is supplied` | Body has `p_live_url: null` |
| `claimJob returns null when the rpc returns null` | Empty queue is not an error |
| `completeJob sends the fencing attempt` | Body carries `p_attempt` |
| `no failure message contains the device token` | Assert absence across every failure path |

**File**: `app/src/test/kotlin/.../ui/viewmodels/FleetSettingsViewModelTest.kt`

**Setup**: `Dispatchers.setMain(StandardTestDispatcher())` in `@BeforeEach` and `resetMain()` in
`@AfterEach`, matching `MainViewModelTest` (W9). MockK `FleetClient`, `FleetIdentityRepository`,
`FleetSyncScheduler`.

| Test | Verifies |
|---|---|
| `pair saves the identity and schedules sync` | `save` then `schedule` in order |
| `pair surfaces the error and saves nothing on failure` | `save` never called |
| `unpair cancels sync before clearing` | Order matters: a cancelled worker cannot race a cleared token |
| `busy is true while pairing and false afterwards` | Turbine over the state flow |

**File**: `web/src/fleet/api.test.ts` (Vitest)

| Test | Verifies |
|---|---|
| `mintPairingCode maps the rpc payload` | `code` and `expiresAt` |
| `mintPairingCode propagates an rpc error` | Rejection |
| `devices maps the rpc payload to typed objects` | Field mapping |
| `devices returns an empty array when the rpc returns null` | Non-viewer becomes `[]`, never a crash |
| `revokeDevice returns false when nothing was revoked` | Idempotent revoke |

**Definition of Done**

- [ ] Tests are written and self-consistent. Execution is Task 5.2.

---

## User story 2 — Outbound job queue

**Why**: a phone behind NAT, asleep, or on a dead quick tunnel cannot be dialled into on a schedule.
The queue makes autonomous work possible with no inbound reachability. The tunnel is unchanged and
stays for interactive work; this story only teaches the device to report whether it is up.

**Acceptance criteria**

- [ ] The device claims and completes jobs with the MCP server stopped and no tunnel running.
- [ ] Only read-only kinds exist; the schema itself cannot express a destructive job.
- [ ] A job whose lease expires is reclaimable, and the abandoned run cannot report success over the
      new one.
- [ ] A job that keeps failing terminates instead of retrying forever.
- [ ] Sync runs on unmetered power by default, and the user can override that.

### Task 2.1 — Schema `fleet` (jobs)

**Action 2.1.1** — create `supabase/migrations/0003_fleet_jobs.sql`

```sql
create schema if not exists fleet;
revoke all on schema fleet from anon, authenticated;

create type fleet.job_state as enum ('PENDING','CLAIMED','DONE','FAILED');

create table fleet.jobs (
  job_id      uuid primary key default gen_random_uuid(),
  device_id   uuid not null references device_registry.devices(device_id) on delete cascade,
  -- The allowlist is a constraint, not application code. Nothing destructive is expressible here;
  -- destructive work arrives with the approvals migration in plan 71, which widens this
  -- deliberately and adds the approval binding at the same time.
  kind        text not null check (kind in ('INVENTORY','EXT_HISTOGRAM','HASH_BATCH','STAT_PATH')),
  params      jsonb not null default '{}'::jsonb,
  state       fleet.job_state not null default 'PENDING',
  run_after   timestamptz not null default now(),
  lease_until timestamptz,
  attempts    int not null default 0,
  max_attempts int not null default 3 check (max_attempts between 1 and 10),
  result      jsonb,
  error       text check (error is null or length(error) <= 2000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index jobs_claimable_idx on fleet.jobs (device_id, state, run_after);
create index jobs_lease_idx on fleet.jobs (state, lease_until) where state = 'CLAIMED';

alter table fleet.jobs enable row level security;
alter table fleet.jobs force row level security;
```

**Action 2.1.2** — append to `0003_fleet_jobs.sql`: claim

```sql
create or replace function public.fleet_claim_job(p_token text, p_lease_seconds int default 900)
returns jsonb language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid; v_job fleet.jobs%rowtype;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;
  if p_lease_seconds not between 60 and 3600 then
    raise exception 'invalid lease' using errcode = '22023';
  end if;

  -- An expired claim returns to the pool, or terminates if it has burned its attempts. A device
  -- that dies mid-job never wedges the queue, and a job that kills the device stops being handed
  -- out.
  update fleet.jobs
     set state = case when attempts >= max_attempts then 'FAILED'::fleet.job_state
                      else 'PENDING'::fleet.job_state end,
         error = case when attempts >= max_attempts
                      then 'lease expired after max attempts' else error end,
         lease_until = null, updated_at = now()
   where device_id = v_device and state = 'CLAIMED' and lease_until < now();

  select * into v_job from fleet.jobs
   where device_id = v_device and state = 'PENDING' and run_after <= now()
   order by run_after
   limit 1
   for update skip locked;

  if v_job.job_id is null then return null; end if;

  update fleet.jobs
     set state = 'CLAIMED',
         lease_until = now() + make_interval(secs => p_lease_seconds),
         attempts = attempts + 1,
         updated_at = now()
   where job_id = v_job.job_id;

  -- `attempt` is a fencing token: an abandoned run holds a stale value and cannot complete over
  -- the run that replaced it.
  return jsonb_build_object('jobId', v_job.job_id, 'kind', v_job.kind,
                            'params', v_job.params, 'attempt', v_job.attempts + 1);
end;
$$;
revoke all on function public.fleet_claim_job(text, int) from public;
grant execute on function public.fleet_claim_job(text, int) to anon, authenticated;
```

**Action 2.1.3** — append to `0003_fleet_jobs.sql`: completion

```sql
create or replace function public.fleet_complete_job(
  p_token text, p_job_id uuid, p_attempt int, p_ok boolean, p_result jsonb, p_error text)
returns boolean language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;

  -- The client caps its payload too, but the client is whoever holds a device token. The cap that
  -- matters is this one.
  if p_result is not null and pg_column_size(p_result) > 262144 then
    raise exception 'result too large' using errcode = '22023';
  end if;

  update fleet.jobs
     set state = case when p_ok then 'DONE'::fleet.job_state else 'FAILED'::fleet.job_state end,
         result = p_result,
         error = left(p_error, 2000),
         lease_until = null,
         updated_at = now()
   where job_id = p_job_id
     and device_id = v_device
     and state = 'CLAIMED'
     and attempts = p_attempt;      -- fencing
  return found;
end;
$$;
revoke all on function public.fleet_complete_job(text, uuid, int, boolean, jsonb, text) from public;
grant execute on function public.fleet_complete_job(text, uuid, int, boolean, jsonb, text)
  to anon, authenticated;
```

**Action 2.1.4** — append to `0003_fleet_jobs.sql`: enqueue and read-back

```sql
create or replace function public.fleet_enqueue_job(
  p_device_id uuid, p_kind text, p_params jsonb default '{}'::jsonb,
  p_run_after timestamptz default now())
returns uuid language plpgsql volatile security definer set search_path = '' as $$
declare v_email text; v_job uuid;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then raise exception 'not authorised' using errcode = '42501'; end if;
  if not exists (select 1 from device_registry.devices d
                  where d.device_id = p_device_id and d.owner_email = v_email
                    and d.revoked_at is null)
  then raise exception 'not authorised' using errcode = '42501'; end if;
  -- The kind allowlist lives on the table; an invalid kind fails the check constraint here.
  insert into fleet.jobs (device_id, kind, params, run_after)
  values (p_device_id, p_kind, p_params, p_run_after)
  returning job_id into v_job;
  return v_job;
end;
$$;
revoke all on function public.fleet_enqueue_job(uuid, text, jsonb, timestamptz) from public, anon;
grant execute on function public.fleet_enqueue_job(uuid, text, jsonb, timestamptz) to authenticated;

create or replace function public.fleet_jobs(p_device_id uuid, p_limit int default 50)
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_email text;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then return null; end if;
  return coalesce((
    select jsonb_agg(jsonb_build_object(
             'jobId', j.job_id, 'kind', j.kind, 'state', j.state, 'attempts', j.attempts,
             'runAfter', j.run_after, 'updatedAt', j.updated_at,
             'error', j.error) order by j.created_at desc)
    from (select * from fleet.jobs j2
           join device_registry.devices d on d.device_id = j2.device_id
          where j2.device_id = p_device_id and d.owner_email = v_email
          order by j2.created_at desc
          limit least(greatest(p_limit, 1), 200)) j), '[]'::jsonb);
end;
$$;
revoke all on function public.fleet_jobs(uuid, int) from public, anon;
grant execute on function public.fleet_jobs(uuid, int) to authenticated;
```

`fleet_jobs` exists because plan 69 built six screens over a single read RPC (C6). Every table this
plan creates gets its read path in the same migration that creates it.

**Definition of Done**

- [ ] `insert into fleet.jobs (kind) values ('MOVE_FILE')` is rejected by the check constraint.
- [ ] `fleet_claim_job` never returns another device's job and returns null on an empty queue.
- [ ] An expired lease returns the job to `PENDING`; once `attempts >= max_attempts` it goes
      `FAILED` instead.
- [ ] A completion carrying a stale `p_attempt` returns false and changes nothing.
- [ ] A 300 KB `p_result` is rejected.

### Task 2.2 — App: streaming reads

**Why**: `FileOperationProvider` exposes only `readFileBytes` (a whole `ByteArray`, bounded by the
configured file-size limit) and a line-paginated `readFile`. Hashing a 2 GB video needs neither.
`FileOperationProviderImpl` already streams internally via
`context.contentResolver.openInputStream(...)` in its verified-copy path, so this exposes the
mechanism that is already there rather than inventing one.

**Action 2.2.1** — modify `.../services/storage/FileOperationProvider.kt`: add to
`DirectoryOperationProvider`

```kotlin
/**
 * Opens [path] for streaming and hands the stream to [block].
 *
 * Deliberately exempt from the configured file-size limit: that limit bounds what an MCP caller can
 * pull into a response, and nothing here crosses the wire — the stream is consumed on-device and
 * only its digest leaves. The stream is closed before this returns, so [block] must not retain it.
 */
suspend fun <T> readStreaming(
    locationId: String,
    path: String,
    block: suspend (java.io.InputStream) -> T,
): T
```

**Action 2.2.2** — modify `.../services/storage/FileOperationProviderImpl.kt`: implement it by
resolving the document URI the way `statPath` already does, then

```kotlin
context.contentResolver.openInputStream(uri)?.use { block(it) }
    ?: throw McpToolException.ActionFailed("Could not open $path for reading")
```

on `Dispatchers.IO`.

**Action 2.2.3** — modify `.../services/storage/MediaStoreFileOperations.kt` and
`MediaStoreFileOperationsImpl.kt`: mirror the addition so both backends satisfy the interface.

**Definition of Done**

- [ ] Reading a 64 MB file through `readStreaming` never materialises it as a `ByteArray`.
- [ ] The stream is closed when `block` returns and when it throws.
- [ ] The configured file-size limit does not apply.

### Task 2.3 — App: content hashing

**Why**: `media_id = sha256(bytes)` is the contract's only cross-device identity. This device holds
90 957 files across ~90 GB, so a single pass is hours of flash reads and a flat battery. Hashing is
incremental, resumable, budgeted, and ordered so the media that exists nowhere else surfaces first.

**Action 2.3.1** — create `.../services/fleet/HashedFile.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.fleet

import kotlinx.serialization.Serializable

/**
 * One hashed file. [mediaId] is the lowercase hex sha256 of the bytes — the contract's join key.
 * [lastModified] is null when the provider does not report one; such a file is re-hashed on every
 * pass rather than assumed unchanged.
 */
@Serializable
data class HashedFile(
    val locationId: String,
    val path: String,
    val mediaId: String,
    val sizeBytes: Long,
    val lastModified: Long?,
)

data class HashBatchResult(val hashed: List<HashedFile>, val exhausted: Boolean, val scanned: Int)
```

**Action 2.3.2** — create `.../services/fleet/HashIndexStore.kt` and `HashIndexStoreImpl.kt`

An append-only index in `context.filesDir/fleet/hash-index/<locationId>.idx`, one
tab-separated record per line: `path`, `mediaId`, `sizeBytes`, `lastModified`. Loaded into an
in-memory `HashMap` on first use per location; appended on each batch; compacted (rewritten without
superseded records) when the file exceeds twice the live record count.

```kotlin
interface HashIndexStore {
    suspend fun fingerprint(locationId: String, path: String): HashedFile?
    suspend fun append(rows: List<HashedFile>)
    suspend fun clear(locationId: String)
    suspend fun count(locationId: String): Int
}
```

**Not DataStore.** Plan 69 put a ~91k-entry index in the shared settings Preferences DataStore,
which rewrites its entire file and holds the whole snapshot in memory on every commit — roughly
14 MB rewritten after each 500-file batch, alongside the app's ~386 settings (C20). CLAUDE.md §6
scopes DataStore to settings and says there is no complex relational data; an append-only file
honours that without adding Room. **If Room is preferred, it is an architecture change and needs
explicit approval before this task is implemented.**

**Action 2.3.3** — create `.../services/fleet/MediaHasher.kt`

```kotlin
interface MediaHasher {
    /**
     * Hashes not-yet-hashed files under [path], stopping at [maxFiles] or [budgetMillis],
     * whichever comes first. A file whose size and timestamp match the stored fingerprint is
     * skipped without being read.
     */
    suspend fun hashBatch(
        locationId: String,
        path: String,
        maxFiles: Int,
        budgetMillis: Long,
    ): HashBatchResult
}
```

**Action 2.3.4** — create `.../services/fleet/MediaHasherImpl.kt`

Injects `FileOperationProvider`, `HashIndexStore` and `@IoDispatcher CoroutineDispatcher` (the
existing qualifier from `AppModule.provideIoDispatcher`) — the whole body runs on `Dispatchers.IO`
(W18). Walks with `listFiles(locationId, path, offset, limit)` page by page; for each candidate,
compares against `HashIndexStore.fingerprint`; on a miss, streams through
`MessageDigest.getInstance("SHA-256")` in 64 KB chunks via `readStreaming`, formatting the digest
as lowercase hex. Checks the deadline and `maxFiles` between files, never mid-file.

**Action 2.3.5** — create `.../services/fleet/HashPriority.kt`

```kotlin
/**
 * The order hashing walks the device. `SOLO_MOVIL` — media whose sha256 exists on no other device —
 * is the only class with no copy anywhere, and these are the locations where it lives. Plan 71's
 * default programme consumes this list; it is defined here, once, as data.
 */
val FLEET_HASH_PRIORITY: List<Pair<String, String>> = listOf(
    "builtin:dcim" to "",
    "com.android.externalstorage.documents/primary:Android/media" to "com.whatsapp",
    "com.android.externalstorage.documents/primary:Android/media" to "com.whatsapp.w4b",
    "com.android.externalstorage.documents/primary:storage-0" to "",
    "builtin:pictures" to "",
    "builtin:movies" to "",
    "builtin:downloads" to "",
)
```

**Definition of Done**

- [ ] The digest of a known file matches `sha256sum`.
- [ ] A second pass over unchanged files performs no reads and reports `exhausted = true`.
- [ ] A file with a null `lastModified` is re-hashed rather than treated as unchanged.
- [ ] A 64 MB file hashes without an `OutOfMemoryError`.
- [ ] `maxFiles` and `budgetMillis` both bound the batch.
- [ ] The index survives a process restart and compacts instead of being dropped.

### Task 2.4 — App: job kinds and the runner

**Action 2.4.1** — create `.../services/fleet/FleetJob.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.fleet

import kotlinx.serialization.json.JsonObject

/**
 * Work the panel can ask this device to do without a live connection. Every kind is read-only:
 * this queue cannot mutate the device. Destructive work travels the approval path and is not
 * representable here or in the database.
 */
enum class FleetJobKind { INVENTORY, EXT_HISTOGRAM, HASH_BATCH, STAT_PATH }

data class FleetJob(
    val jobId: String,
    val kind: String,
    val attempt: Int,
    val params: JsonObject,
) {
    /** Null for a kind this build does not know: an older app must fail the job, not the queue. */
    val knownKind: FleetJobKind? get() = FleetJobKind.entries.firstOrNull { it.name == kind }
}
```

**Action 2.4.2** — create `.../services/fleet/ExtensionHistogram.kt` and `ExtensionHistogramImpl.kt`

No such walker exists in the app; plan 69 called it existing (C8). It is created here.

```kotlin
data class ExtensionTally(val count: Int, val bytes: Long)

interface ExtensionHistogram {
    /** Tallies file count and bytes by lowercase extension under [path]. `""` keys extensionless files. */
    suspend fun tally(
        locationId: String,
        path: String,
        maxFiles: Int,
    ): Map<String, ExtensionTally>
}
```

Implemented over `listFiles` paging, on `Dispatchers.IO`, bounded by `maxFiles`.

**Action 2.4.3** — create `.../services/fleet/FleetJobRunner.kt` and `FleetJobRunnerImpl.kt`

```kotlin
interface FleetJobRunner {
    suspend fun run(job: FleetJob): Result<JsonObject>
}
```

Dispatch:

- `INVENTORY` → `StorageLocationProvider.getAllLocations()`, then
  `FileOperationProvider.diskUsage(locationId, "", maxDepth)` per location. `maxDepth` starts at
  `params.maxDepth ?: 2` and **halves on a payload-cap overflow, retrying down to depth 1** before
  failing — a fixed depth that overflows would fail identically on every run forever (W34).
- `EXT_HISTOGRAM` → `ExtensionHistogram.tally(...)`.
- `HASH_BATCH` → `MediaHasher.hashBatch(...)`, then `FleetClient.reportMedia(rows)`; the job result
  carries counts only, never the rows.
- `STAT_PATH` → `FileOperationProvider.statPath(...)`.
- `knownKind == null` → `Result.failure`, so the queue advances.

Every serialised result is checked against a 256 KB cap before it is returned.

**Definition of Done**

- [ ] An unknown kind fails its own job and the next job still runs.
- [ ] `INVENTORY` reduces depth rather than failing permanently on an oversized tree.
- [ ] `HASH_BATCH` never puts hashed rows in the job result.

### Task 2.5 — App: the sync worker

**Action 2.5.1** — create `.../services/fleet/FleetSyncWorker.kt`

`@HiltWorker` `CoroutineWorker` modelled on `UpdateCheckWorker`. `doWork()`:

1. Return `Result.success()` immediately when unpaired.
2. Heartbeat with capacity, free bytes and the tunnel URL (or null).
3. Loop: `claimJob(leaseSeconds = 900)` → `run` → `completeJob(jobId, attempt, ...)`, until the
   claim returns null, `isStopped` is true, or **8 minutes** have elapsed.
4. Network failure → `Result.retry()`; anything else → `Result.success()`.

The 8-minute budget sits inside WorkManager's ~10-minute ceiling with room for the round-trips
(W32), and the 900-second lease comfortably outlasts a 4-minute hash budget plus upload (W33).

**Action 2.5.2** — create `.../services/fleet/FleetSyncScheduler.kt`

Modelled on `UpdateCheckScheduler`: `schedule()` enqueues a unique periodic
`PeriodicWorkRequestBuilder<FleetSyncWorker>(6, TimeUnit.HOURS)` with
`ExistingPeriodicWorkPolicy.UPDATE`; `syncNow()` enqueues a unique one-shot; `cancel()` cancels
both by name. Constraints come from settings: `NetworkType.UNMETERED` and `setRequiresCharging(true)`
unless the user has overridden them.

**Action 2.5.3** — modify `.../data/model/ServerConfig.kt`,
`.../data/repository/SettingsRepository.kt` and `SettingsRepositoryImpl.kt`: add
`fleetSyncUnmeteredOnly: Boolean = true` and `fleetSyncRequiresCharging: Boolean = true` with
`updateFleetSyncUnmeteredOnly` / `updateFleetSyncRequiresCharging`. AC 2.5's override had no
implementation in plan 69 (W28); it has one here.

**Action 2.5.4** — modify `.../ui/screens/settings/FleetSettingsScreen.kt` and its ViewModel: two
`SettingsSwitchRow`s for those settings, with `HelpHint`s explaining that relaxing them lets sync
run on mobile data and on battery.

**Action 2.5.5** — modify `.../services/tunnel/TunnelManager.kt`: add

```kotlin
/** The current public URL, or null when no tunnel is up. Derived from [tunnelStatus]. */
val publicUrl: StateFlow<String?>
```

`TunnelManager` today exposes `tunnelStatus: StateFlow<TunnelStatus>` only (W5).

**Definition of Done**

- [ ] With the MCP server stopped and no tunnel, a queued `INVENTORY` completes end to end.
- [ ] Stopping the tunnel clears `live_url` on the next heartbeat.
- [ ] Unpairing cancels both work names.
- [ ] Relaxing either constraint takes effect on the next `schedule()`.

### Task 2.6 — Tests for user story 2

**File**: `app/src/test/kotlin/.../services/fleet/MediaHasherImplTest.kt`

**Setup**: JUnit 5 `@TempDir`; a fake `FileOperationProvider` backed by real files whose
`readStreaming` opens a `FileInputStream`; real `HashIndexStoreImpl` over the temp dir.

| Test | Verifies |
|---|---|
| `digest of known content matches the expected sha256` | Precomputed digest of `"hello\n"` |
| `digest is lowercase hex of length 64` | Matches the contract's `^[a-f0-9]{64}$` |
| `unchanged file is skipped on the second pass` | Zero reads; `exhausted = true` |
| `changed lastModified forces a re-hash` | Cache invalidation |
| `changed size forces a re-hash` | Both fingerprint fields matter |
| `null lastModified always re-hashes` | Never treated as unchanged |
| `maxFiles bounds the batch` | Exactly `maxFiles`, `exhausted = false` |
| `zero budget stops before the first file` | Deadline honoured |
| `64 MB file hashes correctly` | Streaming; assert the digest |

**File**: `app/src/test/kotlin/.../services/fleet/HashIndexStoreImplTest.kt`

| Test | Verifies |
|---|---|
| `append then fingerprint round-trips` | Basic persistence |
| `index survives a new store instance over the same directory` | Reload from disk |
| `later record supersedes an earlier one for the same path` | Append-only semantics |
| `compaction preserves live records and shrinks the file` | Assert count unchanged, size reduced |
| `clear removes only the named location` | Isolation |
| `a truncated final line is ignored rather than throwing` | Crash-during-append recovery |

**File**: `app/src/test/kotlin/.../services/fleet/FleetJobRunnerImplTest.kt`

| Test | Verifies |
|---|---|
| `INVENTORY returns one entry per location` | Uses `getAllLocations()` |
| `INVENTORY halves depth on cap overflow and succeeds` | **Setup**: mock `diskUsage` to return an oversized tree at depth 2 and a small one at depth 1 |
| `INVENTORY fails when depth 1 still overflows` | Terminal case |
| `EXT_HISTOGRAM forwards locationId and path` | Argument passing |
| `HASH_BATCH reports rows through the client and returns counts only` | Rows absent from the result |
| `HASH_BATCH failure to report does not mark the job done` | Failure propagates |
| `STAT_PATH maps a missing path to failure` | Error path |
| `unknown kind fails without throwing` | Queue advances |

**File**: `app/src/test/kotlin/.../services/fleet/FleetSyncWorkerTest.kt`

**Setup**: `TestListenableWorkerBuilder<FleetSyncWorker>`; MockK `FleetClient`, `FleetJobRunner`,
`FleetIdentityRepository`, `TunnelManager`.

| Test | Verifies |
|---|---|
| `unpaired device does no work` | Zero client calls, `Result.success` |
| `empty queue ends the loop after one claim` | One claim, no run |
| `claimed job is run and completed with its fencing attempt` | `completeJob(attempt = n)` |
| `runner failure completes the job as failed` | `ok = false` with the error text |
| `network failure returns retry` | `Result.retry()` |
| `heartbeat sends null live url when the tunnel is down` | Stale URL cleared |
| `loop stops when isStopped becomes true` | Cooperative cancellation |

**Definition of Done**

- [ ] Tests are written and self-consistent. Execution is Task 5.2.

---

## User story 3 — The media index reaches the panel

**Why**: hashes are useless where they are computed. Plan 69 computed them on the device and never
moved them into `fleet.media` / `fleet.device_media`, so the cross-device join — and with it
`SOLO_MOVIL`, the only class with no backup anywhere — could never be answered (C7).

**Acceptance criteria**

- [ ] `HASH_BATCH` results land in `fleet.media` and `fleet.device_media`.
- [ ] `media_id` is constrained to lowercase 64-hex at the column.
- [ ] The batch endpoint is capped server-side.
- [ ] The panel can read cross-device state for the devices its viewer owns.

### Task 3.1 — Schema `fleet` (media)

**Action 3.1.1** — create `supabase/migrations/0004_fleet_media.sql`

```sql
create table fleet.media (
  media_id       text primary key check (media_id ~ '^[a-f0-9]{64}$'),
  size_bytes     bigint check (size_bytes is null or size_bytes >= 0),
  media_type     text check (media_type in ('image','video','document','other')),
  derived_from   text references fleet.media(media_id) deferrable initially deferred,
  policy_version text,
  engine_version text,
  first_seen_at  timestamptz not null default now()
);

create table fleet.device_media (
  media_id    text not null references fleet.media(media_id) on delete cascade,
  device_id   uuid not null references device_registry.devices(device_id) on delete cascade,
  location_id text not null,
  path        text not null,
  observed_at timestamptz not null default now(),
  primary key (media_id, device_id, location_id, path)
);
create index device_media_device_idx on fleet.device_media (device_id);

alter table fleet.media enable row level security;
alter table fleet.media force row level security;
alter table fleet.device_media enable row level security;
alter table fleet.device_media force row level security;
```

`media_type` is wider than the engine's `image|video` because the phone inventory contains
documents; the widening is declared rather than silent (S2). `derived_from` is deferrable so a
payload may carry a derivative before its original (W31). `policy_version` and `engine_version` are
recorded so a policy change cannot silently reinterpret stored rows (S3).

**Action 3.1.2** — append to `0004_fleet_media.sql`: the device-facing report

```sql
create or replace function public.fleet_report_media(p_token text, p_rows jsonb)
returns integer language plpgsql volatile security definer set search_path = '' as $$
declare v_device uuid; v_count integer;
begin
  v_device := device_registry.device_for_token(p_token);
  if v_device is null then raise exception 'unknown device' using errcode = '42501'; end if;
  if jsonb_typeof(p_rows) <> 'array' then
    raise exception 'rows must be an array' using errcode = '22023';
  end if;
  -- The mobile media index does cross the cable, in capped batches. Saying so is the honest
  -- version of plan 69's invariant, which claimed the opposite while doing this.
  if jsonb_array_length(p_rows) > 1000 then
    raise exception 'batch too large' using errcode = '22023';
  end if;
  if pg_column_size(p_rows) > 524288 then
    raise exception 'batch too large' using errcode = '22023';
  end if;

  insert into fleet.media (media_id, size_bytes, media_type)
  select r->>'mediaId', (r->>'sizeBytes')::bigint, r->>'mediaType'
    from jsonb_array_elements(p_rows) r
  on conflict (media_id) do nothing;

  insert into fleet.device_media (media_id, device_id, location_id, path, observed_at)
  select r->>'mediaId', v_device, r->>'locationId', r->>'path', now()
    from jsonb_array_elements(p_rows) r
  on conflict (media_id, device_id, location_id, path)
    do update set observed_at = now();

  get diagnostics v_count = row_count;
  return v_count;
end;
$$;
revoke all on function public.fleet_report_media(text, jsonb) from public;
grant execute on function public.fleet_report_media(text, jsonb) to anon, authenticated;
```

**Action 3.1.3** — append to `0004_fleet_media.sql`: cross-device state

```sql
-- SOLO_MOVIL, SOLO_PC and AMBOS_IGUAL fall out of a group by media_id. AMBOS_DISTINTO cannot:
-- it is the same NAME under different hashes, which no grouping by hash can produce. It arrives
-- from the engine as a REVIEW row with reason CONFLICT, and plan 71 reads it from there.
create or replace function public.fleet_cross_device_summary()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare v_email text;
begin
  v_email := device_registry.current_viewer_email();
  if v_email is null then return null; end if;
  return coalesce((
    select jsonb_object_agg(s.state, jsonb_build_object('count', s.n, 'bytes', s.b))
    from (
      select case when bool_or(d.platform <> 'PC') and not bool_or(d.platform = 'PC')
                    then 'SOLO_MOVIL'
                  when bool_or(d.platform = 'PC') and not bool_or(d.platform <> 'PC')
                    then 'SOLO_PC'
                  else 'AMBOS_IGUAL' end as state,
             count(*) as n,
             coalesce(sum(max(m.size_bytes)), 0) as b
      from fleet.media m
      join fleet.device_media dm on dm.media_id = m.media_id
      join device_registry.devices d on d.device_id = dm.device_id
      where d.owner_email = v_email and d.revoked_at is null
      group by m.media_id
    ) s group by s.state), '{}'::jsonb);
end;
$$;
revoke all on function public.fleet_cross_device_summary() from public, anon;
grant execute on function public.fleet_cross_device_summary() to authenticated;
```

Revoked devices are excluded, so a revoked phone cannot keep a medium reading as mobile-resident
forever (I5). Returned pre-aggregated rather than as a view the browser scans (W35), and as a
`security definer` function rather than a plain view that would run with the owner's rights (W43).

**Definition of Done**

- [ ] The same `media_id` reported by two devices yields one `fleet.media` row and two
      `fleet.device_media` rows.
- [ ] A `media_id` of `ABC` or of 63 characters is rejected by the check constraint.
- [ ] A 1001-row batch and a 600 KB batch are both rejected.
- [ ] `fleet_cross_device_summary()` returns `null` for a non-viewer and correct counts for a viewer.

### Task 3.2 — Web: the fleet API surface

**Action 3.2.1** — modify `web/package.json`: add `jsdom`, `@testing-library/react`,
`@testing-library/jest-dom` and `@testing-library/user-event` as dev dependencies, pinned to
current stable versions. `ajv` is not needed until plan 71 and is not added here (W11).

**Action 3.2.2** — modify `web/vite.config.ts`: add

```ts
test: {
  environment: 'jsdom',
  setupFiles: ['./src/test/setup.ts'],
  globals: true,
},
```

**Action 3.2.3** — create `web/src/test/setup.ts` importing `@testing-library/jest-dom/vitest`.

**Action 3.2.4** — create `web/src/fleet/types.ts` mirroring `docs/schemas/device.schema.json`, and
`web/src/fleet/api.ts` with typed wrappers over `fleet_mint_pairing_code`, `fleet_devices`,
`fleet_revoke_device`, `fleet_jobs`, `fleet_enqueue_job` and `fleet_cross_device_summary`. Every
wrapper maps a `null` RPC result to a safe empty value rather than throwing.

**Definition of Done**

- [ ] `npm run build --prefix web` succeeds with the new types.
- [ ] Every RPC this plan creates has exactly one wrapper.

### Task 3.3 — Web: pairing and device list

**Action 3.3.1** — create `web/src/fleet/PairDeviceDialog.tsx`: alias field, platform selector
(`ANDROID` / `PC`), mint button, the code rendered as `XXXX-XXXX` in the monospace face at display
size, a live countdown to `expiresAt`, copy-to-clipboard, and a re-mint button once expired.

The platform selector exists because the PC pairs as a device like any other — that decision is what
makes the cross-device join possible at all.

**Action 3.3.2** — create `web/src/fleet/DeviceList.tsx`: one row per device with alias, platform,
free-over-capacity as a thin bar, last-seen as relative time, and a reachability chip reading
`EN VIVO` when `liveUrl` is set and `lastSeenAt` is under two minutes old, `EN COLA` otherwise.

The chip is labelled by what it means — the device **reported** a tunnel recently. The panel cannot
probe it: `netlify.toml` pins `connect-src` to Supabase deliberately (W27).

**Action 3.3.3** — modify `web/src/App.tsx`: mount the device list and pairing dialog inside the
existing `loading | anonymous | denied | granted` state machine, unchanged.

**Definition of Done**

- [ ] A minted code pairs a real device end to end.
- [ ] The list reflects heartbeats on a 30-second poll.
- [ ] Revoking removes the row and the device's next call fails.
- [ ] The four access states behave exactly as before the change.

### Task 3.4 — Tests for user story 3

**File**: `web/src/fleet/DeviceList.test.tsx`

| Test | Verifies |
|---|---|
| `renders EN VIVO for a recent heartbeat with a live url` | Chip logic |
| `renders EN COLA when liveUrl is null` | Chip logic |
| `renders EN COLA when lastSeenAt is older than two minutes` | Staleness |
| `renders free over capacity without dividing by zero` | Null capacity |
| `revoke asks for confirmation before calling the api` | Destructive UI guard |

**File**: `web/src/fleet/PairDeviceDialog.test.tsx`

| Test | Verifies |
|---|---|
| `mint renders the code grouped as XXXX-XXXX` | Formatting |
| `countdown reaches zero and offers a re-mint` | **Setup**: fake timers |
| `mint is disabled with an empty alias` | Validation |
| `platform selector offers ANDROID and PC` | The PC pairs as a device |

**File**: `app/src/test/kotlin/.../App.tsx` — not applicable; instead
**File**: `web/src/App.test.tsx`

| Test | Verifies |
|---|---|
| `renders the auth screen when anonymous` | Preserved state machine (I17) |
| `renders the denied screen for a non-viewer` | Preserved |
| `renders the device list when granted` | New mount point |

**Definition of Done**

- [ ] Tests are written and self-consistent. Execution is Task 5.2.

---

## User story 4 — Documentation

**Acceptance criteria**

- [ ] The transport is documented with a validated Mermaid diagram.
- [ ] PROJECT.md carries the fleet section and the invariants this plan is bound by.

### Task 4.1 — Documentation

**Action 4.1.1** — modify `docs/ARCHITECTURE.md`: add a "Fleet transport" section with a Mermaid
sequence diagram covering pair → heartbeat → claim → run → complete → report, and a component
diagram showing both directions (device → Supabase for autonomous work, panel → tunnel → device for
live work). Validate with `mmdc`; no ASCII art.

**Action 4.1.2** — modify `docs/PROJECT.md`: add a "Fleet" section stating the job kinds, that the
queue cannot express a destructive job in this release, the device-token model, the streaming-read
exemption from the file-size limit and why, and the invariants table from this plan's header with a
pointer to `docs/contract/`.

**Definition of Done**

- [ ] Every Mermaid diagram validates with `mmdc`.
- [ ] PROJECT.md states that the panel never computes `safe_to_purge_staging` or
      `safe_to_release_mobile`.

---

## User story 5 — Quality gates

Run only after every user story above is implemented, per CLAUDE.md.

### Task 5.1 — Manual QA steps

**Manual QA Steps** — database behaviour that has no automated harness in this repository:

1. Anonymous and non-viewer callers get `null` from `fleet_devices()` and
   `fleet_cross_device_summary()`, verified with `set local role authenticated` and
   `set local request.jwt.claims`.
2. A code minted before revocation fails after revocation.
3. Device A cannot claim device B's job.
4. `insert into fleet.jobs (kind) values ('MOVE_FILE')` is rejected.
5. A stale `p_attempt` completion returns false.

These are labelled manual because they are not a substitute for automated tests (W8); Task 5.3
records the follow-up.

### Task 5.2 — Gates

- [ ] `make lint` clean (ktlint and detekt), no new suppressions.
- [ ] `./gradlew build` succeeds with no warnings.
- [ ] `./gradlew :app:test` green.
- [ ] `npm test --prefix web` green; `npm run build --prefix web` succeeds.
- [ ] `code-reviewer` in plan-compliance mode reports no issues; re-run until clean.

### Task 5.3 — Follow-up recorded, not deferred silently

**Action 5.3.1** — create `docs/plans/README.md` if absent, and record that database-level tests
(pgTAP or `supabase db test`) are not yet part of this repository's harness, that the five manual
steps above stand in for them today, and that adding the harness is the first task of plan 71.

**Definition of Done**

- [ ] The gap is written down where the next plan will find it.

---

## Decisions closed before this plan was written

1. **The PC pairs as a device.** Confirmed by the user. It is what makes the cross-device join
   possible, and it reuses user story 1 with no second credential path.
2. **Thumbnails do not travel in the ingest payload.** They go to a private Supabase Storage bucket
   keyed by `media_id`; because the key is the content hash, a thumbnail is uploaded once ever and
   is reused by any device that later reports the same medium. The panel fetches signed URLs. This
   is plan 71's work; recorded here because it removes plan 69's 2 MB conflict.
3. **The seed carries aggregates, the four opportunities and the PC device row — not the 207 REVIEW
   rows.** REVIEW rows are `run_id`-scoped and the engine's first run replaces them; a seeded row
   that does not match the engine's output is a phantom that looks like data. The seed is written
   under a reserved, visibly-labelled `run_id`, and the panel is considered done when the engine's
   first real ingest replaces it.

## Open question

**`HashIndexStore` is an append-only file rather than Room.** CLAUDE.md §6 scopes DataStore to
settings and states the project has no complex relational data, so a 91k-row index belongs in
neither. If Room is preferred it is an architecture change requiring explicit approval; Task 2.3.2
must not be implemented as Room without it.
