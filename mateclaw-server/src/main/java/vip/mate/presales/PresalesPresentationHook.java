package vip.mate.presales;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Optional presentation preparation performed before an employee run is committed.
 *
 * <p>The hook is deliberately small so the durable run coordinator does not depend on the
 * presentation renderer's storage details. A configured S6 run must have a hook; silently
 * accepting a result without preparing its presentation is a terminal failure.
 */
@FunctionalInterface
public interface PresalesPresentationHook {
  ObjectNode prepare(ObjectNode result, String scope, String projectId, String runId);
}
