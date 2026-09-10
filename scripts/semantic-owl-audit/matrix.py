#!/usr/bin/env python3
"""Index explicit evidence against the frozen matrix; never infer success from totals."""
import collections,hashlib,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'docs/validation/semantic-owl-execution'
MATRIX=ROOT/'docs/validation/semantic-owl-01/capability-matrix.json'
rows=json.loads(MATRIX.read_text())['rows']
items=[]
def entry(row,kind,status,evidence,remaining):
    for name in evidence:
        json.loads((BASE/name).read_text())
    items.append({'id':row['id']+'-'+kind,'construct':row['construct'],'status':status,
        'evidence':evidence,'remaining':remaining})
for row in rows:
    number=int(row['id'][4:])
    for kind in ['P','N','RT','EDIT','CTX']:
        status='MISSING_DIRECT_EVIDENCE';evidence=[];remaining='Needs direct evidence for this specification.'
        if number<=78 and kind in ['P','RT','EDIT']:
            status='VERIFIED_EXAMPLE';evidence=['matrix-remove-preservation.json']
            remaining='Frozen concrete example covered by adapter. Cross-cutting parameter dimensions and persistence/UI paths remain separately audited.'
        if 5<=number<=78 and kind=='CTX':
            status='VERIFIED_EXAMPLE';evidence=['context-matrix-published.json','context-matrix-mysql.json']
            remaining='Frozen example saved/published/bound/paginated; not exhaustive parameter dimensions or real LLM effectiveness.'
        if number in [1,2] and kind=='CTX':
            status='VERIFIED_EXAMPLE';evidence=['context-standard-identity.json','context-standard-identity-runtime.json']
            remaining='Pinned standard ontology/version IRI and absent version checked; broader identity negative requirements remain separate.'
        if number==4 and kind=='CTX':
            status='VERIFIED_EXAMPLE';evidence=['context-prefix-unicode.json'];remaining='Two prefix aliases, Unicode and escaped language label; no exhaustive lexical combinations.'
        if number==3 and kind=='CTX':
            status='PARTIAL';evidence=['context-import-annotations.json','context-annotation-mysql.json'];remaining='Imported metadata origin verified; add direct full imported axiom/closure trace evidence.'
        if 15<=number<=37 and number!=32 and kind=='N':
            status='PARTIAL';evidence=['matrix-missing-operands.json'];remaining=row['negative_spec']+'; missing-operand syntax checks alone do not prove these semantic conditions.'
        if 72<=number<=78 and kind=='N':
            status='PARTIAL';evidence=['matrix-annotation-no-entailment.json','matrix-remove-preservation.json'];remaining='Nonlogical consequence and preservation checked; map each annotation placement/nesting requirement explicitly.'
        boundary={79:['matrix-global-restriction-negatives.json','matrix-global-publish-gate.json','matrix-global-publish-mysql.json'],80:['matrix-role-regularity.json','matrix-role-regularity-mysql.json'],81:['matrix-reserved-vocabulary.json'],86:['context-cardinality-dimensions.json']}
        if number in boundary:
            status='PARTIAL';evidence=boundary[number];remaining='Boundary-specific evidence only. Do not treat a profile rejection or context example as all P/N/RT/EDIT/CTX paths.'
        entry(row,kind,status,evidence,remaining)
assert len(items)==435 and len({x['id'] for x in items})==435
counts=dict(collections.Counter(x['status'] for x in items))
result={'schema':'owl-matrix-evidence-audit-v1','matrixSha256':hashlib.sha256(MATRIX.read_bytes()).hexdigest(),
    'counts':counts,'overallComplete':False,'interpretation':'VERIFIED_EXAMPLE is bounded evidence, not a full row or full OWL certification. Missing means not mapped here, not necessarily absent implementation.',
    'items':items}
(BASE/'matrix-evidence-audit.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(counts))
