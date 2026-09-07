#!/usr/bin/env bash
set -euo pipefail

# The UI keeps database IDs as strings. Numeric sequence counters and timestamps
# may opt out on the same line with a documented snowflake-precision-ok reason.
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node --input-type=module - "${1:-$script_dir/../mateclaw-ui/src}" <<'NODE'
import { readdir, readFile } from 'node:fs/promises'
import path from 'node:path'

const root = process.argv[2]
const conversions = /\b(?:Number|parseInt|parseFloat)\s*\([^\n)]*\b\w*[Ii]d\b/g
let failures = 0
async function scan(dir) {
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name)
    if (entry.isDirectory()) await scan(file)
    else if (/\.(?:ts|tsx|vue)$/.test(entry.name)) {
      const lines = (await readFile(file, 'utf8')).split('\n')
      lines.forEach((line, index) => {
        conversions.lastIndex = 0
        if (!/^\s*(?:\/\/|\*)/.test(line) && conversions.test(line)
            && !/snowflake-precision-ok:\s*\S/.test(line)) {
          console.error(`${file}:${index + 1}: database IDs must stay strings; inspect numeric conversion`)
          failures++
        }
      })
    }
  }
}
await scan(root)
if (failures) process.exitCode = 1
else console.log('Snowflake numeric-conversion check passed')
NODE
