// Compiles web/plugin.tsx to web/plugin.js for the OIE web administrator.
// React is taken from platform.React at runtime, so nothing is bundled.
import { build } from 'esbuild';
import { fileURLToPath } from 'url';
import { dirname, join } from 'path';
const here = dirname(fileURLToPath(import.meta.url));
await build({
    entryPoints: [join(here, 'plugin.tsx')],
    outfile: join(here, 'plugin.js'),
    bundle: true, format: 'esm', target: 'es2020', minify: false,
    jsx: 'transform', jsxFactory: 'React.createElement', jsxFragment: 'React.Fragment',
    external: ['@oie/web-api', '@oie/web-ui', '@oie/web-shell'],
    legalComments: 'none'
});
console.log('built web/plugin.js');
