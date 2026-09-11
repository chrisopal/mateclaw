"""Create an accepted synthetic modeling sample and live repeated-quote source for UI QA."""
import sys,json,uuid,hashlib
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
op=lambda:str(uuid.uuid4())
f=json.loads((OUT/'lifecycle-results.json').read_text());c=Client();c.workspace=f['workspaceId'];kb=f['knowledgeBaseId'];base='/semantic/graphs/'+f['graphId']
text='车间甲记录：同名设备属于设备。序列号A，核对人为张工。\n车间乙记录：同名设备属于设备。序列号B，核对人为李工。'
raw=c.call('/wiki/knowledge-bases/'+kb+'/raw/text',{'title':'T09 合成建模样例：同句不同上下文','content':text})
imported=c.call(base+'/imports',{'sourceKind':'WIKI_RAW','sourceRef':str(raw['id']),'operationId':op()})
task=c.call('/semantic/modeling-tasks',{'operationId':op(),'ontologyId':f['ontologyId'],'baseRevisionId':f['ontologyRevisionId'],'goal':'T09 synthetic sample for Equipment identity and repeated quote review','sources':[{'knowledgeBaseId':kb,'sourceRef':str(raw['id']),'sourceDigest':hashlib.sha256(text.encode()).hexdigest()}]})
d=c.call('/semantic/ontologies/'+f['ontologyId']+'/draft')
sample={'name':'T09 合成样例：同名设备A','subject':'同名设备','serial':'A','type':'urn:t09:Equipment','description':'人工合成验收样例，非真实模型输出；核对车间甲原文。'}
pending=c.call('/semantic/modeling-tasks/'+task['id']+'/proposals',{'operationId':op(),'expectedDraftVersion':d['draftVersion'],'changes':[{'kind':'CREATE_TERM','termKind':'OBJECT','clientId':'qa_sample_marker','name':'T09样例核对标记'}],'evidence':[],'questions':[],'samples':[sample]})
proposal=pending['proposals'][-1]
accepted=c.call('/semantic/modeling-tasks/'+task['id']+'/proposals/'+proposal['id']+'/decision',{'operationId':op(),'decision':'ACCEPT'})
read=c.call('/semantic/modeling-tasks/'+task['id']);assert read['proposals'][-1]['status']=='ACCEPTED';assert read['proposals'][-1]['input']['samples']==[sample]
r={'synthetic':True,'workspaceId':c.workspace,'workspaceName':'T09 API 生命周期 '+f['run'],'ontologyId':f['ontologyId'],'graphId':f['graphId'],'knowledgeBaseId':kb,'entityA':f['entityA'],'entityB':f['entityB'],'taskId':task['id'],'proposalId':proposal['id'],'snapshotId':imported['snapshotId'],'sourceRef':str(raw['id']),'sourceTitle':'T09 合成建模样例：同句不同上下文','sourceText':text,'ambiguousQuote':'同名设备属于设备。','uniqueQuote':'车间甲记录：同名设备属于设备。','sample':sample,'acceptedReadback':read}
(OUT/'sample-fixture.json').write_text(json.dumps(r,ensure_ascii=False,indent=2));print(json.dumps({k:v for k,v in r.items() if k!='acceptedReadback'},ensure_ascii=False,indent=2))
