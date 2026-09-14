#!/usr/bin/env node
// Renders every page of the website from a source in this repository.
//
// Nothing under website/src/ is written by hand. A website that restates the ontology is exactly the
// drift this project exists to prevent: the registry gains a property, nobody remembers the site, and
// the published model quietly stops being the real one. So the site is derived, committed, and held
// to its sources by `npm run drift`, which is what pre-commit and CI run.
//
//   node scripts/generate.mjs           write the pages
//   node scripts/generate.mjs --check   fail if what is committed is not what would be written
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath, URL } from 'node:url'

const repoRoot = fileURLToPath(new URL('../../', import.meta.url))
const outputRoot = fileURLToPath(new URL('../src/', import.meta.url))

const REPOSITORY = 'https://github.com/MaximumTrainer/SdlcKnowledgeGraph'
export const GENERATED_HEADER = 'DO NOT EDIT'

/** The one place that says which source becomes which published path. */
const GUIDE_PAGES = {
  'GETTING-STARTED.md': 'guide/getting-started',
  'USER-GUIDE.md': 'guide/user-guide',
  'ONTOLOGY.md': 'guide/ontology',
  'ADAPTERS.md': 'guide/adapters',
  'TESTING.md': 'guide/testing',
  'ROADMAP.md': 'reference/roadmap'
}

/**
 * What each published path is called, for a link whose text is the path itself.
 *
 * The names are the ones in the sidebar in `.vitepress/config.ts`, so a reader meets one vocabulary
 * rather than two names for the same page.
 */
const PAGE_NAMES = {
  '/guide/ontology': 'Ontology',
  '/guide/adapters': 'Adapters',
  '/guide/testing': 'Testing',
  '/reference/roadmap': 'Roadmap',
  '/adr/': 'Decisions'
}

export const generatedFrom = source =>
  `<!-- GENERATED FROM ${source} - ${GENERATED_HEADER}. Run \`npm --prefix website run generate\`. -->\n\n`

/**
 * Rewrites the links that only a git host resolves.
 *
 * `../../issues/20` and `docs/ROADMAP.md` both work when the file is read in the repository and
 * neither works when it is served as a page, so both have to be translated. External links and bare
 * anchors are left exactly as they are, and so is anything inside a fenced code block — a shell
 * command that mentions a path is not a link.
 */
