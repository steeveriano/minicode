# Visor de almacenamiento

Página que muestra qué ocupa el almacenamiento del teléfono, medido a través del servidor MCP de la
app Android de este mismo repositorio.

## Por qué vive acá

El JSON que produce la captura y el que consume la página son **el mismo contrato**
(`src/snapshot.ts` es un espejo de `DiskUsageResult` en el lado Android). En un solo repositorio no
pueden desincronizarse en silencio: un campo renombrado en el dispositivo rompe el `tsc` de acá.

## La página no habla con el teléfono

Lee un `snapshot.json` compilado dentro del bundle. Funciona con el celular apagado y **no contiene
ninguna credencial**. El `Content-Security-Policy` del deploy declara `connect-src 'none'`, que es
lo que lo demuestra por construcción y no por promesa.

La captura es un paso aparte, que se corre desde una máquina de confianza porque necesita un token
que da control total del dispositivo:

```sh
MCP_URL=https://<tunel>.trycloudflare.com/mcp \
MCP_TOKEN=<token> \
npm run capture
```

Escribe `src/data/snapshot.json`. Commiteás ese archivo y Netlify redespliega.

## Comandos

| | |
|---|---|
| `npm run dev` | servidor de desarrollo |
| `npm run build` | build de producción a `dist/` |
| `npm run lint` | chequeo de tipos |
| `npm test` | tests del parser |
| `npm run capture` | mide el teléfono y regenera el snapshot |

## Lo que falta

Una consola en vivo: pegar URL y token a mano para navegar el dispositivo en tiempo real. Requiere
decisiones de seguridad distintas — el token nunca se persiste ni se despliega — y por eso va
separada del visor, no mezclada con él.
