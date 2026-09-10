#!/usr/bin/env python3
"""Index the pinned W3C archive; never equate selection or indexing with passed tests."""
import collections,hashlib,json
from pathlib import Path
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'docs/validation/semantic-owl-w3c'
p=OUT/'all.rdf';manifest=json.loads((OUT/'source.json').read_text())
assert hashlib.sha256(p.read_bytes()).hexdigest()==manifest['sha256'],'W3C archive digest changed'
r=ET.parse(p).getroot();ns='{http://www.w3.org/2007/OWL/testOntology#}';rdf='{http://www.w3.org/1999/02/22-rdf-syntax-ns#}'
def resources(node,field):return [e.get(rdf+'resource','').rsplit('#',1)[-1] for e in node.findall(ns+field)]
rows=[]
for c in r.findall(ns+'TestCase'):
 status=resources(c,'status');semantics=resources(c,'semantics');species=resources(c,'species')
 reason=None if 'Approved' in status and 'DIRECT' in semantics and 'DL' in species else 'outside-approved-direct-dl-selection'
 texts={e.tag.split('}')[-1]:e.text or '' for e in c if e.tag.startswith(ns) and e.tag.endswith('Ontology') and e.text}
 rows.append({'id':c.findtext(ns+'identifier'),'iri':c.get(rdf+'about'),'status':status,'semantics':semantics,'species':species,'types':[e.get(rdf+'resource','').rsplit('#',1)[-1] for e in c.findall(rdf+'type')],'normativeSyntax':resources(c,'normativeSyntax'),'inputFields':list(texts),'hasImports':bool(c.findall(ns+'importedOntology')),'selection':'selected' if reason is None else 'excluded','reason':reason,'execution':'NOT_RUN'})
assert len({x['id'] for x in rows})==len(rows),'Duplicate test identifiers'
(OUT/'case-index.json').write_text(json.dumps({'sourceSha256':manifest['sha256'],'cases':rows},indent=2))
print(json.dumps({'total':len(rows),'selected':sum(x['selection']=='selected' for x in rows),'execution':'NOT_RUN'}))
