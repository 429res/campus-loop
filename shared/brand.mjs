import { copyFileSync, mkdirSync } from 'node:fs'

// Both builders copy the same brand assets into their platform's static directory.
export function syncBrandAssets(directory) {
  mkdirSync(directory, { recursive: true })
  for (const name of ['brand-mark.svg', 'brand-mark.png', 'campus-courtyard.svg', 'campus-courtyard.png']) {
    copyFileSync(new URL(name, import.meta.url), new URL(name, directory))
  }
}
