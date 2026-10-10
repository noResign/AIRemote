import { defineConfig, externalizeDepsPlugin } from 'electron-vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  main: {
    plugins: [externalizeDepsPlugin()],
  },
  // Preload is sandboxed (sandbox: true) so it must stay a single CJS file whose
  // only runtime require() is `electron`. See tests/preload-sandbox.test.ts.
  preload: {
    plugins: [externalizeDepsPlugin()],
  },
  renderer: {
    plugins: [react()],
  },
});
