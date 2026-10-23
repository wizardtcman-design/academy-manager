import { defineConfig, type Plugin } from 'vitest/config';
import { readFileSync } from 'node:fs';

/**
 * The worker imports src/templates/sign.html as raw TEXT (wrangler's
 * built-in rule for .html imports). Vite's assetsInclude would import it
 * as a URL string instead, so mirror the wrangler behaviour with a tiny
 * transform plugin: .html modules export the file content verbatim.
 */
function htmlAsText(): Plugin {
    return {
        name: 'html-as-text',
        enforce: 'pre',
        transform(_code, id) {
            const path = id.split('?')[0];
            if (path.endsWith('.html')) {
                const text = readFileSync(path, 'utf8');
                return { code: `export default ${JSON.stringify(text)};`, map: null };
            }
            return null;
        }
    };
}

export default defineConfig({
    test: {
        include: ['test/**/*.test.ts'],
        environment: 'node'
    },
    plugins: [htmlAsText()]
});
