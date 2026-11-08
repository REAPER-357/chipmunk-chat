// Builds ./www from ./src/index.html and bundles every library locally,
// so the Android app does not depend on CDNs. If a library cannot be bundled,
// its original CDN link is kept and a warning is printed.
const fs = require('fs');
const path = require('path');
const cp = require('child_process');

const root = path.join(__dirname, '..');
const www = path.join(root, 'www');
const vendor = path.join(www, 'vendor');
const nm = p => path.join(root, 'node_modules', p);

fs.rmSync(www, { recursive: true, force: true });
fs.mkdirSync(vendor, { recursive: true });
let html = fs.readFileSync(path.join(root, 'src', 'index.html'), 'utf8');

function swap(oldText, newText, label) {
  if (!html.includes(oldText)) { console.warn('! could not find the', label, 'tag in index.html'); return; }
  html = html.replace(oldText, newText);
  console.log('+ bundled', label);
}
function firstExisting(list) { return list.find(p => fs.existsSync(p)); }

// PeerJS
{
  const src = firstExisting([nm('peerjs/dist/peerjs.min.js')]);
  if (src) { fs.copyFileSync(src, path.join(vendor, 'peerjs.min.js')); swap('<script src="https://unpkg.com/peerjs@1.5.4/dist/peerjs.min.js"></script>', '<script src="vendor/peerjs.min.js"></script>', 'peerjs'); }
  else console.warn('! peerjs not found in node_modules (run npm install). Keeping CDN link.');
}
// QR generator
{
  const src = firstExisting([nm('qrcode-generator/qrcode.js'), nm('qrcode-generator/dist/qrcode.js')]);
  if (src) { fs.copyFileSync(src, path.join(vendor, 'qrcode.js')); swap('<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcode-generator/1.4.4/qrcode.min.js"></script>', '<script src="vendor/qrcode.js"></script>', 'qrcode-generator'); }
  else console.warn('! qrcode-generator not found. Keeping CDN link.');
}
// QR scanner
{
  const src = firstExisting([nm('jsqr/dist/jsQR.js')]);
  if (src) { fs.copyFileSync(src, path.join(vendor, 'jsQR.js')); swap('<script src="https://cdnjs.cloudflare.com/ajax/libs/jsQR/1.4.0/jsQR.min.js"></script>', '<script src="vendor/jsQR.js"></script>', 'jsQR'); }
  else console.warn('! jsqr not found. Keeping CDN link.');
}
// Tailwind: compile a static stylesheet instead of the in-browser CDN script
{
  const tmpIn = path.join(root, '.tailwind-input.css');
  const out = path.join(vendor, 'tailwind.css');
  try {
    fs.writeFileSync(tmpIn, '@tailwind base;\n@tailwind components;\n@tailwind utilities;\n');
    cp.execSync('npx tailwindcss -c tailwind.config.js -i .tailwind-input.css -o "' + out + '" --minify', { cwd: root, stdio: 'inherit' });
    swap('<script src="https://cdn.tailwindcss.com"></script>\n    <script>\n        tailwind.config = { darkMode: \'class\' }\n    </script>', '<link rel="stylesheet" href="vendor/tailwind.css">', 'tailwind');
  } catch (e) {
    console.warn('! tailwind build failed. Keeping the CDN script (needs internet at launch).');
  } finally { try { fs.unlinkSync(tmpIn); } catch (e) {} }
}

fs.writeFileSync(path.join(www, 'index.html'), html);
for (const f of ['icon.png', 'small-icon.png', 'smallmc-icon.png', 'manifest.json', 'sw.js']) {
  const p = path.join(root, 'src', f);
  if (fs.existsSync(p)) fs.copyFileSync(p, path.join(www, f));
}
console.log('\nwww/ is ready.');
