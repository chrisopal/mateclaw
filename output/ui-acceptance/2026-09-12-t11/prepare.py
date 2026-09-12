"""Isolated lifecycle fixture in existing persistent acceptance database."""
import json,sys,uuid,datetime
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();op=lambda:str(uuid.uuid4())
run=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
c.workspace=str(c.call('/workspaces',{'name':'T11 生命周期验收 '+run})['id'])
o=c.call('/semantic/ontologies',{'name':'T11 设备业务模型','description':''});p='/semantic/ontologies/'+o['id']
revs=[]
for name,iri in [('设备','Equipment'),('生产设备','Equipment'),('生产设备','Machine')]:
 d=c.call(p+'/draft',{})
 doc=f'Ontology(<urn:t11:model> Declaration(Class(<urn:t11:{iri}>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:t11:{iri}> "{name}") Declaration(DataProperty(<urn:t11:voltage>)) DataPropertyDomain(<urn:t11:voltage> <urn:t11:{iri}>) DataPropertyRange(<urn:t11:voltage> <http://www.w3.org/2001/XMLSchema#decimal>))'
 d=c.call(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'name':o['name'],'description':'','document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':doc,'imports':[],'policy':{'version':'1','rules':[]}}},'PUT')
 assert c.call(p+'/draft/validate',{'expectedDraftVersion':d['draftVersion']})['valid']
 revs.append(c.call(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'note':name+'版本'}))
apps=[]
for label in ['装配车间','维修车间']:
 kb=c.call('/wiki/knowledge-bases',{'name':'T11 '+label})
 b=c.call('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':revs[0]['id']},'PUT')
 e=c.call('/semantic/graphs/'+b['graphId']+'/entities',{'iri':'urn:t11:equipment:'+str(kb['id']),'displayName':label+'设备A','assertedTypes':['urn:t11:Equipment']})
 apps.append({'kbId':str(kb['id']),'name':'T11 '+label,'graphId':b['graphId'],'entity':e})
d=c.call(p+'/draft',{})
(OUT/'fixture.json').write_text(json.dumps({'workspaceId':c.workspace,'ontologyId':o['id'],'revisions':revs,'applications':apps,'draftId':d['id']},ensure_ascii=False,indent=2))
print('Created T11 isolated fixture '+o['id'])
