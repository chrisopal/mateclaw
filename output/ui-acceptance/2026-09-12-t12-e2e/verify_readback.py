"""Read-only verification of the persisted T12 UI run; no model calls or fixture writes."""
import json
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(OUT.parent / '2026-09-11-b2'))
from runtime_client import Client

c = Client()
c.workspace = '2098771185501519874'
g = '/semantic/graphs/2098803418832609281'
oid = '2098802182863495170'
revisions = c.call('/semantic/ontologies/' + oid + '/revisions')
assert sorted(r['version'] for r in revisions) == [1, 2, 3]
by_version = {r['version']: r for r in revisions}
assert [len(by_version[v]['document']['axioms']) for v in [1, 2, 3]] == [12, 14, 16]
assert not by_version[1]['availableForNewBindings']
assert by_version[2]['availableForNewBindings']
binding = c.call('/semantic/knowledge-bases/2098803255409942529/binding')
assert binding['ontologyVersion'] == 2 and binding['enabled']
entities = c.call(g + '/entities')['items']
assert {e['displayName'] for e in entities} == {'EQ-01', 'L-01'}
history = c.call(g + '/statements/2098805277643333633/revisions')['revisions']
assert [r['revision'] for r in history] == [1, 2, 3, 4]
assert [r['reviewStatus'] for r in history] == ['PROPOSED', 'ACCEPTED', 'ACCEPTED', 'ACCEPTED']
assert [r['ontologyRevisionId'] for r in history] == [by_version[v]['id'] for v in [2, 2, 3, 2]]
assert all(r['evidenceIds'] == ['2098805277496532993'] for r in history)
trusted = c.call(g + '/statements?view=trusted')['items']
assert trusted == []
sources = c.call(g + '/sources')['items']
assert len(sources) == 1 and sources[0]['state'] == 'WITHDRAWN'
reviews = c.call(g + '/source-changes/7cd492f7-933a-45de-a6c5-55b7c0d4e160/items')
assert len(reviews) == 1
assert reviews[0]['reviewState'] == 'REVIEWED' and reviews[0]['decision'] == 'KEEP_HISTORICAL'
assert reviews[0]['newDigest'] == 'SOURCE_UNAVAILABLE'
plans = [c.call(g + '/migrations/owl/' + pid) for pid in [
    '80601af6-1235-4ed4-b590-f2d4efbf8170', '820d22e5-8df9-4401-b4b9-d154ef59f4ff']]
assert all(p['status'] == 'ROLLED_BACK' for p in plans)
assert plans[1]['impact']['entities'] == 2 and plans[1]['impact']['facts'] == 1
messages = c.call('/conversations/conv_1789228307596_isab6e/messages')
answer = [m for m in messages if m['role'] == 'assistant'][-1]
calls = answer['metadata']['toolCalls']
results = [json.loads(t['result']) for t in calls if t['name'] == 'semantic_search' and t['success']]
hit = next(r for r in results if r['facts'])
fact = hit['facts'][0]
assert fact['id'] == '2098776967022428161' and fact['revision'] == 2
assert fact['evidenceIds'] == ['2098776966951124994'] and fact['validityKind'] == 'UNKNOWN'
assert '泵' in hit['termLabels'].values() and '不能' in answer['content']
original = c.call('/semantic/graphs/2098772602979139585/statements?view=trusted')['items']
assert len(original) == 1 and original[0]['id'] == fact['id']
result = {
    'status': 'PASS', 'ontologyId': oid, 'versions': [1, 2, 3],
    'axiomCounts': [12, 14, 16], 'binding': binding, 'entities': entities,
    'relationHistory': history, 'trustedAfterWithdrawal': trusted,
    'sources': sources, 'sourceReviews': reviews, 'migrations': plans,
    'query': {'conversationId': 'conv_1789228307596_isab6e', 'actualToolCalls': len(calls),
              'factId': fact['id'], 'revision': fact['revision'], 'businessLabel': '泵',
              'validity': 'UNKNOWN', 'unsupportedTodayClaimRejected': True},
    'originalEquipmentFixtureStillTrusted': True,
}
(OUT / 'readback-results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
print(json.dumps({'status': 'PASS', 'versions': 3, 'entities': 2, 'relationRevisions': 4,
                  'sourceDecision': 'KEEP_HISTORICAL', 'trustedAfterWithdrawal': 0}, ensure_ascii=False))
