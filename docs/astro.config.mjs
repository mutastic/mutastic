// docs.mutastic.io. Guides and concepts are Markdown under src/content/docs; the command
// reference is src/pages/reference, written from src/data/commands.json, which the CLI's own
// declarations produce (./gradlew :mutastic-cli:exportDocsModel), so it can't drift from them.
// The plugin API's, src/pages/reference/api, is written the same way from src/data/api.json, the
// API's own doc comments (./gradlew :mutastic-api:exportApiModel).
//
// Every release's docs stay published. scripts/build-versions.mjs builds each from its own tag,
// under its own path, and tells each build which it is through the environment:
//   DOCS_BASE      where it's served: `/` for the latest release, `/v0.3/` for an older one
//   DOCS_VERSION   the release line it describes, `0.3`; `next` for main; unset locally
//   DOCS_LATEST    the latest release line, `0.5`
//   DOCS_VERSIONS  every release line published, newest first: `0.5,0.4,0.3`
// Keep these four as they are: a release's docs are rebuilt from its tag with them for as long
// as it's published.
import { unified } from '@astrojs/markdown-remark';
import starlight from '@astrojs/starlight';
import { defineConfig } from 'astro/config';
import starlightLlmsTxt from 'starlight-llms-txt';

const base = process.env.DOCS_BASE ?? '/';
const version = process.env.DOCS_VERSION ?? '';
const latest = process.env.DOCS_LATEST ?? '';
/**
 * Anything but the latest release at the root: an older release's docs, main's, and the latest's
 * pinned copy under /v<line>/. Kept out of search engines, which should find the one at the root.
 */
const notLatest = (version !== '' && version !== latest) || base !== '/';

/** Root-relative links in the Markdown, `/concepts/results/`, under this build's base path. */
function linksUnderBase() {
  const visit = (node) => {
    if (node.type === 'element' && node.tagName === 'a') {
      const href = node.properties?.href;
      if (typeof href === 'string' && href.startsWith('/') && !href.startsWith('//')) {
        node.properties.href = base.replace(/\/$/, '') + href;
      }
    }
    node.children?.forEach(visit);
  };
  return (tree) => visit(tree);
}

export default defineConfig({
  site: 'https://docs.mutastic.io',
  base,
  markdown: { processor: unified({ rehypePlugins: [linksUnderBase] }) },
  integrations: [
    starlight({
      title: 'mutastic',
      // Its own 404 page is src/content/docs/404.md, in the site's words.
      disable404Route: true,
      description:
        "Mutation testing for Java: finds the changes your tests don't notice, and suggests the tests that would.",
      head: [
        ...(notLatest ? [{ tag: 'meta', attrs: { name: 'robots', content: 'noindex' } }] : []),
        // Umami, the same cookie-free analytics as mutastic.io, as its own website: counted only
        // on docs.mutastic.io itself, never on a preview.
        {
          tag: 'script',
          attrs: {
            defer: true,
            src: 'https://umami.wazolabs.co/script.js',
            'data-website-id': 'ba6d5674-59d9-4086-93dd-b58640d3925b',
            'data-domains': 'docs.mutastic.io',
          },
        },
      ],
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/mutastic/mutastic' }],
      editLink: { baseUrl: 'https://github.com/mutastic/mutastic/edit/main/docs/' },
      customCss: [
        '@fontsource-variable/bricolage-grotesque',
        '@fontsource/jetbrains-mono/400.css',
        '@fontsource/jetbrains-mono/700.css',
        './src/styles/theme.css',
      ],
      components: {
        // The release these docs describe, and a way to the others.
        SiteTitle: './src/components/SiteTitle.astro',
        Banner: './src/components/Banner.astro',
        // A link back to mutastic.io beside GitHub's.
        SocialIcons: './src/components/SocialIcons.astro',
      },
      plugins: [starlightLlmsTxt()],
      sidebar: [
        {
          label: 'Getting started',
          items: ['getting-started/install', 'getting-started/quickstart'],
        },
        {
          label: 'Concepts',
          items: [
            'concepts/results',
            'concepts/equivalence',
            'concepts/output',
            'concepts/next',
            'concepts/suggested-tests',
            'concepts/flaky-tests',
            'concepts/ghosts',
          ],
        },
        {
          label: 'Operators',
          items: [
            'concepts/operators',
            'concepts/operators/conditions',
            'concepts/operators/arithmetic',
            'concepts/operators/returns',
            'concepts/operators/calls',
            'concepts/operators/arguments',
            'concepts/operators/library',
            'concepts/operators/streams',
            'concepts/operators/optional',
            'concepts/operators/control',
          ],
        },
        {
          label: 'Reference',
          items: [
            { label: 'mutastic run', link: `${base}reference/run/` },
            { label: 'mutastic verify', link: `${base}reference/verify/` },
            { label: 'mutastic explain', link: `${base}reference/explain/` },
            { label: 'mutastic ignore', link: `${base}reference/ignore/` },
            { label: 'mutastic next', link: `${base}reference/next/` },
            { label: 'mutastic baseline', link: `${base}reference/baseline/` },
            { label: 'mutastic gate open', link: `${base}reference/gate-open/` },
            { label: 'mutastic gate check', link: `${base}reference/gate-check/` },
            { label: 'mutastic gate close', link: `${base}reference/gate-close/` },
            { label: 'Environment and exit codes', link: `${base}reference/environment/` },
            'reference/reports',
            'reference/compatibility',
            'reference/schemas',
            { label: 'Plugin API', link: `${base}reference/api/` },
          ],
        },
        {
          label: 'Guides',
          items: [
            'guides/build-plugins',
            'guides/scoping',
            'guides/configuration',
            'guides/plugins',
            'guides/operator-plugins',
            'guides/kotlin',
          ],
        },
        {
          label: 'Coding agents',
          items: ['agents', 'agents/claude-code', 'agents/codex', 'agents/gate', 'agents/other-agents'],
        },
        {
          label: 'Help',
          items: ['guides/troubleshooting'],
        },
      ],
    }),
  ],
});
