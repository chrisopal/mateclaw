from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
from urllib.parse import quote
import json
from fixture_api import Isolation,FixtureSession
b=Path(__file__).resolve().parent;r=Path((b/'runtime-path.txt').read_text());state=json.loads((r/'state.json').read_text());fixture=json.loads((b/'evidence/runtime-fixture.json').read_text())
s=FixtureSession(Isolation(r,state['backend_url'],Path('/Users/guojiexie/.codex/worktrees/architecture-quality/mateclaw'),b/'runtime-path.txt'));s._load_saved_state();s._login_admin();s._ensure_saved_workspace()
def request(path,token,method='GET',body=None):
 headers={'Authorization':'Bearer '+token,'X-Workspace-Id':s.workspace_id};data=None
 if body is not None:data=json.dumps(body).encode();headers['Content-Type']='application/json'
 req=Request(state['backend_url']+'/api/v1'+path,data=data,headers=headers,method=method)
 try:
  with urlopen(req,timeout=15) as resp:raw=resp.read();status=resp.status
 except HTTPError as error:raw=error.read();status=error.code
 try:value=json.loads(raw);code=value.get('code',status)
 except ValueError:value=raw.decode();code=status
 return code,value
project=s.api.request('/presales/projects/'+fixture['projectId'],token=s.admin.token)
cid=project['tasks'][-1]['conversationId'];encoded=quote(cid,safe='');prefix='/conversations/'+encoded
proof={'conversationId':cid,'sourceTree':state['source_tree'],'checks':[]}
tokens={'admin':s.admin.token,'viewer':s.api.login(s.synthetic_credentials('viewer')).token}
for role,token in tokens.items():
 for suffix in ['/messages','/trajectory','/status']:
  code,body=request(prefix+suffix,token);assert code==200,(role,suffix,code)
  if suffix=='/messages':assert len(body['data'])==2 and any('needsHumanReview' in m['content'] for m in body['data'])
  if suffix=='/status':assert body['data']['readOnly']=='true'
  proof['checks'].append({'role':role,'path':suffix,'code':code})
for method,path,body in [('POST','/agents/'+fixture['employeeId']+'/chat',{'conversationId':cid,'message':'Should not append'}),('POST','/agents/'+fixture['employeeId']+'/execute',{'conversationId':cid,'message':'Should not append'}),('PUT',prefix+'/title',{'title':'Should not rename'}),('DELETE',prefix+'/messages',None),('POST','/chat?agentId='+fixture['employeeId'],{'conversationId':cid,'message':'Should not append'}),('POST','/chat/'+encoded+'/stop',None),('POST','/chat/'+encoded+'/interrupt',{'message':'Should not queue','agentId':fixture['employeeId']})]:
 code,_=request(path,s.admin.token,method,body);assert code==403,(method,path,code);proof['checks'].append({'method':method,'path':path,'code':code})
code,body=request(prefix+'/messages',s.admin.token);assert code==200 and len(body['data'])==2
(b/'evidence/transcript-http.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof,indent=2))
