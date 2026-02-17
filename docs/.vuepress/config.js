import { defaultTheme } from '@vuepress/theme-default'
import { defineUserConfig } from 'vuepress'
import { viteBundler } from '@vuepress/bundler-vite'
import { markdownExtPlugin } from '@vuepress/plugin-markdown-ext'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

import { createRequire } from 'module'
const require = createRequire(import.meta.url)
const { searchPlugin } = require('@vuepress/plugin-search')

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const docsRoot = path.resolve(__dirname, '..')
const versionsRoot = path.resolve(docsRoot, 'versions')
const currentDocumentationRoot = path.resolve(docsRoot, 'documentation')

const versionDirectories = fs.existsSync(versionsRoot)
  ? fs
      .readdirSync(versionsRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory())
      .map((entry) => entry.name)
      .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
  : []

const hasCurrentDocumentation = fs.existsSync(currentDocumentationRoot)
const latestVersion = versionDirectories.length
  ? versionDirectories[versionDirectories.length - 1]
  : null
const latestDocumentationLink = latestVersion
  ? `/versions/${latestVersion}/documentation/introduction.html`
  : hasCurrentDocumentation
    ? '/documentation/introduction.html'
    : '/'

const documentationChildren = [
  '/documentation/introduction.md',
  '/documentation/quickstart.md',
  '/documentation/repositorystructure.md',
  '/documentation/simulationsetup.md',
  '/documentation/episimsimulation.md',
  '/documentation/outputandanalysis.md',
]

const sidebar = {}

if (hasCurrentDocumentation) {
  sidebar['/documentation/'] = [
    {
      text: 'Documentation',
      collapsible: false,
      children: documentationChildren,
    },
  ]
}

for (const version of versionDirectories) {
  sidebar[`/versions/${version}/documentation/`] = [
    {
      text: `Documentation (${version})`,
      collapsible: false,
      children: documentationChildren.map(
        (item) => `/versions/${version}${item}`,
      ),
    },
  ]
}

const versionsNavbarChildren = versionDirectories
  .slice()
  .reverse()
  .map((version) => ({
    text: version,
    link: `/versions/${version}/documentation/introduction.html`,
  }))

export default defineUserConfig({

  base: "/matsim-episim/",

  lang: 'en-US',

  title: 'Episim Documentation',
  description: 'Documentation for running and understanding MATSim with Episim',

  theme: defaultTheme({
    logo: 'https://www.ivt.ethz.ch/forschung/matsim/_jcr_content/par/fullwidthimage/image.imageformat.1286.2026935405.png',

    navbar: [
      { text: 'Home', link: '/' },
      { text: 'Documentation', link: latestDocumentationLink },
      { text: 'Versions', children: versionsNavbarChildren },
    ],


    sidebar,
  }),

  bundler: viteBundler({
    viteOptions: {
      plugins: [
        {
          name: 'redirect-base-without-trailing-slash',
          configureServer(server) {
            server.middlewares.use((req, res, next) => {
              if (req.url === '/matsim-episim') {
                res.statusCode = 301
                res.setHeader('Location', '/matsim-episim/')
                res.end()
                return
              }
              next()
            })
          },
        },
      ],
    },
  }),

  plugins: [
    markdownExtPlugin({
      footnote: true,
    }),

    searchPlugin({
      locales: {
        '/': {
          placeholder: 'Search...',
        },
      },
    }),
  ],
})
