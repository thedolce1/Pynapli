import { defineConfig } from 'astro/config';
import sitemap from '@astrojs/sitemap';

// Static marketing site for pynapli.com. Output is fully static (web/dist).
export default defineConfig({
  site: 'https://pynapli.com',
  build: { format: 'directory' },
  integrations: [sitemap()],
});
