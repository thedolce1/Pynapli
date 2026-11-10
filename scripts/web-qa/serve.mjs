// Static server for the Wasm production bundle, with the single-page-app fallback the deployed
// host provides: any path that is not a file (/grid, /tonight, /messages/<id>) serves index.html,
// so deep links and bookmarks load like they do in production.
//
// usage: node scripts/web-qa/serve.mjs <distDir> [port]
// Port 8081 is the origin the local server's CORS allows (ServerConfig: local -> localhost:8081).
import { createReadStream, existsSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, resolve } from 'node:path';

const root = resolve(process.argv[2] ?? 'webApp/build/dist/wasmJs/productionExecutable');
const port = Number(process.argv[3] ?? 8081);
const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png',
  '.svg': 'image/svg+xml', '.map': 'application/json', '.ttf': 'font/ttf', '.xml': 'application/xml',
};

if (!existsSync(join(root, 'index.html'))) {
  console.error(`No index.html in ${root}. Build it with: gradlew :webApp:wasmJsBrowserDistribution`);
  process.exit(1);
}

createServer((req, res) => {
  const requested = decodeURIComponent((req.url ?? '/').split('?')[0]);
  let file = join(root, requested);
  if (!file.startsWith(root) || !existsSync(file) || statSync(file).isDirectory()) file = join(root, 'index.html');
  res.writeHead(200, { 'Content-Type': types[extname(file)] ?? 'application/octet-stream' });
  createReadStream(file).pipe(res);
}).listen(port, '127.0.0.1', () => console.log(`web-qa: serving ${root} on http://localhost:${port}`));
