#!/usr/bin/env python3
"""Synthetic loopback-only acceptance; credentials used only in memory. Run against isolated H2 preview."""
import json,re,uuid,hashlib
from pathlib import Path
from urllib.request import Request,urlopen
from urllib.error import HTTPError
ROOT=Path(__file__).resolve().parents[2]
BASE='http://127.0.0.1:18118/api/v1'
OUT=ROOT/'output/presales'
OUT.mkdir(parents=True,exist_ok=True)
def request(path,body=None,method=None,token=None,status=200,binary=False):
 h={'Content-Type':'application/json','X-Workspace-Id':'1'}
 if token:h['Authorization']='Bearer '+token
 req=Request(BASE+path,headers=h,data=None if body is None else json.dumps(body).encode(),method=method or ('POST' if body is not None else 'GET'))
 try:
  with urlopen(req) as response:code=response.status;data=response.read()
 except HTTPError as e:code=e.code;data=e.read()
 assert code==status,(path,code,data[:500])
 if binary:return data
 value=json.loads(data)
 if status==200:assert value['code'] in (0,200), (path,value.get('message'))
 return value.get('data')
seed=(ROOT/'mateclaw-server/src/main/resources/db/data-mysql-zh.sql').read_text()
secret=re.search(r'密码：([^，]+)',seed)[1]
admin=request('/auth/login',{'username':'admin','password':secret})['token']
reviewer='presales-review-'+uuid.uuid4().hex[:10]
reviewer_secret=uuid.uuid4().hex+uuid.uuid4().hex
request('/auth/users',{'username':reviewer,'password':reviewer_secret,'nickname':'独立评审（虚构验收）','role':'admin','enabled':True},token=admin)
qa=request('/auth/login',{'username':reviewer,'password':reviewer_secret})['token']
def api(path,body=None,method=None,status=200,who=None,binary=False):return request(path,body,method,who or admin,status,binary)
def op():return str(uuid.uuid4())
# Baseline production-empty scenario, intentionally labeled synthetic.
p=api('/presales/projects',{'name':'MES 证据到成果闭环（虚构验收）','customer':'启明制造（虚构）','industry':'制造业','goal':'一期两条产线，预算未知','expectedVersion':0,'operationId':op()})
case=p['id']
def cmd(action,payload,who=None,status=200):
 global p
 result=api('/presales/projects/'+case+'/commands',{'expectedVersion':p['version'],'operationId':op(),'action':action,'payload':payload},status=status,who=who)
 if status==200:p=result
 return result
kb=api('/wiki/knowledge-bases',{'name':'MES 试点材料（虚构验收）','description':'仅供隔离验收使用'})
raw=api('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'一期范围（虚构）','content':'客户一期范围为两条产线，预算尚未确认。'})
o=api('/semantic/ontologies',{'name':'售前范围（虚构验收）','description':'范围证据链测试'})
opth='/semantic/ontologies/'+o['id'];draft=api(opth+'/draft',{})
doc={'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':'Ontology(<urn:presales:qa> Declaration(Class(<urn:presales:Project>)) Declaration(DataProperty(<urn:presales:lineCount>)) DataPropertyDomain(<urn:presales:lineCount> <urn:presales:Project>) DataPropertyRange(<urn:presales:lineCount> <http://www.w3.org/2001/XMLSchema#integer>))','imports':[],'policy':{'version':'v1','rules':[]}}
draft=api(opth+'/draft',{'expectedDraftVersion':draft['draftVersion'],'name':'售前范围（虚构验收）','description':'范围证据链测试','document':doc,'operationId':op()},'PUT')
api(opth+'/draft/validate',{'expectedDraftVersion':draft['draftVersion']})
revision=api(opth+'/draft/publish',{'expectedDraftVersion':draft['draftVersion'],'operationId':op(),'note':'仅限虚构验收'})
binding=api('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':revision['id']},'PUT');graph=binding['graphId'];gp='/semantic/graphs/'+graph
entity=api(gp+'/entities',{'assertedTypes':['urn:presales:Project'],'displayName':'MES一期（虚构）'})
imp=api(gp+'/imports',{'sourceKind':'WIKI_RAW','sourceRef':str(raw['id']),'operationId':op()})
quote='两条产线';content='客户一期范围为两条产线，预算尚未确认。';start=content.index(quote)
e=api(gp+'/snapshots/'+imp['snapshotId']+'/evidence',{'operationId':op(),'startCodePoint':start,'endCodePoint':start+len(quote),'exactQuote':quote})
# Entity API returns server minted IRI, never guessed from id.
fact=api(gp+'/statements',{'operationId':op(),'subjectId':entity['id'],'assertionText':'DataPropertyAssertion(<urn:presales:lineCount> <'+entity['iri']+'> "2"^^<http://www.w3.org/2001/XMLSchema#integer>)','validityKind':'UNKNOWN','evidenceIds':[e['id']]})
fact=api(gp+'/statements/'+fact['id']+'/review',{'expectedRevision':fact['revision'],'action':'ACCEPT','reason':'核对虚构原文','operationId':op()})
cmd('BIND_MATERIAL',{'kbId':str(kb['id']),'graphId':graph,'role':'PROJECT'})
cmd('SAVE_REQUIREMENT',{'title':'一期两条产线','scope':'IN','priority':'HIGH','originKind':'CUSTOMER_SOURCE','graphId':graph,'statementId':fact['id'],'statementRevision':fact['revision']})
cmd('APPROVE_BASELINE',{'reason':'仅为内部基线；客户预算未确认'})
req=p['requirements'][0]['id'];baseline=p['baselines'][-1]['id']
cmd('SAVE_SOLUTION',{'title':'MES 试点方案（虚构验收）','baselineId':baseline,'sections':[{'title':'需求与范围','text':'一期两条产线；预算尚未确认。预测维护仅为未来建议。','requirementRefs':[req]}],'requirementResponses':[{'requirementId':req,'status':'FULL'}]})
solution=p['solutions'][-1]['id']
cmd('CREATE_RELEASE',{'solutionId':solution},status=409)
cmd('SAVE_REVIEW',{'solutionId':solution,'summary':'独立核对范围与未知；未形成预算承诺','issues':[]},who=qa)
cmd('CREATE_RELEASE',{'solutionId':solution,'purpose':'虚构验收客户材料'})
release=p['releases'][-1]['id'];prefix='/presales/projects/'+case+'/releases/'+release
api(prefix+'/files/solution.md',status=403)
preview=api(prefix+'/preview/solution.md',binary=True)
cmd('APPROVE_RELEASE',{'releaseId':release,'reason':'核对准确文件和未知项'})
cmd('PUBLISH_RELEASE',{'releaseId':release})
published=api(prefix+'/files/solution.md',binary=True);assert preview==published
handoff=api('/presales/projects/'+case+'/handoff');assert handoff['schemaVersion']==1
readback=api('/presales/projects/'+case);assert readback['version']==p['version'];assert readback['requirements'][0]['customerConfirmationStatus']=='UNCONFIRMED'
report={'status':'PASS','database':'isolated persistent H2','transport':'live HTTP localhost18118','caseId':case,'version':p['version'],'baselineId':baseline,'solutionId':solution,'releaseId':release,'checks':['create/readback','Wiki text source','ontology/semantic review','G1','independent human review required','unapproved download denied','exact preview/published bytes','handoff','customer confirmation unchanged'],'model':'NOT_RUN: unconfigured','artifactSha256':hashlib.sha256(published).hexdigest()}
(OUT/'runtime-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2));print(json.dumps(report,ensure_ascii=False))
