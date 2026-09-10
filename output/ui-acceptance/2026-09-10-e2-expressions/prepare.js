async(page)=>{
 return page.evaluate(async()=>{
  const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId;
  const imported='Ontology(<urn:e2:lib> Declaration(Class(<urn:e2:Imported>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:e2:Imported> "导入定义"@zh))';
  const digest=[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(imported)))].map(x=>x.toString(16).padStart(2,'0')).join('');
  const text=`Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)
Ontology(<urn:e2:expressions> Import(<urn:e2:lib>)
 Declaration(Class(<urn:e2:Machine>)) Declaration(Class(<urn:e2:Probe>)) Declaration(Class(<urn:e2:Equipment>))
 Declaration(ObjectProperty(<urn:e2:uses>)) Declaration(ObjectProperty(<urn:e2:contains>)) Declaration(ObjectProperty(<urn:e2:related>))
 Declaration(DataProperty(<urn:e2:reading>))
 AnnotationAssertion(rdfs:label <urn:e2:Machine> "机器"@zh)
 AnnotationAssertion(rdfs:label <urn:e2:Machine> "Machine"@en)
 AnnotationAssertion(rdfs:label <urn:e2:Probe> "测针"@zh)
 AnnotationAssertion(rdfs:label <urn:e2:uses> "使用"@zh)
 SubClassOf(<urn:e2:Machine> ObjectIntersectionOf(<urn:e2:Equipment> ObjectSomeValuesFrom(<urn:e2:uses> ObjectUnionOf(<urn:e2:Probe> ObjectComplementOf(<urn:e2:Equipment>)))))
 SubClassOf(<urn:e2:Machine> ObjectAllValuesFrom(<urn:e2:uses> ObjectSomeValuesFrom(<urn:e2:contains> <urn:e2:Probe>)))
 SubClassOf(<urn:e2:Machine> ObjectMinCardinality(2 <urn:e2:uses> <urn:e2:Probe>))
 SubClassOf(<urn:e2:Machine> ObjectMaxCardinality(3 <urn:e2:uses> <urn:e2:Probe>))
 SubClassOf(<urn:e2:Machine> ObjectExactCardinality(2 <urn:e2:uses> <urn:e2:Probe>))
 SubClassOf(<urn:e2:Machine> DataSomeValuesFrom(<urn:e2:reading> xsd:decimal))
 SubClassOf(<urn:e2:Machine> DataAllValuesFrom(<urn:e2:reading> xsd:decimal))
 SubClassOf(<urn:e2:Machine> DataExactCardinality(1 <urn:e2:reading> xsd:decimal))
 SubObjectPropertyOf(ObjectPropertyChain(<urn:e2:uses> <urn:e2:contains> <urn:e2:uses>) <urn:e2:related>)
 HasKey(<urn:e2:Machine> (<urn:e2:uses>) ())
)`;
  const o=await a.create(ws,{name:'E2 复杂表达式验收 '+Date.now(),description:'Isolated synthetic projection fixture'}),d=await a.createDraft(ws,o.id);
  const saved=await a.saveDraft(ws,o.id,{expectedDraftVersion:d.draftVersion,name:o.name,description:o.description,operationId:crypto.randomUUID(),document:{modelSchema:'owl-document-v1',syntax:'FUNCTIONAL',documentText:text,imports:[{requestedIri:'urn:e2:lib',resolvedOntologyIri:'urn:e2:lib',syntax:'FUNCTIONAL',documentText:imported,contentDigest:digest,artifactId:'e2-pinned-library'}],policy:{version:'1',rules:[]}}});
  const report=await a.validate(ws,o.id,saved.draftVersion);if(!report.valid)throw Error(JSON.stringify(report));
  const revision=await a.publish(ws,o.id,{expectedDraftVersion:saved.draftVersion,operationId:crypto.randomUUID(),note:'Synthetic projection acceptance fixture'});
  const draft=await a.createDraft(ws,o.id,revision.id);
  return {ontologyId:o.id,revisionId:revision.id,draftId:draft.id,draftVersion:draft.draftVersion,documentDigest:draft.document.documentDigest,importLockDigest:draft.document.importLockDigest};
 });
}
