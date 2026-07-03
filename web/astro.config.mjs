import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Static marketing site for pynapli.com. Output is fully static (web/dist).
// The legal pages are still DRAFT placeholders — they're noindex, so keep them out
// of the sitemap too (don't advertise URLs we've told crawlers to skip). Drop the
// exclusions once the finalized legal copy lands.
const noindexPaths = ['/privacy/', '/terms/', '/guidelines/', '/safety/'];

export default defineConfig({
  site: 'https://pynapli.com',
  build: { format: 'directory' },
  integrations: [
    sitemap({
      filter: (page) => !noindexPaths.some((p) => page.endsWith(p)),
    }),
  ],
});
