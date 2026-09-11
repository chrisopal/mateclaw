"""Synthetic draft reasoning models in the existing persistent QA database."""
import json,sys,uuid
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();file=OUT/'fixture.json'
if file.exists() and "--fresh" not in sys.argv: print(file.read_text());sys.exit()
w=c.call('/workspaces',{'name':'T07 发布门禁验收','description':'Synthetic draft reasoning acceptance'})
c.workspace=str(w['id'])
base='Declaration(Class(<urn:t07:Equipment>)) Declaration(Class(<urn:t07:Sensor>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:t07:Equipment> "设备"@zh-CN) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:t07:Sensor> "传感器"@zh-CN)'
cases={'consistent':('正常设备模型',base+' SubClassOf(<urn:t07:Sensor> <urn:t07:Equipment>)'), 'unsatisfiable':('无法成立的设备类型',base+' DisjointClasses(<urn:t07:Equipment> <urn:t07:Sensor>) SubClassOf(<urn:t07:Sensor> <urn:t07:Equipment>)'), 'inconsistent':('矛盾的模型',base+' SubClassOf(<http://www.w3.org/2002/07/owl#Thing> <http://www.w3.org/2002/07/owl#Nothing>)'), 'abox':('实例断言矛盾',base+' DisjointClasses(<urn:t07:Equipment> <urn:t07:Sensor>) Declaration(NamedIndividual(<urn:t07:device1>)) ClassAssertion(<urn:t07:Equipment> <urn:t07:device1>) ClassAssertion(<urn:t07:Sensor> <urn:t07:device1>)')}
result={'workspaceId':c.workspace,'cases':{}}
for key,(name,axioms) in cases.items():
 o=c.call('/semantic/ontologies',{'name':name,'description':'T07 synthetic '+key});oid=str(o['id']);p='/semantic/ontologies/'+oid
 d=c.call(p+'/draft',{'baseRevisionId':None})
 document={'modelSchema':'owl-document-v1','syntax':'FUNCTIONAL','documentText':'Ontology(<urn:t07:'+key+'> '+axioms+')','imports':[],'policy':{'version':'t07-qa','rules':[]}}
 saved=c.call(p+'/draft',{'expectedDraftVersion':d['draftVersion'],'operationId':str(uuid.uuid4()),'name':name,'description':'T07 synthetic '+key,'document':document},'PUT')
 result['cases'][key]={'ontologyId':oid,'draftVersion':saved['draftVersion']}
 file.write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps(result,ensure_ascii=False))
