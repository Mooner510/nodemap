import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// The Android build packages dist/ as app assets, so the bundle must live under dist/ui/ (asset path "ui/index.html").
// The page talks to native code through a JS bridge, so it must never run script from anywhere but the bundle.
// (Dev server keeps its inline HMR scripts, hence build-only.)
const csp = [
  "default-src 'self'",
  "script-src 'self' 'wasm-unsafe-eval'",
  "worker-src blob:",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https:",
  "font-src 'self' data: https:",
  "connect-src 'self' https: blob: data:",
  "object-src 'none'",
  "base-uri 'none'",
  "form-action 'none'",
].join('; ')

export default defineConfig({
  base: './',
  plugins: [
    react(),
    tailwindcss(),
    {
      name: 'nodemap-csp',
      transformIndexHtml: (html, ctx) =>
        ctx.server ? html : html.replace('<title>', `<meta http-equiv="Content-Security-Policy" content="${csp}" />\n    <title>`),
    },
  ],
  build: { outDir: 'dist/ui', emptyOutDir: true, target: 'es2022', chunkSizeWarningLimit: 1200 },
  server: { host: true },
})
