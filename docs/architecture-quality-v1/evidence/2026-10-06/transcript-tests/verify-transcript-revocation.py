from pathlib import Path
import json
from urllib.parse import quote
# Reuse guarded fixture and raw HTTP helper, while retaining its independent positive checks.
exec((Path(__file__).resolve().parent/'verify-transcript-http.py').read_text())
project=s.api.request('/presales/projects/'+fixture['projectId'],token=s.admin.token)
task=next(t for t in project['tasks'] if t['contextSnapshot'].get('sources'))
assert task['status']=='SUCCEEDED'
prefix='/conversations/'+quote(task['conversationId'],safe='')
agent=s.api.request('/agents/'+fixture['employeeId'],token=s.admin.token);original=dict(agent)
proof={'conversationId':task['conversationId'],'snapshotSourceCount':len(task['contextSnapshot']['sources']),'sourceTree':state['source_tree'],'checks':[]}
for suffix in ['/messages','/trajectory','/status']:
 code,_=request(prefix+suffix,s.admin.token);assert code==200
try:
 agent['wikiDisabled']=True;s.api.request('/agents/'+fixture['employeeId'],method='PUT',token=s.admin.token,body=agent)
 assert s.api.request('/agents/'+fixture['employeeId'],token=s.admin.token)['wikiDisabled'] is True
 for role,token in tokens.items():
  for suffix in ['/messages','/trajectory','/status']:
   code,body=request(prefix+suffix,token);assert code==403,(role,suffix,code)
   assert 'AC30 本地模拟模型候选' not in json.dumps(body,ensure_ascii=False)
   proof['checks'].append({'role':role,'path':suffix,'code':code,'candidateLeaked':False})
finally:
 s.api.request('/agents/'+fixture['employeeId'],method='PUT',token=s.admin.token,body=original)
for suffix in ['/messages','/trajectory','/status']:
 code,_=request(prefix+suffix,s.admin.token);assert code==200
proof['restoredRead']=True;(b/'evidence/transcript-revocation.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof,indent=2))
