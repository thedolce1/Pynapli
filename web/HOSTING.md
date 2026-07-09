# Hosting — pynapli.com (marketing site)

The static marketing site (`web/`, Astro → `web/dist/`) is deployed to **Firebase
Hosting** (classic Hosting, *not* the newer "App Hosting" — this is a pre-built static
site, not SSR). Config lives at the repo root: `firebase.json` + `.firebaserc`
(project `pynapli`). Run all Firebase commands **from the repo root**.

## Deploy

```bash
cd web && npm run build && cd ..   # produces web/dist/
firebase login                     # one-time, needs the founder's Google account
firebase deploy --only hosting     # publishes web/dist to Firebase Hosting
```

The security headers (CSP, HSTS, etc.) live in `firebase.json` under `hosting.headers`
— Firebase does **not** read a Netlify/Cloudflare `_headers` file, so `firebase.json`
is the single source of truth for headers.

## Content-Security-Policy: regenerating the script hashes

`script-src` is locked to `'self'` plus the SHA-256 hashes of the **two** scripts
Astro inlines into the HTML (reveal-on-scroll in `Base.astro`, the waitlist handler
in `Waitlist.astro`). The hashes are of the *built, minified* script bodies, so they
change if those scripts change (the waitlist one **will** change when the form is
wired to a real email service) or on an Astro minifier bump. After `npm run build`,
recompute and update both `script-src` hashes in `firebase.json`:

```bash
node -e "const{createHash}=require('crypto');const{readFileSync}=require('fs');const h=readFileSync('web/dist/index.html','utf8');for(const m of h.matchAll(/<script type=\"module\">([\s\S]*?)<\/script>/g))console.log('sha256-'+createHash('sha256').update(m[1]).digest('base64'))"
```

When the waitlist form starts calling an external email service, also add that origin
to `connect-src` (fetch/XHR) and/or `form-action` (native form POST) in the CSP.

## Custom domains

Point `pynapli.com` at Firebase Hosting via the Firebase console (Hosting → Add custom
domain); Firebase provisions the TLS cert and the HTTP→HTTPS redirect. When
`app.pynapli.com` (the PWA) is ready it can live in the **same** Firebase project as a
second Hosting site (multi-site: `firebase target:apply hosting <target> <site>` +
a `hosting` array in `firebase.json`).

## Validating headers after deploy

Once live, check `https://pynapli.com` at securityheaders.com and Google's CSP
Evaluator, and confirm the reveal animation + waitlist still work (a broken script
hash shows as a CSP violation in the browser console, not a visible error).
