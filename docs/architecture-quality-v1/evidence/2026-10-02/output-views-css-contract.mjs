import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'

const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim()
const requireUi = createRequire(path.join(root, 'mateclaw-ui/package.json'))
const requireVite = createRequire(requireUi.resolve('vite/package.json'))
const postcss = requireVite('postcss')
const { parse, compileStyle } = requireUi('vue/compiler-sfc')
const page = 'mateclaw-ui/src/features/presales/pages/PresalesWorkbench.vue'
const base = 'cc9596b91932029145b80b0f433b135f23f58351'
const before = parse(execFileSync('git', ['show', `${base}:${page}`], { cwd: root, encoding: 'utf8' })).descriptor.styles[0].content
const afterPage = parse(fs.readFileSync(path.join(root, page), 'utf8')).descriptor
const common = 'mateclaw-ui/src/features/presales/shared/workbenchSections.css'
const solution = 'mateclaw-ui/src/features/presales/shared/solutionSections.css'
const css = [afterPage.styles[0].content, fs.readFileSync(path.join(root, common), 'utf8'), fs.readFileSync(path.join(root, solution), 'utf8')]
function contracts(source) {
  const result = []
  postcss.parse(source).walkRules(rule => {
    const context = []
    let parent = rule.parent
    while (parent && parent.type !== 'root') {
      if (parent.type === 'atrule') context.unshift([parent.name, parent.params])
      parent = parent.parent
    }
    for (const selector of rule.selectors) {
      result.push(JSON.stringify([context, selector, rule.nodes.filter(node => node.type === 'decl').map(node => [node.prop, node.value, node.important || false])]))
    }
  })
  return result.sort()
}
const original = contracts(before)
const partitioned = css.flatMap(contracts).sort()
if (JSON.stringify(original) !== JSON.stringify(partitioned)) throw Error('Selector, declaration or media condition changed')
for (const name of ['PresalesWorkbench', 'PresalesSolutions', 'PresalesOutputs']) {
  const file = name === 'PresalesWorkbench' ? page : `mateclaw-ui/src/features/presales/components/${name}.vue`
  const descriptor = parse(fs.readFileSync(path.join(root, file), 'utf8')).descriptor
  if (!descriptor.styles.some(style => style.scoped && style.src === '../shared/workbenchSections.css')) throw Error(`Missing scoped common styles: ${name}`)
  for (const style of descriptor.styles) {
    const source = style.src ? fs.readFileSync(path.resolve(root, path.dirname(file), style.src), 'utf8') : style.content
    const compiled = compileStyle({ source, filename: file, id: 'data-v-contract', scoped: style.scoped })
    if (compiled.errors.length || !compiled.code.includes('[data-v-contract]')) throw Error(`Scoped style compilation failed: ${name}`)
  }
}
console.log(JSON.stringify({ status: 'PASS', base, selector_declaration_media_contracts: original.length, scoped_component_compilations: 3, visual_qa: 'NOT_RUN' }))
