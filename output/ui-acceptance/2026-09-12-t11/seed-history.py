"""Add a synthetic cited fact to an existing application while its ontology is archived."""
import json,sys,uuid
from pathlib import Path
OUT=Path(__file__).resolve().parent;sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();f=json.loads((OUT/'fixture.json').read_text());c.workspace=f['workspaceId'];a=f['applications'][1];b='/semantic/graphs/'+a['graphId'];op=lambda:str(uuid.uuid4());text='维修车间设备A额定电压380V。'
raw=c.call('/wiki/knowledge-bases/'+a['kbId']+'/raw/text',{'title':'T11 保留历史资料','content':text})
snap=c.call(b+'/imports',{'sourceKind':'WIKI_RAW','sourceRef':str(raw['id']),'operationId':op()})
ev=c.call(b+'/snapshots/'+snap['snapshotId']+'/evidence',{'operationId':op(),'startCodePoint':0,'endCodePoint':len(text),'exactQuote':text})
s=c.call(b+'/statements',{'operationId':op(),'subjectId':a['entity']['id'],'assertionText':'DataPropertyAssertion(<urn:t11:voltage> <'+a['entity']['iri']+'> "380"^^<http://www.w3.org/2001/XMLSchema#decimal>)','validityKind':'UNKNOWN','evidenceIds':[ev['id']]})
s=c.call(b+'/statements/'+s['id']+'/review',{'expectedRevision':s['revision'],'action':'ACCEPT','reason':'核对保留历史的原文','operationId':op()})
(OUT/'history.json').write_text(json.dumps({'graphId':a['graphId'],'statementId':s['id'],'evidenceId':ev['id'],'snapshotId':snap['snapshotId']},indent=2));print('Cited fact accepted on retained v1 application')
