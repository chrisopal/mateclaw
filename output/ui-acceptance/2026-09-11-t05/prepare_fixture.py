"""Prepare synthetic T05 data in the existing persistent runtime; never resets storage."""
import sys,json,uuid
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client()
f=OUT/'fixture.json'
if f.exists():
    print(f.read_text());sys.exit()
w=c.call('/workspaces',{'name':'T05 业务规则验收','description':'Synthetic persistent business-policy acceptance'})
c.workspace=str(w['id'])
o=c.call('/semantic/ontologies',{'name':'设备业务规则验收','description':'T05 synthetic sample checks'})
oid=str(o['id']);p='/semantic/ontologies/'+oid
c.call(p+'/draft',{'baseRevisionId':None})
d=c.call(p+'/draft')
changes=[{'kind':'CREATE_TERM','clientId':'equipment','termKind':'OBJECT','name':'生产设备','language':'zh-CN'},
{'kind':'CREATE_TERM','clientId':'code','termKind':'ATTRIBUTE','name':'设备编号','domainId':'$equipment','rangeId':'http://www.w3.org/2001/XMLSchema#string','language':'zh-CN'},
{'kind':'CREATE_TERM','clientId':'power','termKind':'ATTRIBUTE','name':'额定功率','domainId':'$equipment','rangeId':'http://www.w3.org/2001/XMLSchema#decimal','language':'zh-CN'}]
r=c.call(p+'/draft/model-commands',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'changes':changes})
result={'workspaceId':c.workspace,'ontologyId':oid,'items':r['items'],'draftVersion':r['draft']['draftVersion']}
f.write_text(json.dumps(result,ensure_ascii=False,indent=2));print(json.dumps(result,ensure_ascii=False))
