"""Seed a deterministic proposal for UI human-confirmation testing; no LLM claim."""
import json,sys,uuid
from pathlib import Path
OUT=Path(__file__).resolve().parent;sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
f=json.loads((OUT/'fixture.json').read_text());c=Client();c.workspace=f['workspaceId'];p='/semantic/ontologies/'+f['ontologyId']
tasks=c.call('/semantic/modeling-tasks?ontologyId='+f['ontologyId']);task=tasks[0]
assert task['incremental'] and not task['proposals']
draft=c.call(p+'/draft');change={'kind':'REPLACE_DEFINITION','targetId':'urn:t10:Equipment','field':'NAME','value':'生产设备','originalAxiomId':task['incremental']['affectedAxiomIds'][0],'clientId':'name'}
request={'operationId':str(uuid.uuid4()),'expectedDraftVersion':draft['draftVersion'],'changes':[change],'evidence':[{'clientId':'name','knowledgeBaseId':f['knowledgeBaseId'],'sourceRef':f['sourceRef'],'sourceDigest':task['incremental']['newDigest'],'exactQuote':f['newText'],'origin':'EXTRACTED'}]}
task=c.call('/semantic/modeling-tasks/'+task['id']+'/proposals',request)
f['taskId']=task['id'];f['modelReviewId']=task['incremental']['reviewId'];f['modelProposalId']=task['proposals'][0]['id'];f['modelRequest']=request
(OUT/'fixture.json').write_text(json.dumps(f,ensure_ascii=False,indent=2));print('Prepared deterministic proposal for UI confirmation')
