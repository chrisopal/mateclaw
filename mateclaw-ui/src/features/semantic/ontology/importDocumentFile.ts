import type { OntologyPackage } from '../api/types'

/** Wrap standard documents without parsing or weakening server OWL/import validation. */
export function importDocumentFile(filename: string, text: string): { packageData: OntologyPackage; rawPackage: string } {
  if (/\.json$/i.test(filename)) {
    const parsed = JSON.parse(text) as OntologyPackage
    if (parsed?.packageFormatVersion !== 2 || !parsed.document || !parsed.name) throw new Error('invalid package')
    // Preserve exact JSON so duplicate keys still reach strict server validation.
    return { packageData: parsed, rawPackage: text }
  }
  const syntax = /\.(ofn|fss)$/i.test(filename) ? 'FUNCTIONAL' : /\.(rdf|owl|xml)$/i.test(filename) ? 'RDF_XML' : null
  if (!syntax || !text.trim()) throw new Error('unsupported or empty ontology file')
  const packageData: OntologyPackage = {
    packageFormatVersion: 2,
    name: filename.replace(/\.[^.]+$/, '').slice(0, 128), description: '',
    document: { modelSchema: 'owl-document-v1', syntax, documentText: text, imports: [], policy: { version: '1', rules: [] } },
  }
  return { packageData, rawPackage: JSON.stringify(packageData) }
}
