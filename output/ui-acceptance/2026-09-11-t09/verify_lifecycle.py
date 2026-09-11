"""Isolated persistent T09 API acceptance. Uses in-memory controlled auth; never resets DB.
Run: python3 output/ui-acceptance/2026-09-11-t09/verify_lifecycle.py
Each run creates a separately named synthetic workspace, ontology, KB and graph.
/search is verified here; semantic_context tool and real model quality are separate gates.
"""
import json,sys,uuid,datetime
from pathlib import Path
from urllib.error import HTTPError
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
op=lambda:str(uuid.uuid4())
run=datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%dT%H%M%SZ')
result={'run':run,'synthetic':True,'checks':{},'gaps':['semantic_context is a Spring tool, not an HTTP endpoint; this run verifies formal search, not model-generated answers.']}
def persist(): (OUT/'lifecycle-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
def check(name,condition,evidence):
 assert condition,name
 result['checks'][name]={'passed':True,'evidence':evidence};persist()
def rejected(path,body,status):
 try:c.call(path,body)
 except HTTPError as e:
  payload=json.loads(e.read());assert e.code==status,{'expected':status,'actual':e.code,'body':payload}
  return {'httpStatus':e.code,'response':payload}
 raise AssertionError('Expected HTTP rejection '+path)
try:
 c=Client();c.workspace=str(c.call('/workspaces',{'name':'T09 API 生命周期 '+run,'description':'Isolated synthetic persistent acceptance'})['id'])
 result['workspaceId']=c.workspace;persist()
 o=c.call('/semantic/ontologies',{'name':'T09 设备模型 '+run,'description':'Synthetic lifecycle model'});p='/semantic/ontologies/'+o['id']
 document={'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':'Ontology(<urn:t09:model> Declaration(Class(<urn:t09:Equipment>)) Declaration(DataProperty(<urn:t09:voltage>)) DataPropertyDomain(<urn:t09:voltage> <urn:t09:Equipment>) DataPropertyRange(<urn:t09:voltage> <http://www.w3.org/2001/XMLSchema#decimal>))','imports':[],'policy':{'version':'1','rules':[]}}
 d=c.call(p+'/draft',{'baseRevisionId':None});d=c.call(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'name':o['name'],'description':'Synthetic T09','document':document},'PUT')
 assert c.call(p+'/draft/validate',{'expectedDraftVersion':d['draftVersion']})['valid']
 rev=c.call(p+'/draft/publish',{'expectedDraftVersion':d['draftVersion'],'operationId':op(),'note':'T09 acceptance'})
 kb=c.call('/wiki/knowledge-bases',{'name':'T09 生命周期资料 '+run,'description':'Synthetic isolation'})
 bind=c.call('/semantic/knowledge-bases/'+str(kb['id'])+'/binding',{'action':'ENABLE','revisionId':rev['id']},'PUT');base='/semantic/graphs/'+bind['graphId']
 result.update({'ontologyId':o['id'],'ontologyRevisionId':rev['id'],'knowledgeBaseId':str(kb['id']),'graphId':bind['graphId']});persist()
 a=c.call(base+'/entities',{'iri':'urn:t09:equipment:a','displayName':'同名设备','assertedTypes':['urn:t09:Equipment']})
 b=c.call(base+'/entities',{'iri':'urn:t09:equipment:b','displayName':'同名设备','assertedTypes':['urn:t09:Equipment']})
 entities=c.call(base+'/entities')['items'];check('same_name_distinct_ids',len(entities)==2 and a['id']!=b['id'],entities)
 evidence=[];sources=[]
 for i,text in enumerate(['设备A铭牌：额定电压380V。设备B电压220V。','设备A检修记录：历史电压400V，当前复核380V。']):
  raw=c.call('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'T09 来源 '+str(i+1),'content':text});sources.append(str(raw['id']))
  imported=c.call(base+'/imports',{'sourceKind':'WIKI_RAW','sourceRef':str(raw['id']),'operationId':op()})
  ev=c.call(base+'/snapshots/'+imported['snapshotId']+'/evidence',{'operationId':op(),'startCodePoint':0,'endCodePoint':len(text),'exactQuote':text});evidence.append(ev['id'])
  read=c.call(base+'/evidence/'+ev['id']);check('exact_source_'+str(i+1),read['exactQuote']==text and str(read['sourceRef'])==str(raw['id']),read)
 result.update({'entityA':a['id'],'entityB':b['id'],'sourceRefs':sources,'evidenceIds':evidence});persist()
 def propose(entity,value,ev,kind='INTERVAL',negative=False):
  return c.call(base+'/statements',{'operationId':op(),'subjectId':entity['id'],'assertionText':('Negative' if negative else '')+'DataPropertyAssertion(<urn:t09:voltage> <'+entity['iri']+'> "'+value+'"^^<http://www.w3.org/2001/XMLSchema#decimal>)','validityKind':kind,'validFrom':'2026-01-01T00:00:00Z' if kind=='INTERVAL' else None,'validTo':'2027-01-01T00:00:00Z' if kind=='INTERVAL' else None,'evidenceIds':[ev]})
 def review(fact,action):return c.call(base+'/statements/'+fact['id']+'/review',{'expectedRevision':fact['revision'],'action':action,'reason':'T09 isolated source verification','operationId':op()})
 def search(at='2026-09-11T00:00:00Z'):return c.call(base+'/search',{'query':'同名设备','atTime':at,'limit':100})['facts']
 f1=propose(a,'380',evidence[0]);f2=propose(a,'400',evidence[1],'UNKNOWN');other=propose(b,'220',evidence[1])
 check('pending_excluded',not search(),{'facts':search()})
 accepted=review(f1,'ACCEPT');unknown=review(f2,'ACCEPT');other=review(other,'ACCEPT')
 facts=search();ids=[f['id'] for f in facts]
 check('accepted_interval_visible_unknown_excluded',accepted['id'] in ids and unknown['id'] not in ids and other['id'] in ids,{'facts':facts,'unknownAccepted':unknown})
 history1=c.call(base+'/statements/'+f1['id']+'/revisions');history2=c.call(base+'/statements/'+f2['id']+'/revisions')
 check('two_sources_reuse_one_entity',history1['revisions'][-1]['subjectId']==history2['revisions'][-1]['subjectId']==a['id'] and len(c.call(base+'/entities')['items'])==2,{'first':history1,'second':history2})
 check('out_of_interval_excluded',not search('2025-01-01T00:00:00Z'),{'beforeIntervalFacts':search('2025-01-01T00:00:00Z')})
 stale=rejected(base+'/statements/'+f1['id']+'/review',{'expectedRevision':1,'action':'ACCEPT','reason':'stale review','operationId':op()},409);check('stale_revision_rejected',True,stale)
 retracted=review(accepted,'RETRACT');check('retracted_excluded',retracted['reviewStatus']=='RETRACTED' and accepted['id'] not in [x['id'] for x in search()],{'retracted':retracted,'facts':search()})
 # An explicit negative assertion conflicts with the accepted same entity/value assertion.
 competing=propose(b,'220',evidence[0],negative=True)
 conflict=rejected(base+'/statements/'+competing['id']+'/review',{'expectedRevision':competing['revision'],'action':'ACCEPT','reason':'must reject conflicting statement','operationId':op()},409)
 check('conflict_acceptance_rejected',True,{'rejection':conflict,'conflicts':c.call(base+'/conflicts')})
 c.call(base+'/sources/withdraw',{'sourceKind':'WIKI_RAW','sourceRef':sources[1],'reason':'T09 source invalidation acceptance','operationId':op()})
 history=c.call(base+'/statements/'+other['id']+'/revisions')
 check('withdrawn_source_excluded',not search() and history['revisions'][-1]['supportStatus']=='SUPPORT_LOST',{'facts':search(),'history':history})
 try:c.call(base+'/evidence/'+evidence[1])
 except HTTPError as e:check('withdrawn_evidence_hidden',e.code==404,{'httpStatus':e.code})
 else:raise AssertionError('withdrawn evidence unexpectedly visible')
 result['status']='PASS';persist();print('PASS: '+str(len(result['checks']))+' persistent lifecycle checks; semantic_context tool/model quality not asserted.')
except Exception as e:
 result['status']='FAIL';result['failure']={'type':type(e).__name__,'message':str(e)};persist();raise
