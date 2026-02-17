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
