package vip.mate.semantic.owl;

import vip.mate.semantic.core.ontology.OntologyDocumentException;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Entity;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Expands bounded internal XML entities before handing an inert document to OWLAPI. */
final class RdfXmlInput {
    private RdfXmlInput() {}

    static String normalize(String text, vip.mate.semantic.core.ontology.OntologyDocumentSyntax syntax) {
        return syntax == vip.mate.semantic.core.ontology.OntologyDocumentSyntax.RDF_XML ? normalize(text) : text;
    }

    static String normalize(String text) {
        if (!text.toLowerCase(Locale.ROOT).contains("<!doctype")) return text;
        try {
            var factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setAttribute("jdk.xml.entityExpansionLimit", "10000");
            factory.setAttribute("jdk.xml.totalEntitySizeLimit", "1048576");
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML entities are disabled"); });
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
                @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
            });
            var document = builder.parse(new InputSource(new StringReader(text)));
            var dtd = document.getDoctype();
            if (dtd != null) {
                if (dtd.getSystemId() != null || dtd.getPublicId() != null)
                    throw new SAXException("External DTD declarations are disabled");
                if (dtd.getInternalSubset() != null && dtd.getInternalSubset().contains("%"))
                    throw new SAXException("XML parameter entities are disabled");
                var entities = dtd.getEntities();
                for (int i = 0; i < entities.getLength(); i++) {
                    var entity = (Entity) entities.item(i);
                    if (entity.getSystemId() != null || entity.getPublicId() != null)
                        throw new SAXException("External XML entity declarations are disabled");
                }
                document.removeChild(dtd);
            }
            var transformers = TransformerFactory.newDefaultInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var output = new StringWriter();
            transformers.newTransformer().transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (Exception exception) {
            throw new OntologyDocumentException(OntologyDocumentException.Kind.PARSE_ERROR,
                    "RDF/XML internal entity processing failed or exceeded its safety limits", exception);
        }
    }
}
