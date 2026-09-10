#!/usr/bin/env python3
"""Exercise packaged OWL INSERT/UPDATE/publication and immutable history readback."""
import argparse,json,runpy
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'))
api,op=c['api'],c['op']
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,default=ROOT/'docs/validation/semantic-owl-runtime/owl-persistence-readback.json')
out=parser.parse_args().output
out.parent.mkdir(parents=True,exist_ok=True)
if out.exists():
    saved=json.loads(out.read_text())
    revision=api('/semantic/ontologies/'+saved['ontologyId']+'/revisions/'+saved['revisionId'])
    assert revision['document']['documentDigest']==saved['documentDigest']
    print('Existing published OWL persistence readback passed')
    raise SystemExit
ontology=api('/semantic/ontologies',{'name':'OWL persistence acceptance','description':'Synthetic field retirement verification'})
base='/semantic/ontologies/'+ontology['id']
draft=api(base+'/draft',{})
text='Ontology(<urn:qa:persistence> Declaration(Class(<urn:qa:Equipment>)))'
def save(draft,text):
    return api(base+'/draft',{'expectedDraftVersion':draft['draftVersion'],'operationId':op(),'name':ontology['name'],'description':ontology['description'],'document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':text,'imports':[],'policy':{'version':'1','rules':[]}}},'PUT')
draft=save(draft,text)
revision=api(base+'/draft/publish',{'expectedDraftVersion':draft['draftVersion'],'operationId':op(),'note':'Synthetic persistence acceptance'})
original=api(base+'/revisions/'+revision['id'])
draft=api(base+'/draft',{'baseRevisionId':revision['id']})
draft=save(draft,'Ontology(<urn:qa:persistence> Declaration(Class(<urn:qa:DraftOnly>)))')
assert api(base+'/revisions/'+revision['id'])['document']==original['document']
out.write_text(json.dumps({'ontologyId':ontology['id'],'revisionId':revision['id'],'documentDigest':original['document']['documentDigest'],'publishedHistoryUnchanged':True,'newDraftVersion':draft['draftVersion']},indent=2)+'\n')
print('New draft insert, OWL update, publication and immutable history passed')
