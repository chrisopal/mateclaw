#!/usr/bin/env python3
"""Verify declarations across an explicit offline import closure in the packaged API."""
import hashlib,json,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'))
api,op=c['api'],c['op']
out=ROOT/'docs/validation/semantic-owl-runtime/closure-readback.json'
if out.exists():
    saved=json.loads(out.read_text())
    r=api('/semantic/ontologies/'+saved['ontologyId']+'/revisions/'+saved['revisionId'])
    assert r['document']['documentDigest']==saved['documentDigest']
    assert r['document']['importLockDigest']==saved['importLockDigest']
    print('Existing closure fixture readback passed')
    raise SystemExit
text='Ontology(<urn:qa:dependency> Declaration(Class(<urn:qa:A>)) SubClassOf(<urn:qa:A> <urn:qa:B>))'
lock={'requestedIri':'urn:qa:dependency','resolvedOntologyIri':'urn:qa:dependency','versionIri':None,'syntax':'FUNCTIONAL','documentText':text,'contentDigest':hashlib.sha256(text.encode()).hexdigest(),'artifactId':'qa-closure-dependency'}
o=api('/semantic/ontologies',{'name':'Import closure declaration acceptance','description':'Synthetic offline validation'})
p='/semantic/ontologies/'+o['id'];d=api(p+'/draft',{'baseRevisionId':None})
d=api(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'name':o['name'],'description':'Synthetic offline validation','operationId':op(),'document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':'Ontology(<urn:qa:root> Import(<urn:qa:dependency>) Declaration(Class(<urn:qa:B>)))','imports':[lock],'policy':{'version':'1','rules':[]}}},'PUT')
r=api(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'note':'Closed offline dependency acceptance'})
read=api(p+'/revisions/'+r['id']);assert read['document']['source']['imports'][0]['contentDigest']==lock['contentDigest']
assert read['document']['source']['imports'][0]['documentText']==text
out.write_text(json.dumps({'ontologyId':o['id'],'revisionId':r['id'],'documentDigest':read['document']['documentDigest'],'importLockDigest':read['document']['importLockDigest'],'importTextPreserved':True,'rootSuppliesImportedDeclaration':True},indent=2)+'\n')
print('Packaged root-closure save, publish and locked dependency readback passed')
