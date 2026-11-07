import { defineConfig } from 'vite';

// Built into the app's assets, so the phone serves it with no internet. Fixed names keep the relay's
// and phone's routes simple. The classic page beside it must survive a rebuild.
const names = {
  entryFileNames: 'assets/[name].js',
  chunkFileNames: 'assets/[name].js',
  assetFileNames: 'assets/[name][extname]',
};
export default defineConfig({
  esbuild: { jsx: 'automatic', jsxImportSource: 'preact' },
  build: {
    outDir: '../app/src/main/assets/web',
    emptyOutDir: false,
    target: 'es2020',
    chunkSizeWarningLimit: 300,
    rollupOptions: { input: 'index.html', output: names },
  },
  worker: { format: 'es', rollupOptions: { output: names } },
  server: { proxy: { '/ws': { target: 'ws://127.0.0.1:8090', ws: true }, '/video': { target: 'ws://127.0.0.1:8090', ws: true } } },
});
