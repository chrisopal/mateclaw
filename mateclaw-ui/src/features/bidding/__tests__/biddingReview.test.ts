import { expect, it } from 'vitest'
import { canApproveArtifact } from '../shared/state'
import { afterEach, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { createI18n } from 'vue-i18n'
import ElementPlus from 'element-plus'
import en from '@/i18n/locales/en-US'
import BiddingReview from '../components/BiddingReview.vue'
import type { BiddingReviewFinding, BiddingReviewView, Ref } from '../api/types'

let app:App|undefined,host:HTMLElement|undefined
const flush=async()=>{await new Promise(resolve=>setTimeout(resolve,0));await nextTick()}
afterEach(()=>{app?.unmount();host?.remove();app=undefined;host=undefined;vi.clearAllMocks()})

it('allows approval only for an authorized, unblocked candidate', () => {
  expect(canApproveArtifact('preview', 'CANDIDATE', 0, true)).toBe(false)
  expect(canApproveArtifact('candidate', 'PREVIEW', 0, true)).toBe(false)
  expect(canApproveArtifact('candidate', 'CANDIDATE', 1, true)).toBe(false)
  expect(canApproveArtifact('candidate', 'CANDIDATE', 0, false)).toBe(false)
  expect(canApproveArtifact('candidate', 'CANDIDATE', 0, true)).toBe(true)
})

it('filters findings by severity and chapter and withholds defer on non-suggestions',async()=>{
  const ref=(kind:string,id:string):Ref=>({kind,id,version:1,digest:id})
  const view:BiddingReviewView={status:'REVIEWED',manuscriptRef:ref('manuscript','ms'),tasks:[],humanTodos:[],findings:[
    {reviewRef:ref('review','r1'),findingRef:ref('reviewFinding','f1'),decision:'OPEN',finding:{id:'f1',severity:'CRITICAL',category:'MANDATORY',description:'Missing signed certificate',chapterRefs:[ref('chapter','c1')]}},
    {reviewRef:ref('review','r1'),findingRef:ref('reviewFinding','f3'),decision:'OPEN',finding:{id:'f3',severity:'BLOCKER',category:'UNANSWERED_TECHNICAL_REQUIREMENT',description:'Unanswered requirement',chapterRefs:[ref('chapter','c1')]}},
    {reviewRef:ref('review','r1'),findingRef:ref('reviewFinding','f2'),decision:'OPEN',finding:{id:'f2',severity:'SUGGESTION',category:'STYLE_SUGGESTION',description:'Consider a shorter introduction',chapterRefs:[ref('chapter','c2')]}}
  ]}
  const defer=vi.fn(),fix=vi.fn()
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingReview,{view,chapters:[{id:'c1',title:'Compliance'},{id:'c2',title:'Introduction'}],canWrite:true,canApprove:true,canResolveTodo:false,reviewerReady:false,busy:false,onResolve:(_item:BiddingReviewFinding,decision:'DISMISS_WITH_EVIDENCE'|'DEFER_SUGGESTION')=>{if(decision==='DEFER_SUGGESTION')defer()},onFix:fix})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  const runReview=[...host.querySelectorAll('button')].find(button=>button.textContent?.includes('Run review')) as HTMLButtonElement
  expect(runReview.disabled).toBe(true)
  expect(host.textContent).toContain('Bind a reviewer who is distinct from the writer first.')
  const cards=[...host.querySelectorAll('.finding-card')]
  expect(cards).toHaveLength(3)
  expect(cards[0]?.textContent).toContain('Missing signed certificate')
  expect(cards[0]?.textContent).not.toContain('Defer suggestion')
  expect(cards[1]?.textContent).not.toContain('Defer suggestion')
  ;(host.querySelectorAll('.filters .el-select input')[1] as HTMLInputElement).click();await flush()
  const option=[...document.querySelectorAll('.el-select-dropdown__item')].find(item=>item.textContent?.includes('Introduction')) as HTMLElement
  option?.click();await flush()
  expect(host.querySelectorAll('.finding-card')).toHaveLength(1)
  expect(host.querySelector('.finding-card')?.textContent).toContain('Consider a shorter introduction')
  ;([...host.querySelectorAll('.finding-card button')].find(button=>button.textContent?.includes('Defer suggestion')) as HTMLButtonElement).click();await flush()
  expect(defer).toHaveBeenCalledOnce()
})

it('does not expose todo closure to a writer who is neither its owner nor approver',async()=>{
  const ref=(kind:string,id:string):Ref=>({kind,id,version:1,digest:id})
  const view:BiddingReviewView={status:'REVIEWED',manuscriptRef:ref('manuscript','ms'),tasks:[],findings:[],humanTodos:[{ref:ref('HUMAN_TODO','todo-1'),title:'Confirm commercial warranty',status:'OPEN',impactClassification:'CLASSIFIED',affectsTechnical:false}]}
  host=document.createElement('div');document.body.append(host)
  app=createApp(BiddingReview,{view,chapters:[],canWrite:true,canApprove:false,canResolveTodo:false,reviewerReady:false,busy:false})
  app.use(ElementPlus).use(createI18n({legacy:false,locale:'en-US',messages:{'en-US':en}})).mount(host);await flush()
  expect(host.textContent).toContain('Business only')
  expect([...host.querySelectorAll('button')].some(button=>button.textContent?.includes('Resolve item'))).toBe(false)
})
