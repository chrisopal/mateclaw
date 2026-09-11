"""Read-only verification of persisted T09 UI/API records after runtime restart."""
import json, sys
from pathlib import Path
out = Path(__file__).resolve().parent
sys.path.insert(0, str(out.parent / '2026-09-11-b2'))
from runtime_client import Client
c = Client()
fixture = json.loads((out / 'sample-fixture.json').read_text())
c.workspace = fixture['workspaceId']
base = '/semantic/graphs/' + fixture['graphId']
history = c.call(base + '/statements/2098323105969745921/revisions')
evidence = c.call(base + '/evidence/2098323105923608578')
assert history['revisions'][0]['subjectId'] == fixture['entityA']
assert evidence['exactQuote'] == fixture['uniqueQuote']
assert history['revisions'][0]['ontologyRevisionId'] == c.call(base)['binding']['ontologyRevisionId']
c.workspace = '2098268037262319617'
review = c.call('/semantic/graphs/2098268824352825345/statements/2098311584749334530/revisions')
assert [r['reviewStatus'] for r in review['revisions']] == ['PROPOSED', 'ACCEPTED', 'RETRACTED']
result = {'pass': True, 'sampleHistory': history, 'sampleEvidence': evidence, 'reviewHistory': review}
(out / 'post-restart.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
print('PASS: sample identity, pinned revision, exact evidence and three review revisions survived restart')
