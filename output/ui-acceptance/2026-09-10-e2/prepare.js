async(page)=>{
 return page.evaluate(async()=>{
  const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId;
  const imported='Ontology(<urn:e2:lib> Declaration(Class(<urn:e2:Imported>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:e2:Imported> "导入定义"@zh))';
  const digest=[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(imported)))].map(x=>x.toString(16).padStart(2,'0')).join('');
  const text=`Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
Ontology(<urn:e2:root> Import(<urn:e2:lib>)
 Declaration(Class(<urn:e2:Equipment>)) Declaration(Class(<urn:e2:Machine>)) Declaration(Class(<urn:e2:Probe>))
 Declaration(NamedIndividual(<urn:e2:Equipment>))
 Declaration(ObjectProperty(<urn:e2:uses>)) Declaration(ObjectProperty(<urn:e2:usedBy>)) Declaration(ObjectProperty(<urn:e2:contains>))
 AnnotationAssertion(rdfs:label <urn:e2:Equipment> "设备"@zh)
 AnnotationAssertion(rdfs:label <urn:e2:Equipment> "Equipment"@en)
 AnnotationAssertion(rdfs:label <urn:e2:Machine> "机器"@zh)
 AnnotationAssertion(rdfs:label <urn:e2:Probe> "测针"@zh)
 AnnotationAssertion(rdfs:label <urn:e2:uses> "使用"@zh)
 AnnotationAssertion(rdfs:label <urn:e2:usedBy> "被使用"@zh)
 EquivalentClasses(<urn:e2:Equipment> <urn:e2:Machine>)
 DisjointClasses(<urn:e2:Machine> <urn:e2:Probe>)
 InverseObjectProperties(<urn:e2:uses> <urn:e2:usedBy>)
 FunctionalObjectProperty(<urn:e2:uses>)
 TransitiveObjectProperty(<urn:e2:contains>)
 ObjectPropertyDomain(<urn:e2:uses> <urn:e2:Machine>)
 ObjectPropertyRange(<urn:e2:uses> <urn:e2:Probe>)
 SubClassOf(<urn:e2:Machine> ObjectAllValuesFrom(<urn:e2:uses> <urn:e2:Probe>))
)`;
  const o=await a.create(ws,{name:'E2 标准投影验收 '+Date.now(),description:'Isolated synthetic projection fixture'}),d=await a.createDraft(ws,o.id);
  const saved=await a.saveDraft(ws,o.id,{expectedDraftVersion:d.draftVersion,name:o.name,description:o.description,operationId:crypto.randomUUID(),document:{modelSchema:'owl-document-v1',syntax:'FUNCTIONAL',documentText:text,imports:[{requestedIri:'urn:e2:lib',resolvedOntologyIri:'urn:e2:lib',syntax:'FUNCTIONAL',documentText:imported,contentDigest:digest,artifactId:'e2-pinned-library'}],policy:{version:'1',rules:[]}}});
  const report=await a.validate(ws,o.id,saved.draftVersion);if(!report.valid)throw Error(JSON.stringify(report));
  const revision=await a.publish(ws,o.id,{expectedDraftVersion:saved.draftVersion,operationId:crypto.randomUUID(),note:'Synthetic projection acceptance fixture'});
  const draft=await a.createDraft(ws,o.id,revision.id);
  return {ontologyId:o.id,revisionId:revision.id,draftId:draft.id,draftVersion:draft.draftVersion,documentDigest:draft.document.documentDigest,importLockDigest:draft.document.importLockDigest};
 });
}
