# Visor de almacenamiento

Página que muestra qué ocupa el almacenamiento del teléfono, medido a través del servidor MCP de la
app Android de este mismo repositorio.

## Por qué vive acá

Lo que produce la captura y lo que consume la página son **el mismo contrato** (`src/snapshot.ts` es
un espejo de `DiskUsageResult` en el lado Android). En un solo repositorio no pueden
desincronizarse en silencio: un campo renombrado en el dispositivo rompe el `tsc` de acá.

## Configuración

Ninguna. `.env.production` está commiteado y el build lo toma solo.

Esas dos credenciales **no son secretas**: identifican al proyecto, no a una persona, y Vite las
inlinea en el JavaScript, así que son legibles por cualquiera que abra el sitio, estén en el repo o
no. Lo que protege los datos no es esa clave —es `device_storage.viewers`, verificada en el
servidor en cada pedido.

La clave de servicio de Supabase es harina de otro costal: saltea row level security y **nunca** se
commitea. Vive solo en el entorno de quien corre la captura.

Para desarrollo local con otro proyecto: `cp .env.example .env` (ignorado por git) o variables de
entorno en Netlify — cualquiera de las dos le gana a `.env.production`.

## La página no habla con el teléfono

Consulta Supabase, nunca el dispositivo. Funciona con el celular apagado. El
`Content-Security-Policy` del deploy nombra **una sola** dirección en `connect-src` —el proyecto de
Supabase—, así que un pedido al túnel del teléfono lo bloquea el navegador, entre en el bundle lo
que entre después.

## Quién puede entrar

El padrón de usuarios de ese proyecto de Supabase pertenece a otra aplicación, así que **poder
iniciar sesión no es permiso para estar acá**. Lo decide `device_storage.viewers`, una lista de
correos autorizados que el servidor consulta en cada pedido. Un usuario autenticado pero no listado
recibe `null`, no un inventario vacío — la página distingue las dos cosas.

Autorizar a alguien:

```sql
insert into device_storage.viewers (email, note) values ('persona@ejemplo.com', 'quién es');
```

## La captura

Paso aparte, desde una máquina de confianza: tiene las dos credenciales que nunca deben llegar a un
navegador — el token del dispositivo (control total del celular) y la clave de servicio de Supabase
(saltea row level security).

```sh
MCP_URL=https://<tunel>.trycloudflare.com/mcp \
MCP_TOKEN=<token del celular> \
SUPABASE_URL=https://<proyecto>.supabase.co \
SUPABASE_SERVICE_ROLE_KEY=<clave de servicio> \
npm run capture
```

Inserta una instantánea nueva; la página siempre muestra la última. Para una corrida en seco sin
tocar la base: `SNAPSHOT_OUT=tmp/snapshot.json`.

## Las tablas

`device_storage` está **fuera** de la API REST a propósito. La única puerta es la función
`public.device_storage_latest_snapshot()`, que verifica la lista antes de devolver nada.

| tabla | qué guarda |
|---|---|
| `snapshots` | una fila por captura |
| `locations` | ubicaciones autorizadas en esa captura, con su nivel de acceso |
| `usage_nodes` | el árbol aplanado — es lo que permite comparar capturas y medir crecimiento |
| `viewers` | correos autorizados |

## Comandos

| | |
|---|---|
| `npm run dev` | servidor de desarrollo |
| `npm run build` | build de producción a `dist/` |
| `npm run lint` | chequeo de tipos |
| `npm test` | tests del parser |
| `npm run capture` | mide el teléfono y guarda una instantánea en Supabase |

## Lo que falta

Una consola en vivo: pegar URL y token a mano para navegar el dispositivo en tiempo real. Requiere
decisiones de seguridad distintas — el token nunca se persiste ni se despliega — y por eso va
separada del visor, no mezclada con él.
