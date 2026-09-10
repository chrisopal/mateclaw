async(userPage)=>{const page=await userPage.context().newPage();try {await page.goto(userPage.url());await page.locator(".model-term").first().waitFor(); const fixture=await (async(page)=>{
 return page.evaluate(async()=>{
  const {ontologyApi:a}=await import('/src/features/semantic/api/ontologyApi.ts');const {useWorkspaceStore:w}=await import('/src/stores/useWorkspaceStore.ts');const ws=w().currentWorkspaceId;
  const imported='Ontology(<urn:e3b:lib> Declaration(Class(<urn:e3b:Imported>)) AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:e3b:Imported> "导入定义"@zh))';
  const digest=[...new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(imported)))].map(x=>x.toString(16).padStart(2,'0')).join('');
  const text=`Prefix(rdfs:=<http://www.w3.org/2000/01/rdf-schema#>)
Prefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)
Ontology(<urn:e3b:expressions> Import(<urn:e3b:lib>)
 Declaration(Class(<urn:e3b:Machine>)) Declaration(Class(<urn:e3b:Probe>)) Declaration(Class(<urn:e3b:Equipment>))
 Declaration(ObjectProperty(<urn:e3b:uses>)) Declaration(ObjectProperty(<urn:e3b:contains>)) Declaration(ObjectProperty(<urn:e3b:related>))
 Declaration(DataProperty(<urn:e3b:reading>))
 AnnotationAssertion(rdfs:label <urn:e3b:Machine> "机器"@zh)
 AnnotationAssertion(rdfs:label <urn:e3b:Machine> "Machine"@en)
 AnnotationAssertion(rdfs:label <urn:e3b:Probe> "测针"@zh)
 AnnotationAssertion(rdfs:label <urn:e3b:uses> "使用"@zh)
 SubClassOf(<urn:e3b:Machine> ObjectIntersectionOf(<urn:e3b:Equipment> ObjectSomeValuesFrom(<urn:e3b:uses> ObjectUnionOf(<urn:e3b:Probe> ObjectComplementOf(<urn:e3b:Equipment>)))))
 SubClassOf(<urn:e3b:Machine> ObjectAllValuesFrom(<urn:e3b:uses> ObjectSomeValuesFrom(<urn:e3b:contains> <urn:e3b:Probe>)))
 SubClassOf(<urn:e3b:Machine> DataSomeValuesFrom(<urn:e3b:reading> xsd:decimal))
 SubClassOf(<urn:e3b:Machine> DataAllValuesFrom(<urn:e3b:reading> xsd:decimal))
 SubClassOf(<urn:e3b:Machine> DataExactCardinality(1 <urn:e3b:reading> xsd:decimal))
 SubObjectPropertyOf(ObjectPropertyChain(<urn:e3b:uses> <urn:e3b:contains> <urn:e3b:uses>) <urn:e3b:related>)
 SubClassOf(Annotation(rdfs:comment "keep annotated") <urn:e3b:Machine> ObjectSomeValuesFrom(<urn:e3b:contains> <urn:e3b:Probe>))
 HasKey(<urn:e3b:Machine> (<urn:e3b:uses>) ())
)`;
  const o=await a.create(ws,{name:'E3b 规则编辑验收 '+Date.now(),description:'Isolated synthetic projection fixture'}),d=await a.createDraft(ws,o.id);
  const saved=await a.saveDraft(ws,o.id,{expectedDraftVersion:d.draftVersion,name:o.name,description:o.description,operationId:crypto.randomUUID(),document:{modelSchema:'owl-document-v1',syntax:'FUNCTIONAL',documentText:text,imports:[{requestedIri:'urn:e3b:lib',resolvedOntologyIri:'urn:e3b:lib',syntax:'FUNCTIONAL',documentText:imported,contentDigest:digest,artifactId:'e3b-pinned-library'}],policy:{version:'1',rules:[]}}});
  const report=await a.validate(ws,o.id,saved.draftVersion);if(!report.valid)throw Error(JSON.stringify(report));
  const revision=await a.publish(ws,o.id,{expectedDraftVersion:saved.draftVersion,operationId:crypto.randomUUID(),note:'Synthetic projection acceptance fixture'});
  const draft=await a.createDraft(ws,o.id,revision.id);
  return {ontologyId:o.id,revisionId:revision.id,draftId:draft.id,draftVersion:draft.draftVersion,documentDigest:draft.document.documentDigest,importLockDigest:draft.document.importLockDigest};
 });
})(page);await page.goto('http://127.0.0.1:5189/semantic/ontologies/'+fixture.ontologyId+'/edit');await page.locator('.model-term').filter({hasText:'机器'}).click();await page.getByRole('button',{name:'编辑概念规则',exact:true}).click();await page.getByRole('dialog').waitFor();return {fixture,text:await page.getByRole('dialog').innerText()};}finally{await page.close();await userPage.bringToFront();}}