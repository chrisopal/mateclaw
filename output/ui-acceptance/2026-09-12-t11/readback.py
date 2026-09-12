"""Read persisted T11 lifecycle and migration state; optionally exercise rejected rollback."""
import json,sys,uuid
from pathlib import Path
from urllib.error import HTTPError
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();f=json.loads((OUT/'fixture.json').read_text());c.workspace=f['workspaceId'];p='/semantic/ontologies/'+f['ontologyId']
o=c.call(p); revs=c.call(p+'/revisions'); apps=[c.call('/semantic/knowledge-bases/'+a['kbId']+'/binding') for a in f['applications']]
assert [a['ontologyVersion'] for a in apps]==[3,1]
planId='25942cb6-aa77-4c4c-a5e5-12dbf6292340'; m='/semantic/graphs/'+apps[0]['graphId']+'/migrations/owl/'+planId
plan=c.call(m); assert plan['status']=='EXECUTED'
code=None
if '--reject-rollback' in sys.argv:
 try:c.call(m+'/rollback',{'operationId':str(uuid.uuid4()),'expectedPlanDigest':plan['planDigest'],'expectedGraphVersion':apps[0]['graphVersion']})
 except HTTPError as e:
  result=json.load(e);code=result['data']['code']; assert e.code==409
 else:raise AssertionError('Unsafe rollback was accepted')
assert c.call(p+'/draft')['id']==f['draftId']
for old in f['revisions']:
 current=c.call(p+'/revisions/'+old['id']);assert current['document']['documentDigest']==old['document']['documentDigest']
result={'archived':o['archived'],'versions':[{'id':r['id'],'version':r['version'],'available':r['availableForNewBindings']} for r in revs],'applications':apps,'plan':plan,'unsafeRollbackCode':code,'publishedDigestsUnchanged':True,'draftRetained':True}
h=json.loads((OUT/'history.json').read_text())
base='/semantic/graphs/'+h['graphId']
history=c.call(base+'/statements/'+h['statementId']+'/revisions')
evidence=c.call(base+'/evidence/'+h['evidenceId'])
assert len(history['revisions'])==2
assert evidence['exactQuote']=='维修车间设备A额定电压380V。'
result.update({'historyRevisions':len(history['revisions']),'evidenceQuote':evidence['exactQuote']})
(OUT/('post-restart.json' if '--post-restart' in sys.argv else 'readback-'+('archived' if o['archived'] else 'restored')+'.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2));print({k:result[k] for k in ['archived','unsafeRollbackCode','publishedDigestsUnchanged','draftRetained']})
