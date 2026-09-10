import { expect, it } from 'vitest'
import { editableDefinition, definitionEdits } from '../definitionEditing'
import type { AxiomDescriptor } from '../../api/types'
const a=(rendering:string,annotations:AxiomDescriptor['annotations']=[]):AxiomDescriptor=>({axiomId:'old',axiomType:rendering.split('(')[0]!,rendering,annotations,signatureIris:['urn:p'],logical:true})
it('replaces only the chosen domain and preserves subject identity',()=>{
 const original=a('ObjectPropertyDomain(<urn:p> <urn:A>)')
 expect(editableDefinition(original,'urn:p','objectProperty')).toMatchObject({kind:'ObjectPropertyDomain',value:'urn:A'})
 expect(definitionEdits('urn:p','ObjectPropertyDomain','urn:B','',original)).toEqual([{kind:'REMOVE',axiomId:'old'},{kind:'ADD',functionalSyntax:'ObjectPropertyDomain(<urn:p> <urn:B>)'}])
})
it('does not flatten annotated, nested or unrelated axioms',()=>{
 expect(editableDefinition(a('ObjectPropertyDomain(<urn:p> ObjectUnionOf(<urn:A> <urn:B>))'),'urn:p','objectProperty')).toBeNull()
 expect(editableDefinition(a('SubClassOf(<urn:p> <urn:A>)',[{propertyIri:'urn:source',valueRendering:'"x"',nestedAnnotations:[]}]),'urn:p','class')).toBeNull()
 expect(editableDefinition(a('SubClassOf(<urn:other> <urn:p>)'),'urn:p','class')).toBeNull()
 expect(editableDefinition(a('SubClassOf(<urn:p> <urn:A>)'),'urn:p','objectProperty')).toBeNull()
})
it('edits multilingual labels and adds comments using escaped literals',()=>{
 expect(editableDefinition(a('AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#label> <urn:p> "名字"@zh-CN)'),'urn:p','class')).toMatchObject({kind:'label',value:'名字',language:'zh-CN'})
 expect(definitionEdits('urn:p','comment','说明 "A"','zh')[0]?.functionalSyntax).toBe('AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#comment> <urn:p> "说明 \\"A\\""@zh)')
})
it('rejects injection, changed subjects and annotated replacement',()=>{
 expect(()=>definitionEdits('urn:p','SubClassOf','urn:A>) Evil(','')).toThrow()
 expect(()=>definitionEdits('urn:p','label','x','zh) Evil')).toThrow()
 expect(()=>definitionEdits('urn:p','SubClassOf','urn:B','',a('SubClassOf(<urn:other> <urn:A>)'))).toThrow()
})

import { projectOntology } from '../ontologyProjection'
it('uses exact language, primary language, then untagged display fallback without changing IRI',()=>{
 const items=[a('Declaration(Class(<urn:p>))'),a('AnnotationAssertion(rdfs:label <urn:p> "English"@en)'),a('AnnotationAssertion(rdfs:label <urn:p> "中文"@zh)'),a('AnnotationAssertion(rdfs:label <urn:p> "Default")')]
 expect(projectOntology(items,'zh-CN').nodes[0]?.label).toBe('中文')
 expect(projectOntology(items,'en').nodes[0]?.label).toBe('English')
 expect(projectOntology(items,'fr').nodes[0]?.label).toBe('Default')
 expect(projectOntology(items,'zh').nodes[0]?.iri).toBe('urn:p')
})

it('recognizes standard datatype prefixes emitted by the server',()=>{
 expect(editableDefinition(a('DataPropertyRange(<urn:p> xsd:decimal)'),'urn:p','dataProperty')).toMatchObject({kind:'DataPropertyRange',value:'http://www.w3.org/2001/XMLSchema#decimal'})
})
it('treats normalized server prefixes and language case as unchanged',()=>{
 expect(definitionEdits('urn:p','label','名字','zh-CN',a('AnnotationAssertion(rdfs:label <urn:p> "名字"@zh-cn)'))).toEqual([])
 expect(definitionEdits('urn:p','DataPropertyRange','http://www.w3.org/2001/XMLSchema#decimal','',a('DataPropertyRange(<urn:p> xsd:decimal)'))).toEqual([])
})
