# docs.mutastic.io

mutastic's documentation: [Astro Starlight](https://starlight.astro.build), deployed to
Cloudflare Pages.

```sh
npm ci
npm run dev      # http://localhost:4321, reloading as you edit
npm run build    # dist/, as deployed
```

## Where the content comes from

- **Guides and concepts** are Markdown in `src/content/docs/`.
- **The command reference** (`src/pages/reference/`) is written from `src/data/commands.json`,
  which the CLI's own declarations produce, the same ones `mutastic --help` prints. After changing
  an option or a command, regenerate it:

  ```sh
  ./gradlew :mutastic-cli:exportDocsModel
  ```

  `./gradlew build` fails while the committed file and the code disagree.
- **Every command the docs show is checked**: `DocsCommandsTest` parses each `mutastic …`,
  `mvn …mutastic:…` and `./gradlew …mutastic…` line in a code block, so a page can't show an option
  the CLI doesn't take.
- **Coding agents** get `/llms.txt`, `/llms-full.txt` and `/llms-small.txt`.

Code is set in JetBrains Mono with its ligatures off: these pages are about telling `>=` from `>`.

## Deploying

`.github/workflows/docs.yml` builds the site on every change. docs.mutastic.io describes the latest
release, so it's deployed from each release's tag, to the Pages project's production branch,
`release`. main and each pull request deploy to preview addresses of their own
(`main.mutastic-docs.pages.dev`), since main documents what isn't released yet.

It deploys once these exist; the vault's *Mutastic hosting and DNS setup* has the whole checklist:

1. **A Cloudflare Pages project** named `mutastic-docs`, whose production branch is `release`:
   ```sh
   npx wrangler pages project create mutastic-docs --production-branch=release
   ```
2. **Two repository secrets**: `CLOUDFLARE_ACCOUNT_ID` (on the account's overview page), and
   `CLOUDFLARE_API_TOKEN`, a token with the *Cloudflare Pages: Edit* permission.
   ```sh
   gh secret set CLOUDFLARE_ACCOUNT_ID --repo mutastic/mutastic
   gh secret set CLOUDFLARE_API_TOKEN --repo mutastic/mutastic
   ```
3. **The domain**: add `docs.mutastic.io` under the project's *Custom domains* first, then its
   DNS record. In that order: a record pointing at a project that doesn't know the domain yet gives
   a 522 error.
