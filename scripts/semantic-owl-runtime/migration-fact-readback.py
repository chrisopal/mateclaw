#!/usr/bin/env python3
"""Verify browser migration/rollback after an isolated runtime restart, without writes."""
import json,runpy,hashlib,subprocess
from pathlib import Path
from urllib.request import urlopen
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'));api=c['api']
r=ROOT/'docs/validation/semantic-owl-runtime'
f=json.loads((r/'migration-fact-fixture.json').read_text());expected=json.loads((r/'migration-fact-rolledback.json').read_text())
g='/semantic/graphs/'+f['graphId']
actual={'graph':api(g),'entities':api(g+'/entities'),'facts':api(g+'/statements?view=trusted'),'history':api(g+'/statements/'+f['statementId']+'/revisions'),'plan':api(g+'/migrations/owl/'+expected['plan']['id'])}
def canonical(value):
    # signatureIris is a Java Set; JVM restarts may reorder its JSON array.
    if isinstance(value,dict):
        return {k:sorted(v) if k=='signatureIris' else canonical(v) for k,v in value.items()}
    if isinstance(value,list):return [canonical(v) for v in value]
    return value
assert canonical(actual)==canonical(expected),'Restart changed persisted migration, entities, facts or revision history'
with urlopen('http://127.0.0.1:18109/actuator/health',timeout=10) as response:assert json.load(response)['status']=='UP'
rows=[v for v in api('/tools') if v['name'] in ['SemanticContextTool','SemanticReasoningTool']]
assert len(rows)==2 and all(v['enabled'] for v in rows)
pid=subprocess.check_output(['lsof','-t','-iTCP:18109','-sTCP:LISTEN'],text=True).strip()
command=subprocess.check_output(['ps','-p',pid,'-o','command='],text=True)
jar=Path(command.split(' -jar ')[1].split(' ')[0])
assert jar.read_bytes()==(ROOT/'mateclaw-server/target/mateclaw-server-2.3.0-SNAPSHOT.jar').read_bytes()
result={'restartReadbackPassed':True,'graphId':f['graphId'],'planId':actual['plan']['id'],'status':actual['plan']['status'],'factRevisions':len(actual['history']['revisions']),'allReadbackFieldsUnchanged':True,'normalization':'Sort only signatureIris (declared Set); every other field and revision order compared exactly','evidenceId':f['evidenceId'],'readOnlyToolsRemainEnabled':True,'runtimeJar':str(jar),'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'scope':'Isolated H2 file database; no main database changes'}
(ROOT/'docs/validation/semantic-owl-execution/m8-fact-restart.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print('Restart readback passed: plan, graph, entities, facts, four revisions and evidence unchanged')
