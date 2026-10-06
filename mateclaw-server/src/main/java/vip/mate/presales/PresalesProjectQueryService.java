package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import vip.mate.presales.PresalesDtos.Page;
import vip.mate.presales.repository.PresalesProjectRepository;

/** Authorized project-list query; persistence owns the atomic projection read. */
@Service
@ConditionalOnProperty(name = "mateclaw.presales.enabled", havingValue = "true")
public class PresalesProjectQueryService {
    private final PresalesProjectRepository projects;
    private final ObjectMapper json;
    private final PresalesAccess access;

    public PresalesProjectQueryService(
            PresalesProjectRepository projects, ObjectMapper json, PresalesAccess access) {
        this.projects = projects;
        this.json = json;
        this.access = access;
    }

    public Page list(
            String scope,
            String query,
            String status,
            String ownerId,
            String stageFilter,
            int page,
            int size) {
        access.require(scope, "viewer");
        if (page < 1 || size < 1 || size > 100)
            throw new PresalesRejected(400, "INVALID_REQUEST", "Invalid pagination");
        var rows =
                projects.listProjected(
                        scope,
                        new PresalesProjectRepository.ListingQuery(
                                query == null || query.isEmpty()
                                        ? null
                                        : PresalesListingProjectionV1.searchKey(query),
                                listingFilterKey(status),
                                listingFilterKey(ownerId),
                                listingFilterKey(stageFilter),
                                (long) (page - 1) * size,
                                size,
                                PresalesListingProjectionV1.CONTRACT_VERSION));
        if (rows.failureType() != null) {
            switch (rows.failureType()) {
                case "STAGE", "SUMMARY" ->
                        throw new IllegalArgumentException(
                                "Invalid project listing collection: " + rows.failureDetail());
                case "DECODE" ->
                        throw new IllegalStateException(
                                "Invalid project body (" + rows.failureDetail() + ")");
                default ->
                        throw new IllegalStateException("Project listing projection is not ready");
            }
        }
        return new Page(
                rows.summaries().stream().map(this::decode).toList(), rows.total(), page, size);
    }

    private static String listingFilterKey(String value) {
        return value == null || value.isBlank() ? null : PresalesListingProjectionV1.key(value);
    }

    private ObjectNode decode(String body) {
        try {
            return (ObjectNode) json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
