"""Read-only T12 replay against persistent runtime; never generates model output."""
import json, sys, hashlib
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client(); c.workspace=json.loads((OUT/'fixture.json').read_text())['workspaceId']
results={}
for label,oid,tid,terms,samples in [
 ('natural','2098771602272731138','2cf5374f-e16a-43cf-9662-a04c6fc4d6fb',{'设备','泵','点检记录','设备编号','温度','点检设备'},2),
 ('library','2098772686139604993','e3567a30-dec9-4f1d-9b2a-40fa5071993b',{'图书','读者','借阅记录','ISBN','读者编号','借阅图书','借阅人'},3)]:
 t=c.call('/semantic/modeling-tasks/'+tid); r=c.call('/semantic/ontologies/'+oid+'/revisions'); q=t['proposals'][0]
 assert t['stage']=='READY' and q['status']=='ACCEPTED' and len(r)==1
 assert {x['name'] for x in q['input']['changes'] if x['kind']=='CREATE_TERM'}==terms
 assert len(q['input']['samples'])==samples
 text=t['goal'] if label=='natural' else (OUT/'materials/library-v1.txt').read_text()
 assert all(e['exactQuote'] in text for e in q['input']['evidence'])
 axioms=r[0]['document']['axioms'];assert not any(a['axiomType'] in ['ClassAssertion','ObjectPropertyAssertion','DataPropertyAssertion'] for a in axioms)
 assert sum(a['rendering'].startswith('Declaration(Class(') for a in axioms)==3
 if label=='library':
  reviews=c.call('/semantic/ontologies/'+oid+'/source-reviews');assert len(reviews)==22 and all(x['decision']=='ACKNOWLEDGE' for x in reviews)
 results[label]={'stage':t['stage'],'revision':r[0]['id'],'axioms':len(axioms),'samples':samples,'allQuotesExact':True,'instancesNotInModel':True}
a=c.call('/semantic/ontologies/2098771602272731138/revisions/2098771602276925442')['document'];b=c.call('/semantic/ontologies/2098772916075544578/draft')['document']
assert a['source']['documentText']==b['source']['documentText']
assert {x['rendering'] for x in a['axioms']}=={x['rendering'] for x in b['axioms']}
results['roundtrip']={'canonicalTextAndAxiomSetEqual':True,'count':len(a['axioms'])}
g='/semantic/graphs/2098772602979139585';trusted=c.call(g+'/statements?view=trusted')['items'];assert len(trusted)==1
s=trusted[0];assert s['reviewStatus']=='ACCEPTED' and s['validityKind']=='UNKNOWN'
h=c.call(g+'/statements/'+s['id']+'/revisions')['revisions'];assert [x['reviewStatus'] for x in h]==['PROPOSED','ACCEPTED']
e=c.call(g+'/evidence/'+s['evidenceIds'][0]);assert 'P-101是一台泵' in json.dumps(e,ensure_ascii=False)
results['sample']={'trusted':len(trusted),'history':['PROPOSED','ACCEPTED'],'evidenceExact':True,'validity':'UNKNOWN'}
(OUT/'replay-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print(json.dumps(results,ensure_ascii=False))
