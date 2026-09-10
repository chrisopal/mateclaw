#!/usr/bin/env python3
"""Prepare an isolated nonempty OWL graph for browser migration acceptance. No model calls."""
import runpy,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'))
api,op=c['api'],c['op']
out=ROOT/'docs/validation/semantic-owl-runtime/migration-fixture.json'
if out.exists():
    saved=json.loads(out.read_text())
    assert api('/semantic/graphs/'+saved['graphId'])
    print(json.dumps({'graphId':saved['graphId'],'fixture':'existing'}))
    raise SystemExit
name='OWL migration browser acceptance'
o=api('/semantic/ontologies',{'name':name,'description':'Isolated synthetic migration fixture'})
p='/semantic/ontologies/'+o['id']
versions=[]
for version in [1,2]:
    d=api(p+'/draft',{'baseRevisionId':versions[-1]['id'] if versions else None})
    text='Ontology(<urn:qa:migration> Declaration(Class(<urn:qa:Batch>))'+ (' Declaration(Class(<urn:qa:CalibratedBatch>))' if version==2 else '') +')'
    d=api(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'name':name,'description':'Isolated synthetic migration fixture','operationId':op(),'document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':text,'imports':[],'policy':{'version':'1','rules':[]}}},'PUT')
    versions.append(api(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'note':'Synthetic browser verification'}))
kb=api('/wiki/knowledge-bases',{'name':name,'description':'Synthetic only'})
b=api('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':versions[0]['id']},'PUT')
g='/semantic/graphs/'+b['graphId']
e=api(g+'/entities',{'iri':'urn:qa:migration:batch1','assertedTypes':['urn:qa:Batch'],'displayName':'Migration Batch'})
saved={'graphId':b['graphId'],'knowledgeBaseId':str(kb['id']),'ontologyId':o['id'],'sourceRevisionId':versions[0]['id'],'targetRevisionId':versions[1]['id'],'entityId':e['id'],'binding':api('/semantic/knowledge-bases/'+str(kb['id'])+'/binding'),'noModelCalls':True}
out.write_text(json.dumps(saved,indent=2))
print(json.dumps({'graphId':b['graphId'],'fixture':'created'}))
