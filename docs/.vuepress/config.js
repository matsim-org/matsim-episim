import { defaultTheme } from '@vuepress/theme-default'
import { defineUserConfig } from 'vuepress'
import { viteBundler } from '@vuepress/bundler-vite'
import { markdownExtPlugin } from '@vuepress/plugin-markdown-ext'

import { createRequire } from 'module'
const require = createRequire(import.meta.url)
const { searchPlugin } = require('@vuepress/plugin-search')

export default defineUserConfig({

  base: "/matsim-episim/",

  lang: 'en-US',

  title: 'Episim Documentation',
  description: 'Documentation for running and understanding MATSim with Episim',

  theme: defaultTheme({
    logo: 'https://www.ivt.ethz.ch/forschung/matsim/_jcr_content/par/fullwidthimage/image.imageformat.1286.2026935405.png',

    navbar: [
      { text: 'Home', link: '/' },
      { text: 'Documentation', link: '/documentation/introduction.html' },
    ],


    sidebar: {
      '/documentation/': [
        {
          text: 'Documentation',
          collapsible: false,
          children: [
            '/documentation/introduction.md',
            '/documentation/quickstart.md',
            '/documentation/repositorystructure.md',
            '/documentation/simulationsetup.md',
            '/documentation/episimsimulation.md',
            '/documentation/outputandanalysis.md',

            // '/guide/run-setup.md',
            // '/guide/configuration.md',
            // '/guide/plots.md',
            // '/guide/example.md',
            // '/guide/website.md',
          ],
        },
      ],
    },
  }),

  bundler: viteBundler(),

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
