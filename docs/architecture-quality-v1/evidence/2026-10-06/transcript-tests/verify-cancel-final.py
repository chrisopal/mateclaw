from pathlib import Path
import json,time
from fixture_api import Isolation,FixtureSession
b=Path(__file__).resolve().parent;r=Path((b/'runtime-path.txt').read_text());state=json.loads((r/'state.json').read_text());f=json.loads((b/'evidence/runtime-fixture.json').read_text());s=FixtureSession(Isolation(r,state['backend_url'],Path('/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw'),b/'runtime-path.txt'));s._load_saved_state();s._login_admin();s._ensure_saved_workspace()
case=(b/'evidence/runtime-start-held.log').read_text();cancel=json.loads(case.split('### Result\n')[1].split('\n### Ran')[0]);tid=cancel['task']['id']
for _ in range(20):
 p=s.api.request('/presales/projects/'+f['projectId'],token=s.admin.token);t=next(t for t in p['tasks'] if t['id']==tid);assert t['status']=='CANCELLED' and 'result' not in t;assert len(p['contextCards'])==cancel['contextCount'];m=s.api.request('/conversations/'+t['conversationId']+'/messages',token=s.admin.token)
 if len(m)==2:break
 time.sleep(.25)
assert len(m)==2 and m[-1]['status']=='interrupted',[(v['role'],v['status']) for v in m]
proof={'sourceTree':state['source_tree'],'taskId':tid,'taskStatus':t['status'],'resultPresent':False,'contextCount':len(p['contextCards']),'messages':[{'role':v['role'],'status':v['status'],'contentLength':len(v['content'] or '')} for v in m],'observation':'Actual GET after releasing local held provider; actual late output retained as interrupted evidence'}
(b/'evidence/cancel-readback-final.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))
