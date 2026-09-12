"""Read back manual case; remove only this run's unchanged temporary draft."""
import sys,json
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'2026-09-11-b2'))
from runtime_client import Client
from urllib.error import HTTPError
c=Client();c.workspace='2098771185501519874'
o='/semantic/ontologies/2098771602272731138'
revisions=c.call(o+'/revisions');assert len(revisions)==1 and revisions[0]['version']==1
try: draft=c.call(o+'/draft')
except HTTPError as e:
 if e.code!=404:raise
 draft=None
cleanup=False
if draft:
 assert str(draft['id'])=='2098823160222007298','Not the temporary draft created for this manual'
 assert draft['baseRevisionId']==revisions[0]['id'] and draft['draftVersion']==3,'Draft changed; retain it'
 assert sorted(a['rendering'] for a in draft['document']['axioms'])==sorted(a['rendering'] for a in revisions[0]['document']['axioms']),'Model changed; retain it'
 assert all(draft['document']['source'][k]==revisions[0]['document']['source'][k] for k in ['imports','policy']),'Policy/imports changed; retain it'
 c.call(o+'/draft?expectedDraftVersion='+str(draft['draftVersion']),method='DELETE');cleanup=True
try:
 c.call(o+'/draft');raise AssertionError('Draft still exists')
except HTTPError as e:assert e.code==404
binding=c.call('/semantic/knowledge-bases/2098771185526685698/binding');assert binding['ontologyVersion']==1 and binding['enabled']
facts=c.call('/semantic/graphs/2098772602979139585/statements?view=trusted')['items'];assert len(facts)==1 and facts[0]['revision']==2 and facts[0]['reviewStatus']=='ACCEPTED'
result={'status':'PASS','temporaryDraftRemoved':cleanup,'publishedVersions':[1],'knowledgeBaseVersion':binding['ontologyVersion'],'trustedFacts':1,'factRevision':2,'evidenceIds':facts[0]['evidenceIds']}
Path(__file__).with_name('readback-result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
