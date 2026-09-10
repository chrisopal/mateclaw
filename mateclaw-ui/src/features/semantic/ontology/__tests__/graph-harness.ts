// Isolated browser fixture, not a product route. All data is synthetic, no API access.
import { createApp, h, nextTick, ref } from 'vue'
import ElementPlus from 'element-plus'
import { createI18n } from 'vue-i18n'
import 'element-plus/dist/index.css'
import Workbench from '../components/OntologyModelWorkbench.vue'
import type { AxiomDescriptor } from '../../api/types'
const fixture = (count: number): AxiomDescriptor[] => {
  const rows: AxiomDescriptor[] = []
  const add = (rendering: string, iris: string[]) => rows.push({ axiomId: String(rows.length), rendering, signatureIris: iris, axiomType: rendering.split('(')[0]!, annotations: [], logical: true })
  for (let i = 0; i < count; i++) {
    add(`Declaration(Class(<urn:bench:N${i}>))`, [`urn:bench:N${i}`])
    if (i) add(`SubClassOf(<urn:bench:N${i}> <urn:bench:N${Math.floor((i - 1) / 2)}>)`, [`urn:bench:N${i}`, `urn:bench:N${Math.floor((i - 1) / 2)}`])
    if (i > 2) add(`SubClassOf(<urn:bench:N${i}> <urn:bench:N${Math.floor((i - 2) / 3)}>)`, [`urn:bench:N${i}`, `urn:bench:N${Math.floor((i - 2) / 3)}`])
  }
  return rows
}
createApp({ setup() {
  const count = ref(50); const mounted = ref(true); const two = ref(false); const generation = ref(0); const elapsed = ref(0)
  const rows = ref(fixture(50))
  async function change(event: Event) {
    const start = performance.now(); count.value = Number((event.target as HTMLSelectElement).value); rows.value = fixture(count.value); generation.value++
    await nextTick(); await new Promise(requestAnimationFrame); await new Promise(requestAnimationFrame); elapsed.value = performance.now() - start
  }
  return () => h('main', { style: 'font-family:Arial;padding:16px;--mc-border:#d9dee7;--mc-bg-elevated:#fff;--mc-bg-base:#fff;--mc-text-primary:#162238;--mc-text-secondary:#526480' }, [
    h('label', ['Synthetic nodes ', h('select', { 'aria-label': 'Synthetic node count', value: count.value, onChange: change }, [50, 200, 500, 550].map(n => h('option', { value: n }, String(n))))]),
    h('button', { onClick: () => { mounted.value = !mounted.value } }, 'Mount / unmount'),
    h('button', { onClick: () => { two.value = !two.value } }, 'Two instances'),
    h('output', { id: 'timing' }, `${Math.round(elapsed.value)}ms`),
    mounted.value && h(Workbench, { key: generation.value, axioms: rows.value }),
    two.value && h(Workbench, { axioms: fixture(7) }),
  ])
} }).use(ElementPlus).use(createI18n({ legacy: false, locale: 'zh-CN', messages: {} })).mount('#app')
