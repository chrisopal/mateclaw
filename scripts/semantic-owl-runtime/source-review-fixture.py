#!/usr/bin/env python3
"""Seed synthetic shared axiom evidence and remove only that new source for browser review."""
import json,runpy,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'));api,op=c['api'],c['op']
out=ROOT/'docs/validation/semantic-owl-runtime/source-review-fixture.json'
if out.exists():
 f=json.loads(out.read_text());assert api('/semantic/ontologies/'+f['ontologyId']);print(json.dumps({'ontologyId':f['ontologyId'],'fixture':'existing'}));raise SystemExit
name='OWL shared source review browser acceptance';text='测针😀属于测量设备。'
o=api('/semantic/ontologies',{'name':name,'description':'Synthetic deleted-source review fixture'});p='/semantic/ontologies/'+o['id']
d=api(p+'/draft',{})
d=api(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'name':name,'description':'Synthetic deleted-source review fixture','operationId':op(),'document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':'Ontology(<urn:qa:source-review> Declaration(Class(<urn:qa:Probe>)) Declaration(Class(<urn:qa:Equipment>)) SubClassOf(<urn:qa:Probe> <urn:qa:Equipment>))','imports':[],'policy':{'version':'1','rules':[]}}},'PUT')
kb=api('/wiki/knowledge-bases',{'name':name,'description':'Synthetic source, safe to delete within this fixture'})
raw=api('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'Synthetic definitions','content':text})
axioms=d['document']['axioms'];bindings=[];version=d['draftVersion']
for axiom in axioms[:2]:
 b=api(p+'/draft/axiom-sources',{'expectedDraftVersion':version,'operationId':op(),'axiomId':axiom['axiomId'],'knowledgeBaseId':str(kb['id']),'sourceRef':str(raw['id']),'expectedSourceDigest':hashlib.sha256(text.encode()).hexdigest(),'startCodePoint':0,'endCodePoint':len(text),'exactQuote':text,'origin':'EXPERT'})
 bindings.append(b['binding']);version=b['draftVersion']
published=api(p+'/draft/publish',{'expectedDraftVersion':version,'operationId':op(),'note':'Synthetic shared-source review acceptance'})
assert bindings[0]['sourceSnapshotId']==bindings[1]['sourceSnapshotId']
f={'ontologyId':o['id'],'revisionId':published['id'],'knowledgeBaseId':str(kb['id']),'rawId':str(raw['id']),'originalText':text,'bindings':bindings,'publishedBefore':published,'noModelCalls':True}
out.write_text(json.dumps(f,ensure_ascii=False,indent=2))
# Only the source created immediately above is removed; published evidence snapshots remain.
api('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/'+str(raw['id']),method='DELETE')
print(json.dumps({'ontologyId':o['id'],'fixture':'created; synthetic raw source removed'}))
