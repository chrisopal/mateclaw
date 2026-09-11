"""Prepare a current, pending source binding for real UI acknowledgement."""
import json,sys,uuid
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
f=json.loads((OUT/'fixture.json').read_text());c=Client();c.workspace=f['workspaceId']
target=OUT/'source-fixture.json'
if target.exists() and "--fresh" not in sys.argv:print('Source fixture exists');sys.exit()
kb=c.call('/wiki/knowledge-bases',{'name':'T07 模型参考资料','description':'Synthetic source gate acceptance'})
quote='传感器是一种设备。'
material=c.call('/wiki/knowledge-bases/'+str(kb['id'])+'/raw/text',{'title':'设备分类说明','content':quote})
p='/semantic/ontologies/'+f['cases']['consistent']['ontologyId'];d=c.call(p+'/draft')
source=c.call(p+'/source-material?knowledgeBaseId='+str(kb['id'])+'&sourceRef='+str(material['id']))
axiom=next(a for a in d['document']['axioms'] if a['axiomType']=='SubClassOf')
bound=c.call(p+'/draft/axiom-sources',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'axiomId':axiom['axiomId'],'knowledgeBaseId':str(kb['id']),'sourceRef':str(material['id']),'expectedSourceDigest':source['sourceDigest'],'startCodePoint':0,'endCodePoint':len(quote),'exactQuote':quote,'origin':'EXPERT'})
target.write_text(json.dumps({'knowledgeBaseId':str(kb['id']),'sourceRef':str(material['id']),'bound':bound},ensure_ascii=False,indent=2));print('Current pending source bound; browser must acknowledge')
