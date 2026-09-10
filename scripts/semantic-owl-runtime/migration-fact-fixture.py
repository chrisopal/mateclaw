#!/usr/bin/env python3
"""Seed only an isolated synthetic graph; migration actions are left to the browser."""
import runpy,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'))
api,op=c['api'],c['op']
out=ROOT/'docs/validation/semantic-owl-runtime/migration-fact-fixture.json'
if out.exists():
    saved=json.loads(out.read_text());assert api('/semantic/graphs/'+saved['graphId'])
    print(json.dumps({'graphId':saved['graphId'],'fixture':'existing'}));raise SystemExit
name='OWL accepted fact migration acceptance'
o=api('/semantic/ontologies',{'name':name,'description':'Synthetic fixture for UI migration and rollback'})
p='/semantic/ontologies/'+o['id'];versions=[]
for cls,prop in [('Batch','reading'),('CalibratedBatch','calibratedReading')]:
    d=api(p+'/draft',{'baseRevisionId':versions[-1]['id'] if versions else None})
    text=f'Ontology(<urn:qa:fact-migration> Declaration(Class(<urn:qa:{cls}>)) Declaration(DataProperty(<urn:qa:{prop}>)))'
    d=api(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'name':name,'description':'Synthetic fixture for UI migration and rollback','operationId':op(),'document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':text,'imports':[],'policy':{'version':'1','rules':[]}}},'PUT')
    versions.append(api(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'note':'Synthetic browser acceptance'}))
kb=api('/wiki/knowledge-bases',{'name':name,'description':'Synthetic evidence only'})
b=api('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':versions[0]['id']},'PUT');g='/semantic/graphs/'+b['graphId']
e=api(g+'/entities',{'iri':'urn:qa:fact-migration:batch1','assertedTypes':['urn:qa:Batch'],'displayName':'Measured batch'})
raw=api('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'Synthetic batch reading','content':'Measured reading: 0.012'})
imported=api(g+'/imports',{'sourceKind':'WIKI_RAW','sourceRef':str(raw['id']),'operationId':op()})
ev=api(g+'/snapshots/'+imported['snapshotId']+'/evidence',{'operationId':op(),'startCodePoint':18,'endCodePoint':23,'exactQuote':'0.012'})
f=api(g+'/statements',{'operationId':op(),'subjectId':e['id'],'assertionText':'DataPropertyAssertion(<urn:qa:reading> <urn:qa:fact-migration:batch1> "0.012"^^<http://www.w3.org/2001/XMLSchema#decimal>)','validityKind':'INTERVAL','validFrom':'2026-01-01T00:00:00Z','evidenceIds':[ev['id']]})
api(g+'/statements/'+f['id']+'/review',{'operationId':op(),'expectedRevision':1,'action':'ACCEPT','reason':'Verified synthetic reading'})
saved={'graphId':b['graphId'],'knowledgeBaseId':str(kb['id']),'ontologyId':o['id'],'sourceRevisionId':versions[0]['id'],'targetRevisionId':versions[1]['id'],'entityId':e['id'],'statementId':f['id'],'snapshotId':imported['snapshotId'],'evidenceId':ev['id'],'beforeGraph':api(g),'beforeEntities':api(g+'/entities'),'beforeFacts':api(g+'/statements?view=trusted'),'beforeHistory':api(g+'/statements/'+f['id']+'/revisions'),'noModelCalls':True}
out.write_text(json.dumps(saved,ensure_ascii=False,indent=2))
print(json.dumps({'graphId':b['graphId'],'fixture':'created'}))
