"""Validate OWL-01 specification artifacts only; never run OWL or touch a database."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib
import json
import re
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
matrix = json.loads((HERE / 'capability-matrix.json').read_text())
rows = matrix['rows']
assert len(rows) == 87
assert len({r['id'] for r in rows}) == len(rows)
assert len({r['construct'] for r in rows}) == len(rows)
tests = [t for r in rows for t in r['tests']]
assert len(tests) == len(set(tests)) == 435
for row in rows:
    assert row['current'] == 'not-implemented'
    assert row['positive_spec'] and row['negative_spec']
    assert all(row[key] for key in ('parse','save','edit','export','validate','reason','ai'))
manifest = json.loads((HERE / 'fixture-manifest.json').read_text())
assert len(manifest) == 11
assert len({r['id'] for r in manifest}) == len(manifest)
for item in manifest:
    assert (HERE / item['file']).is_file()
    assert item['expected'] and item['semantic_execution'] == 'NOT_RUN'
ET.parse(HERE / 'fixtures/domain.rdf')
docs = [*HERE.glob('*.md'),
    ROOT / 'docs/adr/0003-owl2-dl-authority-and-reset.md',
    ROOT / 'docs/architecture/2026-09-08-owl-01-dependencies.md',
    ROOT / 'docs/superpowers/plans/2026-09-08-owl-02-and-followups.md']
checked_links = 0
for doc in docs:
    assert doc.is_file(), doc
    for target in re.findall(r'\]\(([^)]+)\)', doc.read_text()):
        if target.startswith(('http:', 'https:', '#')):
            continue
        assert (doc.parent / target.split('#')[0]).exists(), (doc, target)
        checked_links += 1
artifacts = sorted([p for p in HERE.rglob('*') if p.is_file() and p.name != 'static-verification.json'] + docs[-3:])
result = {
    'checkedAt': datetime.now(timezone.utc).isoformat(),
    'result': 'PASS_STATIC_ONLY',
    'constructRows': len(rows), 'specifiedTestIds': len(tests),
    'functionalFixturesPresent': len(manifest), 'wellFormedXmlFiles': 1,
    'localLinksChecked': checked_links,
    'notRun': ['OWL parsing/profile/round-trip', 'reasoner', 'Maven/Java compatibility', 'UI/business tests', 'database reset'],
    'sha256': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in artifacts}
}
(HERE / 'static-verification.json').write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n')
print(json.dumps({k:v for k,v in result.items() if k!='sha256'},ensure_ascii=False))
