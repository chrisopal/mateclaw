package vip.mate.presales.architecturefixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.bidding.BiddingRepository;
import vip.mate.presales.PresalesService;
import vip.mate.presales.repository.architecturefixture.QueryPort;

/** Deliberate test-only bytecode inputs; excluded from production architecture imports. */
public final class ControllerCanaries {
    private ControllerCanaries() {}

    public static final class ForbiddenJdbcController {
        public JdbcTemplate jdbc;
    }

    public static final class ForbiddenRootRepositoryController {
        public BiddingRepository repository;
    }

    public static final class ForbiddenRepositoryPackageController {
        public QueryPort repository;
    }

    public static final class AllowedSerializationController {
        public ObjectMapper json;
        public PresalesService application;
    }
}
