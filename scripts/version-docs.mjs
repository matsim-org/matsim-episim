// I *think* that this script is there to auto-generate the documentation for a following year.  kai, jun'26

import fs from 'fs'
import path from 'path'
import process from 'process'

const rootDir = process.cwd()
const docsDir = path.join(rootDir, 'docs')
const versionsDir = path.join(docsDir, 'versions')

const version = process.argv[2]
const sourceVersionArg = process.argv[3]

if (!version) {
  console.error('Usage: npm run version-docs -- <new-version> [source-version]')
  process.exit(1)
}

if (!/^v?[0-9]+(\.[0-9]+)*([-.][A-Za-z0-9]+)*$/.test(version)) {
  console.error(`Invalid version string: "${version}"`)
  process.exit(1)
}

const targetVersionDir = path.join(versionsDir, version)
const targetDocDir = path.join(targetVersionDir, 'documentation')
const targetHomeFile = path.join(targetVersionDir, 'README.md')
const targetImagesDir = path.join(targetVersionDir, 'images')
const rootHomeFile = path.join(docsDir, 'README.md')
const disclaimerMarker = '<!-- AUTO-GENERATED-VERSION-DISCLAIMER -->'

const collectMarkdownFiles = (dir) => {
  if (!fs.existsSync(dir)) {
    return []
  }

  const entries = fs.readdirSync(dir, { withFileTypes: true })
  const files = []

  for (const entry of entries) {
    const fullPath = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      files.push(...collectMarkdownFiles(fullPath))
      continue
    }
    if (entry.isFile() && fullPath.endsWith('.md')) {
      files.push(fullPath)
    }
  }

  return files
}

const addDisclaimer = (filePath, newVersion, fromVersion) => {
  const content = fs.readFileSync(filePath, 'utf8')
  if (content.includes(disclaimerMarker)) {
    return
  }

  const currentDate = new Date().toISOString().slice(0, 10)
  const disclaimer = [
    disclaimerMarker,
    '> **Auto-generated version notice**',
    `> This file was generated automatically for version \`${newVersion}\` from \`${fromVersion}\` on ${currentDate}.`,
    '> Please review and adjust the content, then remove this notice manually.',
    '',
  ].join('\n')

  // Preserve frontmatter at the top of the file, then inject the notice.
  if (content.startsWith('---\n')) {
    const closingIndex = content.indexOf('\n---\n', 4)
    if (closingIndex !== -1) {
      const frontmatterEnd = closingIndex + '\n---\n'.length
      const updated =
        content.slice(0, frontmatterEnd) +
        '\n' +
        disclaimer +
        content.slice(frontmatterEnd)
      fs.writeFileSync(filePath, updated)
      return
    }
  }

  fs.writeFileSync(filePath, `${disclaimer}${content}`)
}

if (!fs.existsSync(versionsDir)) {
  console.error(`Missing versions directory: ${versionsDir}`)
  process.exit(1)
}

if (fs.existsSync(targetVersionDir)) {
  console.error(`Version already exists: ${version}`)
  process.exit(1)
}

const existingVersions = fs
  .readdirSync(versionsDir, { withFileTypes: true })
  .filter((entry) => entry.isDirectory())
  .map((entry) => entry.name)
  .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))

const sourceVersion = sourceVersionArg || existingVersions[existingVersions.length - 1]
if (!sourceVersion) {
  console.error('No source version found. Create the first version manually under docs/versions/<version>.')
  process.exit(1)
}

const sourceVersionDir = path.join(versionsDir, sourceVersion)
const sourceDocDir = path.join(sourceVersionDir, 'documentation')
const sourceHomeFile = path.join(sourceVersionDir, 'README.md')
const sourceImagesDir = path.join(sourceVersionDir, 'images')

if (!fs.existsSync(sourceDocDir)) {
  console.error(`Missing source documentation directory: ${sourceDocDir}`)
  process.exit(1)
}

fs.mkdirSync(targetVersionDir, { recursive: true })
fs.cpSync(sourceDocDir, targetDocDir, { recursive: true })
if (fs.existsSync(sourceImagesDir)) {
  fs.cpSync(sourceImagesDir, targetImagesDir, { recursive: true })
}

if (fs.existsSync(sourceHomeFile)) {
  let content = fs.readFileSync(sourceHomeFile, 'utf8')

  content = content
    .replaceAll(
      `link: /versions/${sourceVersion}/documentation/`,
      `link: /versions/${version}/documentation/`,
    )
    .replaceAll(
      `](/versions/${sourceVersion}/documentation/`,
      `](/versions/${version}/documentation/`,
    )

  fs.writeFileSync(targetHomeFile, content)
}

for (const markdownFile of [...collectMarkdownFiles(targetDocDir), targetHomeFile]) {
  if (fs.existsSync(markdownFile)) {
    addDisclaimer(markdownFile, version, sourceVersion)
  }
}

if (fs.existsSync(rootHomeFile)) {
  let rootContent = fs.readFileSync(rootHomeFile, 'utf8')
  rootContent = rootContent
    .replace(/link:\s*\/versions\/[^/]+\/documentation\//g, `link: /versions/${version}/documentation/`)
    .replace(/\]\(\/versions\/[^/]+\/documentation\//g, `](/versions/${version}/documentation/`)
  fs.writeFileSync(rootHomeFile, rootContent)
}

console.log(
  `Created documentation version ${version} from ${sourceVersion} in docs/versions/${version}`,
)
