"""Verify UI-created T10 changes, replay safety and persisted history; read-only by default.
--replay additionally retries the identical existing bridge operations (no new records).
"""
import json,sys
from pathlib import Path
OUT=Path(__file__).resolve().parent;sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
f=json.loads((OUT/'fixture.json').read_text());c=Client();c.workspace=f['workspaceId'];p='/semantic/ontologies/'+f['ontologyId'];base='/semantic/graphs/'+f['graphId'];checks={}
published=c.call(p+'/revisions/'+f['revisionId']);assert published==f['publishedBefore'];checks['published_unchanged']=True
draft=c.call(p+'/draft');assert '生产设备' in draft['document']['source']['documentText'];checks['draft_only_updated']=True
task=c.call('/semantic/modeling-tasks/'+f['taskId']);assert len(task['proposals'])==1 and task['proposals'][0]['status']=='ACCEPTED';checks['one_accepted_model_proposal']=True
history=c.call(base+'/statements/'+f['statementId']+'/revisions');latest=history['revisions'][-1];assert latest['revision']==3 and latest['assertion']['literal']['lexicalValue']=='381';assert history['revisions'][0]['assertion']['literal']['lexicalValue']=='380';checks['history_keeps_old_fact']=True
assert latest['subjectId']==f['entityId'] and latest['validityKind']=='UNKNOWN' and latest['assertion']['literal']['datatypeIri'].endswith('#decimal');checks['identity_datatype_time_preserved']=True
ev=c.call(base+'/evidence/'+latest['evidenceIds'][0]);assert ev['exactQuote']=='设备A额定电压381V。';checks['new_exact_evidence']=True
if '--replay' in sys.argv:
 replay=c.call(p+'/source-reviews/'+f['modelReviewId']+'/modeling-task',{'expectedObservedDigest':task['incremental']['newDigest']});assert replay['id']==task['id'];checks['model_task_replay']=True
 items=c.call(base+'/source-changes');item=next(i for i in items if i['itemKind']=='FACT' and i['itemId']==f['statementId'])
 quote=ev['exactQuote'];start=f['newText'].index(quote)
 change=c.call(base+'/source-changes/items/'+item['id']+'/fact-revision',{'expectedObservedDigest':item['newDigest'],'expectedRevision':2,'assertionText':'DataPropertyAssertion(<urn:t10:voltage> <urn:t10:equipment:a> "381"^^<http://www.w3.org/2001/XMLSchema#decimal>)','validityKind':'UNKNOWN','validFrom':None,'validTo':None,'exactQuote':quote,'startCodePoint':start,'endCodePoint':start+len(quote)})
 assert change['status']=='APPROVED';assert c.call(base+'/statements/'+f['statementId']+'/revisions')==history;checks['fact_replay_no_duplicate_revision']=True
result={'status':'PASS','checks':checks,'taskId':task['id'],'history':history,'evidence':ev,'publishedDigest':published['document']['documentDigest']}
(OUT/('replay-readback.json' if '--replay' in sys.argv else 'post-restart-readback.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2));print('PASS '+str(len(checks))+' persisted checks')
