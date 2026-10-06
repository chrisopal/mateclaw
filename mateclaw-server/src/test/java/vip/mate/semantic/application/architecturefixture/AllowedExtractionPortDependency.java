package vip.mate.semantic.application.architecturefixture;

import vip.mate.semantic.application.extraction.ExtractionPorts;

/** A consumer-owned pure port is allowed; database/framework adapters remain forbidden. */
public final class AllowedExtractionPortDependency {
    public ExtractionPorts.ExtractionTaskRepository tasks;
}
