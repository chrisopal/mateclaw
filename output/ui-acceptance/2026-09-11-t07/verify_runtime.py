"""T07 persistent DB checks. No credentials are written or printed."""
import json,sys,uuid
from pathlib import Path
from urllib.error import HTTPError
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();f=json.loads((OUT/'fixture.json').read_text());c.workspace=f['workspaceId'];results={}
def rejected(path,body):
 try: c.call(path,body)
 except HTTPError as e:
  value=json.loads(e.read());assert e.code in (409,422),value
  return {'status':e.code,'code':value.get('data',{}).get('code')}
 raise AssertionError('Publication unexpectedly allowed')
for key,item in f['cases'].items():
 p='/semantic/ontologies/'+item['ontologyId'];d=c.call(p+'/draft')
 blocked=rejected(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'note':'must not publish before full passing report'}) if key!='consistent' else None
 report=c.call(p+'/draft/validate',{'expectedDraftVersion':d['draftVersion']})
 assert bool(report['valid'])==(key=='consistent'),report
 assert len(report['checks'])==4 and report['reportId'] and report['inputDigest'],report
 latest=c.call(p+'/draft/validation');assert latest['reportId']==report['reportId'] and not latest['stale'],latest
 assert c.call(p+'/draft')==d,'Checking changed draft'
 if key!='consistent':blocked=rejected(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'note':'reject invalid model'})
 results[key]={'report':report,'publishRejected':blocked}
# Separate publication fixture leaves the browser fixture draft intact.
p='/semantic/ontologies/'+str(c.call('/semantic/ontologies',{'name':'T07 API 发布回读','description':'Synthetic publication gate and retry'})['id'])
d=c.call(p+'/draft',{'baseRevisionId':None});template=c.call('/semantic/ontologies/'+f['cases']['consistent']['ontologyId']+'/draft')
d=c.call(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'name':'T07 API 发布回读','description':'Synthetic','document':template['document']['source']},'PUT')
body={'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'note':'T07 API verified'}
results['noReport']=rejected(p+'/draft/publish',body)
report=c.call(p+'/draft/validate',{'expectedDraftVersion':d['draftVersion']});assert report['valid']
d=c.call(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'name':'T07 API 发布回读 changed','description':'Changed after validation','document':d['document']['source']},'PUT')
assert c.call(p+'/draft/validation')['stale']
body['expectedDraftVersion']=d['draftVersion'];results['stale']=rejected(p+'/draft/publish',body)
assert c.call(p+'/draft/validate',{'expectedDraftVersion':d['draftVersion']})['valid']
a=c.call(p+'/draft/publish',body);b=c.call(p+'/draft/publish',body);assert a==b
assert any(r['id']==a['id'] for r in c.call(p+'/revisions'))
results['publication']={'ontologyId':a['ontologyId'],'revisionId':a['id'],'idempotent':True}
(OUT/'runtime-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print('PASS: persisted reports, strict gate, stale input rejection and publication retry')
