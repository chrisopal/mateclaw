"""Create isolated T10 fixtures on the existing persistent runtime; never reset it."""
import json,sys,uuid,datetime,hashlib
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();op=lambda:str(uuid.uuid4())
run=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
c.workspace=str(c.call('/workspaces',{'name':'T10 资料增量验收 '+run})['id'])
o=c.call('/semantic/ontologies',{'name':'T10 设备模型','description':'Synthetic incremental acceptance'});p='/semantic/ontologies/'+o['id']
d=c.call(p+'/draft',{})
d=c.call(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'name':o['name'],'description':o['description'],'document':{'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':'Ontology(<urn:t10:model> Declaration(Class(<urn:t10:Equipment>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:t10:Equipment> "设备") Declaration(DataProperty(<urn:t10:voltage>)) DataPropertyDomain(<urn:t10:voltage> <urn:t10:Equipment>) DataPropertyRange(<urn:t10:voltage> <http://www.w3.org/2001/XMLSchema#decimal>))','imports':[],'policy':{'version':'1','rules':[]}}},'PUT')
kb=c.call('/wiki/knowledge-bases',{'name':'T10 合成资料 '+run})
old='设备是生产现场使用的装备。设备A额定电压380V。'
new='设备现称生产设备。设备A额定电压381V。'
raw=c.call('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'T10 合成设备资料','content':old})
axiom=next(a for a in d['document']['axioms'] if a['axiomType']=='AnnotationAssertion')
b=c.call(p+'/draft/axiom-sources',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'axiomId':axiom['axiomId'],'knowledgeBaseId':str(kb['id']),'sourceRef':str(raw['id']),'expectedSourceDigest':hashlib.sha256(old.encode()).hexdigest(),'startCodePoint':0,'endCodePoint':len(old),'exactQuote':old,'origin':'EXPERT'})
for r in c.call(p+'/source-reviews'):
 if r['reviewState']=='PENDING':c.call(p+'/source-reviews/'+r['id']+'/decision',{'operationId':op(),'expectedObservedDigest':r['observedDigest'],'decision':'ACKNOWLEDGE','reason':'核对初始资料'})
assert c.call(p+'/draft/validate',{'expectedDraftVersion':b['draftVersion']})['valid']
rev=c.call(p+'/draft/publish',{'expectedDraftVersion':b['draftVersion'],'operationId':op(),'note':'T10 initial published version'})
bind=c.call('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':rev['id']},'PUT');base='/semantic/graphs/'+bind['graphId']
e=c.call(base+'/entities',{'iri':'urn:t10:equipment:a','displayName':'设备A','assertedTypes':['urn:t10:Equipment']})
snap=c.call(base+'/imports',{'sourceKind':'WIKI_RAW','sourceRef':str(raw['id']),'operationId':op()})
ev=c.call(base+'/snapshots/'+snap['snapshotId']+'/evidence',{'operationId':op(),'startCodePoint':0,'endCodePoint':len(old),'exactQuote':old})
f=c.call(base+'/statements',{'operationId':op(),'subjectId':e['id'],'assertionText':'DataPropertyAssertion(<urn:t10:voltage> <urn:t10:equipment:a> "380"^^<http://www.w3.org/2001/XMLSchema#decimal>)','validityKind':'UNKNOWN','evidenceIds':[ev['id']]})
f=c.call(base+'/statements/'+f['id']+'/review',{'expectedRevision':f['revision'],'action':'ACCEPT','reason':'核对初始资料','operationId':op()})
out={'workspaceId':c.workspace,'ontologyId':o['id'],'revisionId':rev['id'],'publishedBefore':rev,'graphId':bind['graphId'],'knowledgeBaseId':str(kb['id']),'sourceRef':str(raw['id']),'entityId':e['id'],'statementId':f['id'],'oldText':old,'newText':new}
(OUT/'fixture.json').write_text(json.dumps(out,ensure_ascii=False,indent=2))
print('Created isolated T10 fixture '+o['id'])
