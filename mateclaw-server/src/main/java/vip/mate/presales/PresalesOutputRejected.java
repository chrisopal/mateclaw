package vip.mate.presales;
import com.fasterxml.jackson.databind.node.ObjectNode;
import vip.mate.semantic.web.SemanticApiException;
/** Preserve rejected untrusted output for authorized task diagnostics; never project it as business data. */
final class PresalesOutputRejected extends SemanticApiException {
  private final ObjectNode rejected;
  PresalesOutputRejected(SemanticApiException cause,ObjectNode rejected){super(cause.status(),cause.code(),cause.getMessage());this.rejected=rejected.deepCopy();}
  ObjectNode rejected(){return rejected.deepCopy();}
}
