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
    with urlopen(req) as r: data=r.read()
    if raw:return data
    result=json.loads(data)
    if result.get('code') not in [0,200]:raise RuntimeError(result)
    return result.get('data')

def op():return str(uuid.uuid4())
seed=(ROOT/'mateclaw-server/src/main/resources/db/data-mysql-zh.sql').read_text()
password=re.search(r'密码：([^，]+)',seed)[1]
token=api('/auth/login',{'username':'admin','password':password})['token']
items=api('/semantic/ontologies')['items']; ont=next(x for x in items if x['name']=='OWL runtime browser QA')
oid=ont['id']; rid=ont['latestRevisionId']; path='/semantic/ontologies/'+oid
revision=api(path+'/revisions/'+rid)
assert len(revision['document']['axioms'])==4
for syntax,ext in [('FUNCTIONAL','ofn'),('RDF_XML','rdf')]:
    data=api(path+'/revisions/'+rid+'/document?syntax='+syntax,raw=True)
    assert b'urn:qa:Batch' in data
    (OUT/('published.'+ext)).write_bytes(data)
kb=api('/wiki/knowledge-bases',{'name':'OWL runtime synthetic','description':'Isolated verification'})
binding=api('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':rid,'expectedGraphVersion':None},'PUT')
gid=binding['graphId']; graph='/semantic/graphs/'+gid
entity=api(graph+'/entities',{'iri':'urn:qa:batch:001','assertedTypes':['urn:qa:Batch'],'displayName':'QA Batch'})
statement=api(graph+'/statements',{'operationId':op(),'subjectId':entity['id'],'assertionText':'DataPropertyAssertion(<urn:qa:temperature> <urn:qa:batch:001> "23.5"^^<http://www.w3.org/2001/XMLSchema#decimal>)','validityKind':'UNKNOWN','evidenceIds':[]})
result={'runtime':'genuine Spring Boot + isolated H2','browserCreatedOntology':ont,'revisionReadback':revision,'binding':binding,'entity':entity,'statement':statement,'entitiesReadback':api(graph+'/entities'),'statementsReadback':api(graph+'/statements?view=review'),'noModelCalls':True}
(OUT/'api-readback.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({'ontologyId':oid,'revisionId':rid,'graphId':gid,'knowledgeBaseId':kb['id'],'checks':'exports + binding + entity + statement persisted GET'}))
sourceText='Batch temperature is measured in degrees Celsius.'
raw=api('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'Synthetic temperature specification','content':sourceText})
draft=api(path+'/draft') if api(path).get('hasDraft') else api(path+'/draft',{'baseRevisionId':rid})
axiom=next(a for a in draft['document']['axioms'] if a['axiomType']=='DataPropertyDomain')
bound=api(path+'/draft/axiom-sources',{'expectedDraftVersion':draft['draftVersion'],'operationId':op(),'axiomId':axiom['axiomId'],'knowledgeBaseId':str(kb['id']),'sourceRef':str(raw['id']),'expectedSourceDigest':hashlib.sha256(sourceText.encode()).hexdigest(),'startCodePoint':0,'endCodePoint':len(sourceText),'exactQuote':sourceText,'origin':'EXPERT'})
reviews=api(path+'/source-reviews')
result['m7']={'bound':bound,'reviews':reviews}
(OUT/'api-readback.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
result['m7']['snapshots']=api(path+'/source-reviews/'+reviews[0]['id']+'/snapshots')
(OUT/'api-readback.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print('M7 source binding and immutable snapshot readback passed')
