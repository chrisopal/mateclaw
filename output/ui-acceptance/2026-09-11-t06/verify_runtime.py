"""Read-only draft logic checks with independent draft/binding readback."""
import json,sys
from pathlib import Path
OUT=Path(__file__).resolve().parent
sys.path.insert(0,str(OUT.parent/'2026-09-11-b2'))
from runtime_client import Client
c=Client();f=json.loads((OUT/'fixture.json').read_text());c.workspace=f['workspaceId'];results={}
for key,item in f['cases'].items():
 p='/semantic/ontologies/'+item['ontologyId'];before=c.call(p+'/draft');bindings=c.call(p+'/bindings')
 result=c.call(p+'/draft/reason',{'expectedDraftVersion':before['draftVersion']},timeout=120)
 assert c.call(p+'/draft')==before,'Reasoning changed draft'
 assert c.call(p+'/bindings')==bindings,'Reasoning created binding'
 if key=='consistent': assert result['consistent'] is True and not result['unsatisfiableClasses'],result
 elif key=='unsatisfiable': assert result['consistent'] is True and 'urn:t06:Sensor' in result['unsatisfiableClasses'],result
 else: assert result['consistent'] is False,result
 results[key]=result
(OUT/'runtime-results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print('PASS: four real-worker draft cases; drafts and bindings unchanged')
