"""Read-only verification of B2 fixtures in the persistent local acceptance database."""
import json
from runtime_client import Client, OUT

client = Client()
fixture = json.loads((OUT / 'runtime-fixture.json').read_text())
results = {}
for name, key in [('natural', 'naturalTaskId'), ('source', 'sourceTaskId')]:
    task = client.call('/semantic/modeling-tasks/' + fixture[key])
    proposal = task['proposals'][-1]
    assert proposal['status'] == 'ACCEPTED'
    draft = client.call('/semantic/ontologies/' + task['ontologyId'] + '/draft')
    text = draft['document']['source']['documentText']
    assert 'SubClassOf(' in text and 'DataPropertyRange(' in text and 'xsd:string' in text
    assert 'EQ-QA-001' not in text and 'ObjectMaxCardinality(' not in text
    assert draft['id'] == task['draftId']
    assert draft['document']['documentDigest'] == proposal['result']['draft']['document']['documentDigest']
    assert len(task['sources']) == (2 if name == 'source' else 0)
    if name == 'source':
        assert len(proposal['input']['questions']) == len(proposal['answers']) == 1
    else:
        assert all(e['origin'] == 'USER_STATEMENT' for e in proposal['input']['evidence'])
    results[name] = {'taskId': task['id'], 'ontologyId': task['ontologyId'],
                     'draftId': draft['id'], 'draftVersion': draft['draftVersion'],
                     'documentDigest': draft['document']['documentDigest'],
                     'proposalStatus': proposal['status'], 'sourceCount': len(task['sources'])}
(OUT / 'persistent-readback.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
print(json.dumps(results, ensure_ascii=False))
