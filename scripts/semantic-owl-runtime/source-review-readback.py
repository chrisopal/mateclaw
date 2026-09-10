#!/usr/bin/env python3
"""Verify synthetic source review persistence and the packaged enterprise UI."""
import json,runpy,hashlib,subprocess,zipfile
from pathlib import Path
from urllib.request import urlopen
ROOT=Path(__file__).resolve().parents[2]
c=runpy.run_path(str(ROOT/'scripts/semantic-owl-runtime/post-restart.py'));api=c['api']
r=ROOT/'docs/validation/semantic-owl-runtime';f=json.loads((r/'source-review-fixture.json').read_text());expected=json.loads((r/'source-review-browser-readback.json').read_text());p='/semantic/ontologies/'+f['ontologyId']
reviews=api(p+'/source-reviews');current=[v for v in reviews if v['sourceState']=='UNAVAILABLE']
actual={'published':api(p+'/revisions/'+f['revisionId']),'reviews':reviews,'bindings':api(p+'/revisions/'+f['revisionId']+'/axiom-sources'),'comparisons':[api(p+'/source-reviews/'+v['id']+'/snapshots') for v in current],'noDraftCreated':not api(p)['hasDraft']}
def canonical(v):
    if isinstance(v,dict):return {k:sorted(x) if k=='signatureIris' else canonical(x) for k,x in v.items()}
    if isinstance(v,list):return [canonical(x) for x in v]
    return v
assert canonical(actual)==canonical(expected),'Persisted review/source/published document changed'
with urlopen('http://127.0.0.1:18109/actuator/health',timeout=10) as response:assert json.load(response)['status']=='UP'
pid=subprocess.check_output(['lsof','-t','-iTCP:18109','-sTCP:LISTEN'],text=True).strip();command=subprocess.check_output(['ps','-p',pid,'-o','command='],text=True);jar=Path(command.split(' -jar ')[1].split(' ')[0])
assert jar.read_bytes()==(ROOT/'mateclaw-server/target/mateclaw-server-2.3.0-SNAPSHOT.jar').read_bytes()
index=(ROOT/'mateclaw-server/src/main/resources/static/index.html').read_bytes()
with zipfile.ZipFile(jar) as archive:assert archive.read('BOOT-INF/classes/static/index.html')==index
result={'restartReadbackPassed':True,'ontologyId':f['ontologyId'],'revisionId':f['revisionId'],'decisions':[v['decision'] for v in current],'noDraftCreated':actual['noDraftCreated'],'publishedAndSnapshotsUnchanged':True,'jarSha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'enterpriseIndexSha256':hashlib.sha256(index).hexdigest(),'scope':'Isolated H2; synthetic source only; no main database changes'}
(ROOT/'docs/validation/semantic-owl-execution/m7-source-restart.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print('M7 restart readback passed: decisions, original snapshots and publication unchanged')
