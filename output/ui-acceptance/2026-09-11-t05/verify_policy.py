"""Real API acceptance against the persistent QA fixture, after UI rule editing."""
import sys,json
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client(); f=json.loads((OUT/'fixture.json').read_text());c.workspace=f['workspaceId']
p='/semantic/ontologies/'+f['ontologyId'];d=c.call(p+'/draft')
ids={x['clientId']:x['targetId'] for x in f['items']}
before=c.call(p+'/bindings')
def check(properties,complete=True):
 return c.call(p+'/draft/check-sample',{'expectedDraftVersion':d['draftVersion'],'classIri':ids['equipment'],'completeSubmission':complete,'properties':properties})
def literal(value,kind='string',unit=None):return {'lexicalValue':value,'datatypeIri':'http://www.w3.org/2001/XMLSchema#'+kind,'unit':unit}
results={}
results['missing']=check({})
assert any(v['code']=='BUSINESS_REQUIRED' for v in results['missing']['violations'])
results['partial']=check({},False);assert results['partial']['valid']
results['valid']=check({ids['code']:[literal('EQ-001')],ids['power']:[literal('10','decimal','kW')]});assert results['valid']['valid']
results['unit']=check({ids['code']:[literal('EQ-001')],ids['power']:[literal('10','decimal','W')]});assert any(v['code']=='BUSINESS_UNIT_MISMATCH' for v in results['unit']['violations'])
results['enum']=check({ids['code']:[literal('INVALID')],ids['power']:[literal('10','decimal','kW')]});assert any(v['code']=='BUSINESS_VALUE_NOT_ALLOWED' for v in results['enum']['violations'])
results['single']=check({ids['code']:[literal('EQ-001'),literal('EQ-002')],ids['power']:[literal('10','decimal','kW')]});assert any(v['code']=='BUSINESS_SINGLE_VALUE' for v in results['single']['violations'])
assert c.call(p+'/draft')==d,'Read-only checks changed draft'
assert c.call(p+'/bindings')==before,'Read-only checks changed graph bindings'
result={'draftVersion':d['draftVersion'],'policy':d['document']['source']['policy'],'cases':results,'draftUnchanged':True,'bindingsUnchanged':True}
(OUT/'api-acceptance.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print('PASS: 6 business-policy cases; draft and bindings unchanged')
