#!/usr/bin/env python3
"""Smoke-test the packaged complete-object endpoint on the existing synthetic graph."""
import runpy,json
from pathlib import Path
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'))
api=c['api'];out=ROOT/'docs/validation/semantic-owl-runtime'
f=json.loads((out/'migration-fixture.json').read_text());g='/semantic/graphs/'+f['graphId']
b=api(g)['binding']
request={'expectedGraphVersion':b['graphVersion'],'expectedOntologyRevisionId':b['ontologyRevisionId'],'entityId':f['entityId'],'assertions':[]}
r=api(g+'/objects/complete-validation',request)
assert r['valid'] and r['ontologyRevisionId']==b['ontologyRevisionId']
try: api(g+'/objects/complete-validation',{**request,'expectedGraphVersion':b['graphVersion']+1})
except HTTPError as error: assert error.code==409
else: raise AssertionError('Stale graph version accepted')
after=api(g)['binding'];assert after==b
(out/'complete-object-readback.json').write_text(json.dumps({'result':r,'staleVersionRejected':True,'bindingUnchanged':True,'scope':'Packaged API smoke test; empty policy fixture, business rule cases covered in integration tests'},indent=2))
print('Packaged complete-object validation: valid request, stale CAS rejected, no graph mutation')
