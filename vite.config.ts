import { defineConfig } from 'vite';

// Builds only the bootstrap picker. The chat frontend itself is never bundled
// here — it is served by whichever instance the user configures.
export default defineConfig({
  build: { outDir: 'dist', emptyOutDir: true, target: 'es2022' },
});
