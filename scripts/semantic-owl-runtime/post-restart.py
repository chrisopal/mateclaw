#!/usr/bin/env python3
"""Isolated synthetic runtime API checks; never invokes a model."""
import json, re, uuid, hashlib
from pathlib import Path
from urllib.request import Request,urlopen
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/validation/semantic-owl-runtime'
BASE='http://127.0.0.1:18109/api/v1'
token=None

def api(path,body=None,method=None,raw=False):
    headers={'Content-Type':'application/json','X-Workspace-Id':'1'}
    if token: headers['Authorization']='Bearer '+token
    req=Request(BASE+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method or ('POST' if body is not None else 'GET'))
    with urlopen(req, timeout=60) as r: data=r.read()
    if raw:return data
    result=json.loads(data)
    if result.get('code') not in [0,200]:raise RuntimeError(result)
    return result.get('data')

def op():return str(uuid.uuid4())
seed=(ROOT/'mateclaw-server/src/main/resources/db/data-mysql-zh.sql').read_text()
password=re.search(r'密码：([^，]+)',seed)[1]
token=api('/auth/login',{'username':'admin','password':password})['token']
saved=json.loads((OUT/'api-readback.json').read_text())
oid=saved['browserCreatedOntology']['id']; rid=saved['revisionReadback']['id']; gid=saved['binding']['graphId']; path='/semantic/ontologies/'+oid
assert api(path+'/revisions/'+rid)['document']['documentDigest']==saved['revisionReadback']['document']['documentDigest']
assert any(e['id']==saved['entity']['id'] for e in api('/semantic/graphs/'+gid+'/entities')['items'])
assert any(s['id']==saved['statement']['id'] for s in api('/semantic/graphs/'+gid+'/statements?view=review')['items'])
reviews=api(path+'/source-reviews')
snapshots=api(path+'/source-reviews/'+reviews[0]['id']+'/snapshots')
result={'postRestart':True,'revisionDigestUnchanged':True,'entityPersisted':True,'statementPersisted':True,'sourceSnapshots':snapshots}
decision=reviews[0]
if decision['reviewState']=='PENDING':
    decision=api(path+'/source-reviews/'+decision['id']+'/decision',{'operationId':op(),'expectedObservedDigest':decision['observedDigest'],'decision':'ACKNOWLEDGE','reason':'Synthetic runtime source review'})
assert decision['reviewState']=='REVIEWED' and decision['decision']=='ACKNOWLEDGE'
result['decision']=decision
result['reviewReadback']=api(path+'/source-reviews')
(OUT/'post-restart-readback.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('Post-restart ontology, graph, statement, source snapshot and decision readback passed')
