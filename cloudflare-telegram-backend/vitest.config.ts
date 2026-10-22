import { defineConfig } from 'vitest/config';

export default defineConfig({
    test: {
        include: ['test/**/*.test.ts'],
        environment: 'node'
    },
    // sign.html is imported as raw text by the worker; keep it out of the
    // HTML transform pipeline.
    assetsInclude: ['**/*.html']
});
