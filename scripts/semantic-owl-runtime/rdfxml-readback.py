#!/usr/bin/env python3
"""Packaged RDF/XML internal entity validation on an isolated synthetic ontology."""
import hashlib,json,runpy,sys
from pathlib import Path
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'))
api,op=c['api'],c['op']
singleton='--singleton' in sys.argv
out=ROOT/'docs/validation/semantic-owl-runtime'/('rdfxml-singleton-readback.json' if singleton else 'rdfxml-readback.json')
if out.exists():
    saved=json.loads(out.read_text())
    r=api('/semantic/ontologies/'+saved['ontologyId']+'/revisions/'+saved['revisionId'])
    assert r['document']['documentDigest']==saved['documentDigest']
    print('Existing RDF/XML published fixture read back')
    raise SystemExit
xml="""<!DOCTYPE rdf:RDF [<!ENTITY ns 'urn:qa:internal:'>]>
<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#' xmlns:owl='http://www.w3.org/2002/07/owl#'>
<owl:Ontology rdf:about='&ns;ontology'/><owl:Class rdf:about='&ns;Equipment'/></rdf:RDF>"""
if singleton:
    xml=xml.replace('</rdf:RDF>', "<owl:Class rdf:about='&ns;Equipment'><owl:equivalentClass><owl:Class><owl:intersectionOf rdf:parseType='Collection'><owl:Class rdf:about='&ns;PhysicalEquipment'/></owl:intersectionOf></owl:Class></owl:equivalentClass></owl:Class></rdf:RDF>")
o=api('/semantic/ontologies',{'name':'RDF XML internal entity acceptance','description':'Synthetic input verification'})
p='/semantic/ontologies/'+o['id']
d=api(p+'/draft',{'baseRevisionId':None})
body={'expectedDraftVersion':d['draftVersion'],'name':o['name'],'description':'Synthetic input verification','operationId':op(),'document':{'modelSchema':'owl-document-v1','syntax':'RDF_XML','documentText':xml,'imports':[],'policy':{'version':'1','rules':[]}}}
d=api(p+'/draft',body,'PUT')
canonical=d['document']['source']['documentText']
assert d['document']['source']['syntax']=='FUNCTIONAL'
assert 'urn:qa:internal:Equipment' in canonical
if singleton:
    assert 'urn:qa:internal:PhysicalEquipment' in canonical and 'EquivalentClasses(' in canonical
    assert 'ObjectIntersectionOf(' not in canonical
assert d['document']['documentDigest']==hashlib.sha256(canonical.encode()).hexdigest()
bad={**body,'expectedDraftVersion':d['draftVersion'],'operationId':op(),'document':{**body['document'],'documentText':xml.replace("<!ENTITY ns 'urn:qa:internal:'>","<!ENTITY ns SYSTEM 'file:///nonexistent-runtime-secret'>")}}
try:api(p+'/draft',bad,'PUT')
except HTTPError as error:assert error.code==422
else:raise AssertionError('External entity document accepted')
after=api(p+'/draft');assert after['draftVersion']==d['draftVersion'] and after['document']['documentDigest']==d['document']['documentDigest']
r=api(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'note':'Synthetic input validation'})
read=api(p+'/revisions/'+r['id']);assert read['document']['source']['documentText']==canonical
out.write_text(json.dumps({'ontologyId':o['id'],'revisionId':r['id'],'documentDigest':read['document']['documentDigest'],'externalEntityRejected':True,'rejectedSavePreservedDraft':True,'publishedCanonicalTextPreserved':True,'inputXmlDigest':hashlib.sha256(xml.encode()).hexdigest()},indent=2)+'\n')
print('RDF/XML packaged save, reject, publish and readback passed')
