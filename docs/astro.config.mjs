// docs.mutastic.io. Guides and concepts are Markdown under src/content/docs; the command
// reference is src/pages/reference, written from src/data/commands.json, which the CLI's own
// declarations produce (./gradlew :mutastic-cli:exportDocsModel), so it can't drift from them.
import starlight from '@astrojs/starlight';
import { defineConfig } from 'astro/config';
import starlightLlmsTxt from 'starlight-llms-txt';

export default defineConfig({
  site: 'https://docs.mutastic.io',
  integrations: [
    starlight({
      title: 'mutastic',
      // Its own 404 page is src/content/docs/404.md, in the site's words.
      disable404Route: true,
      description: 'Mutation testing for Java: finds the changes your tests don\'t notice, and suggests the tests that would.',
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/mutastic/mutastic' }],
      editLink: { baseUrl: 'https://github.com/mutastic/mutastic/edit/main/docs/' },
      customCss: [
        '@fontsource-variable/bricolage-grotesque',
        '@fontsource/jetbrains-mono/400.css',
        '@fontsource/jetbrains-mono/700.css',
        './src/styles/theme.css',
      ],
      plugins: [starlightLlmsTxt()],
      sidebar: [
        {
          label: 'Getting started',
          items: ['getting-started/install', 'getting-started/quickstart'],
        },
        {
          label: 'Concepts',
          items: ['concepts/results', 'concepts/operators'],
        },
        {
          label: 'Reference',
          items: [
            { label: 'mutastic run', link: '/reference/run/' },
            { label: 'mutastic verify', link: '/reference/verify/' },
            { label: 'mutastic explain', link: '/reference/explain/' },
            { label: 'mutastic ignore', link: '/reference/ignore/' },
            { label: 'Environment and exit codes', link: '/reference/environment/' },
          ],
        },
        {
          label: 'Help',
          items: ['guides/troubleshooting'],
        },
      ],
    }),
  ],
});