export const rewriteLinks = (markdown, sourcePath) => {
  const fromAdr = sourcePath.startsWith('docs/adr/')

  const rewriteTarget = target => {
    if (/^(https?:|mailto:|#)/.test(target)) return target

    const issue = target.match(/(?:\.\.\/)+issues\/(\d+)$/)
    if (issue) return `${REPOSITORY}/issues/${issue[1]}`

    const [, file, anchor = ''] = target.match(/^([^#]*)(#.*)?$/) ?? []
    if (!file) return target

    const normalised = file.replace(/^(\.\.\/|\.\/)+/, '')

    if (/^docs\/adr\/?$/.test(normalised) || /^adr\/?$/.test(normalised)) return `/adr/${anchor}`

    // The OpenAPI document is published as the rendered API reference, not as a file.
    if (/^(?:docs\/)?api\/openapi\.json$/.test(normalised)) return `/reference/api${anchor}`

    const adr = normalised.match(/^(?:docs\/)?adr\/([^/]+)\.md$/)
    if (adr) return `/adr/${adr[1]}${anchor}`

    // A record refers to a sibling by bare file name, which is only resolvable from inside docs/adr.
    const sibling = fromAdr && normalised.match(/^(\d{4}-[^/]+)\.md$/)
    if (sibling) return `/adr/${sibling[1]}${anchor}`

    const doc = normalised.match(/^(?:docs\/)?([A-Z][A-Z-]*\.md)$/)
    if (doc && GUIDE_PAGES[doc[1]]) return `/${GUIDE_PAGES[doc[1]]}${anchor}`

    // Anything else (a path into the source tree, say) is left for the build's dead-link check.
    return target
  }

  /**
   * The name of the page, for a link that labels itself with a repository path.
   *
   * `[docs/TESTING.md](/guide/testing)` reads correctly in the repository and wrongly on the site,
   * where no such file is served. Only text that is exactly the path is renamed: descriptive wording
   * like `[the testing guide]` is the author's, and `[ADR-0001]` is already the name of the thing.
   */
  const rewriteText = (text, rewrittenTarget) => {
    const name = PAGE_NAMES[rewrittenTarget.replace(/#.*$/, '')]
    if (!name) return text

    const looksLikePath = /^(\.\.\/|\.\/)*(docs\/)?[A-Za-z0-9_.-]+(\.md|\/)?$/.test(text) && /[./]/.test(text)
    return looksLikePath ? name : text
  }

  // Fenced blocks are held aside so a path inside a shell example is never treated as a link.
  const fences = []
  const withoutFences = markdown.replace(/```[\s\S]*?```/g, match => {
    fences.push(match)
    return `\u0000FENCE${fences.length - 1}\u0000`
  })

  const rewritten = withoutFences.replace(
    /\[([^\]]*)\]\(([^)\s]+)\)/g,
    (whole, text, target) => {
      const rewrittenTarget = rewriteTarget(target)
      return `[${rewriteText(text, rewrittenTarget)}](${rewrittenTarget})`
    }
  )

  return rewritten.replace(/\u0000FENCE(\d+)\u0000/g, (_, index) => fences[Number(index)])
}

/** `yes`/`no` reads better in a table than a checkbox nobody can copy. */
const required = property => (property.required ? 'yes' : 'no')

export const ontologyPage = ontology => {
  const lines = [
    generatedFrom('backend/src/main/resources/ontology/v1/ontology.json'),
    '# Ontology reference\n',
    `Every type the graph may contain, as declared by the registry. This page describes ontology`,
    `**v${ontology.version}**. It is generated, so a type added to the registry appears here without`,
    'anyone writing a page for it.\n',
    '## Node types\n'
  ]

  for (const nodeType of ontology.nodeTypes) {
    lines.push(`### ${nodeType.name}\n`)
    if (nodeType.description) lines.push(`${nodeType.description}\n`)
    lines.push(`Identity: \`${nodeType.identity.join(', ')}\`\n`)
    lines.push('| Property | Type | Required | Description |')
    lines.push('| --- | --- | --- | --- |')
    for (const property of nodeType.properties) {
      lines.push(
        `| \`${property.name}\` | \`${property.type}\` | ${required(property)} | ${property.description ?? ''} |`
      )
    }
    lines.push('')
  }

  lines.push('## Relationship types\n')
  lines.push(
    'An edge is stored once and read in both directions: the inverse is a traversal name, not a',
    'second relationship.\n'
  )
  lines.push('| Type | From | To | Inverse | Description |')
  lines.push('| --- | --- | --- | --- | --- |')
  for (const edgeType of ontology.edgeTypes) {
    lines.push(
      `| \`${edgeType.name}\` | ${edgeType.from.join(', ')} | ${edgeType.to.join(', ')} | ` +
        `\`${edgeType.inverse}\` | ${edgeType.description ?? ''} |`
    )
  }
  lines.push('')

  const withProperties = ontology.edgeTypes.filter(edge => edge.properties?.length)
  if (withProperties.length > 0) {
    lines.push('### Relationship properties\n')
    lines.push('| Type | Property | Value | Required |')
    lines.push('| --- | --- | --- | --- |')
    for (const edgeType of withProperties) {
      for (const property of edgeType.properties) {
        lines.push(
          `| \`${edgeType.name}\` | \`${property.name}\` | \`${property.type}\` | ${required(property)} |`
        )
      }
    }
    lines.push('')
  }

  return lines.join('\n')
}

/** Headings are the node type name alone, so the acceptance test can look for a heading per type. */
export const adrIndexPage = records => {
  const lines = [
    generatedFrom('docs/adr/'),
    '# Architecture decisions\n',
    'Why things are the way they are, and what was rejected. Superseding a decision means a new',
    'record, not an edit to the old one.\n',
    '| Number | Decision | Status |',
    '| --- | --- | --- |'
  ]

  for (const record of records) {
    const number = record.file.split('-')[0]
    // The status line often continues "Accepted. Implemented by #17." The decision is the first
    // sentence; the rest is history that belongs on the record itself.
    const decision = record.status.split('.')[0].trim()
    const title = record.title.replace(/^ADR-\d+:\s*/, '')
    lines.push(`| [${number}](./${record.file.replace(/\.md$/, '')}) | ${title} | ${decision} |`)
  }

  lines.push('')
  return lines.join('\n')
}

export const apiPage = openapi => {
  const lines = [
    generatedFrom('docs/api/openapi.json'),
    '# REST API reference\n',
    'Generated from the OpenAPI document the application serves. A running instance offers the same',
    'thing interactively at `/swagger-ui.html`.\n',
    '| Method | Path | Summary | Responses |',
    '| --- | --- | --- | --- |'
  ]

  for (const [route, operations] of Object.entries(openapi.paths)) {
    for (const [method, operation] of Object.entries(operations)) {
      const statuses = Object.keys(operation.responses ?? {}).join(', ')
      lines.push(
        `| \`${method.toUpperCase()}\` | \`${route}\` | ${operation.summary ?? ''} | ${statuses} |`
      )
    }
  }

  lines.push('')
  return lines.join('\n')
}

/** The first non-empty line under `## Status`, which is how every record in docs/adr is written. */
const statusOf = markdown => {
  const section = markdown.split(/^## Status\s*$/m)[1] ?? ''
  return section.trim().split('\n')[0]?.trim() ?? 'Unknown'
}

const titleOf = markdown => markdown.match(/^#\s+(.+)$/m)?.[1]?.trim() ?? 'Untitled'

const readRepoFile = file => readFile(path.join(repoRoot, file), 'utf8')

/**
 * The landing page is the README rendered under a hero. The hero is layout, not content: its copy is
 * the README's own first paragraph and the two documents a new reader is most likely to want, so it
 * is still a function of the sources and the drift check still holds.
 */
export const homePage = readme => {
  const body = readme.replace(/^#\s+SDLC Knowledge Graph\s*\n/, '')
  const frontmatter = [
    '---',
    'layout: home',
    'hero:',
    '  name: SDLC Knowledge Graph',
    '  text: How a commit becomes a running service',
    '  tagline: A queryable graph of repositories, teams, pipelines, artifacts, deployments, environments and cloud resources, with the provenance of every fact.',
    '  image:',
    '    src: /logo.svg',
    '    alt: SDLC Knowledge Graph',
    '  actions:',
    '    - theme: brand',
    '      text: Get started',
    '      link: /guide/getting-started',
    '    - theme: alt',
    '      text: User guide',
    '      link: /guide/user-guide',
    '    - theme: alt',
    '      text: Ontology reference',
    '      link: /reference/ontology',
    'features:',
    '  - title: One model, declared once',
    '    details: Node and relationship types live in a YAML registry. The API, the GraphQL types, the frontend types and this site are generated from it.',
    '    link: /guide/ontology',
    '  - title: Every fact has a source',
    '    details: Each node and edge carries provenance - which system reported it, when, and how confident it was - so a wrong answer can be traced and corrected.',
    '    link: /guide/ontology#provenance',
    '  - title: Built outside-in',
    '    details: Acceptance tests are committed red before the code that makes them pass, and git hooks refuse what cannot be undone.',
    '    link: /guide/testing',
    '---',
    ''
  ].join('\n')
  return frontmatter + generatedFrom('README.md') + rewriteLinks(body, 'README.md')
}

/** Builds the whole site as a map of published path to content, so `--check` needs no temp directory. */
const buildPages = async () => {
  const pages = new Map()

  pages.set('index.md', homePage(await readRepoFile('README.md')))

  for (const [file, published] of Object.entries(GUIDE_PAGES)) {
    const source = `docs/${file}`
    pages.set(`${published}.md`, generatedFrom(source) + rewriteLinks(await readRepoFile(source), source))
  }

  const adrFiles = (await readdir(path.join(repoRoot, 'docs/adr'))).filter(name => name.endsWith('.md')).sort()
  const records = []
  for (const file of adrFiles) {
    const source = `docs/adr/${file}`
    const markdown = await readRepoFile(source)
    records.push({ file, title: titleOf(markdown), status: statusOf(markdown) })
    pages.set(`adr/${file}`, generatedFrom(source) + rewriteLinks(markdown, source))
  }
  pages.set('adr/index.md', adrIndexPage(records))

  const ontology = JSON.parse(await readRepoFile('backend/src/main/resources/ontology/v1/ontology.json'))
  pages.set('reference/ontology.md', ontologyPage(ontology))

  // Required rather than optional. A missing document would silently drop the API reference while
  // leaving the previously committed page in place, which is the one failure this arrangement exists
  // to prevent.
  const openapiPath = path.join(repoRoot, 'docs/api/openapi.json')
  if (!existsSync(openapiPath)) {
    throw new Error(
      'docs/api/openapi.json is missing. Run:\n' +
        '  cd backend && ./gradlew integrationTest --tests "*OpenApiExportTest*" -DupdateOpenApi=true'
    )
  }
  pages.set('reference/api.md', apiPage(JSON.parse(await readFile(openapiPath, 'utf8'))))

  return pages
}

/**
 * Every page currently under src/, so a hand-added one can be spotted. `src/public/` holds the
 * logo and favicon, which are assets rather than pages, and is the one directory left alone.
 */
const publishedFiles = async (dir = outputRoot, prefix = '') => {
  if (!existsSync(dir)) return []
  const found = []
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name
    if (relative === 'public') continue
    if (entry.isDirectory()) found.push(...(await publishedFiles(path.join(dir, entry.name), relative)))
    else found.push(relative)
  }
  return found
}

const write = async pages => {
  for (const [relative, content] of pages) {
    const target = path.join(outputRoot, relative)
    await mkdir(path.dirname(target), { recursive: true })
    await writeFile(target, content, 'utf8')
  }
  console.log(`Generated ${pages.size} pages into website/src/`)
}

const check = async pages => {
  const stale = []
  for (const [relative, content] of pages) {
    const target = path.join(outputRoot, relative)
    const current = existsSync(target) ? await readFile(target, 'utf8') : null
    if (current !== content) stale.push(relative)
  }

  // A page under src/ that generation would not produce was written by hand, which is the other way
  // this site could stop being a faithful render of the repository.
  const unexpected = (await publishedFiles()).filter(relative => !pages.has(relative))

  if (stale.length > 0 || unexpected.length > 0) {
    if (stale.length > 0) {
      console.error('Website pages are stale:')
      for (const relative of stale) console.error(`  website/src/${relative}`)
      console.error('\nRun `npm --prefix website run generate` and commit the result.')
    }
    if (unexpected.length > 0) {
      console.error('Website pages that nothing generates:')
      for (const relative of unexpected) console.error(`  website/src/${relative}`)
      console.error('\nEvery page here is generated. Delete these, or add a generator for them.')
    }
    process.exitCode = 1
    return
  }
  console.log(`All ${pages.size} generated pages are current.`)
}

if (process.argv[1] && import.meta.url === new URL(`file://${process.argv[1].replace(/\\/g, '/')}`).href) {
  const pages = await buildPages()
  await (process.argv.includes('--check') ? check(pages) : write(pages))
}
