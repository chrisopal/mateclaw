package vip.mate.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import vip.mate.auth.service.ActorResolver;
import vip.mate.delivery.repository.DeliveryHandoffRepository;
import vip.mate.presales.api.PresalesHandoffReader;
import vip.mate.presales.api.PresalesHandoffReader.Handoff;
import vip.mate.workspace.core.service.WorkspaceAccessService;

@Service
public class DeliveryHandoffService {
    private final DeliveryHandoffRepository repository;
    private final ActorResolver actors;
    private final WorkspaceAccessService workspaces;
    private final ObjectProvider<PresalesHandoffReader> presales;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public DeliveryHandoffService(
            DeliveryHandoffRepository repository,
            ActorResolver actors,
            WorkspaceAccessService workspaces,
            ObjectProvider<PresalesHandoffReader> presales,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.actors = actors;
        this.workspaces = workspaces;
        this.presales = presales;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public record Receive(String operationId, String projectId, String releaseId, String digest) {}

    public record Accepted(
            String id, String projectId, String releaseId, String digest, JsonNode snapshot) {}

    public Handoff preview(String workspace, String project, String release) {
        require(workspace, "viewer");
        identifier(project);
        identifier(release);
        return source(workspace, project, release);
    }

    public Accepted receive(String workspace, Receive input) {
        String actor = require(workspace, "member");
        if (input == null) throw error(400, "INVALID_REQUEST");
        identifier(input.operationId());
        identifier(input.projectId());
        identifier(input.releaseId());
        if (input.digest() == null || !input.digest().matches("[a-f0-9]{64}"))
            throw error(400, "INVALID_REQUEST");
        try {
            return transaction.execute(status -> receiveInTransaction(workspace, actor, input));
        } catch (DuplicateKeyException conflict) {
            // The failed transaction has rolled back. Re-read the winning operation in a fresh
            // transaction, including current identity and source authorization before replay.
            return transaction.execute(
                    status -> {
                        requireActor(workspace, actor);
                        var row =
                                repository
                                        .findOperation(workspace, actor, input.operationId())
                                        .orElseThrow(() -> conflict);
                        return replay(workspace, input, row);
                    });
        }
    }

    private Accepted receiveInTransaction(String workspace, String actor, Receive input) {
        requireActor(workspace, actor);
        var old = repository.findOperation(workspace, actor, input.operationId());
        if (old.isPresent()) return replay(workspace, input, old.get());
        Handoff source = source(workspace, input.projectId(), input.releaseId());
        if (!source.digest().equals(input.digest())) throw error(409, "HANDOFF_DIGEST_MISMATCH");
        var row =
                new DeliveryHandoffRepository.Row(
                        UUID.randomUUID().toString(),
                        workspace,
                        actor,
                        input.operationId(),
                        input.projectId(),
                        input.releaseId(),
                        source.digest(),
                        source.snapshotJson());
        repository.insert(row);
        return view(row);
    }

    private Accepted replay(String workspace, Receive input, DeliveryHandoffRepository.Row row) {
        if (!row.projectId().equals(input.projectId())
                || !row.releaseId().equals(input.releaseId())
                || !row.digest().equals(input.digest())) throw error(409, "OPERATION_CONFLICT");
        return checked(workspace, row);
    }

    private void requireActor(String workspace, String actor) {
        if (!actor.equals(require(workspace, "member"))) throw error(403, "FORBIDDEN");
    }

    public Accepted get(String workspace, String id) {
        require(workspace, "viewer");
        identifier(id);
        return checked(
                workspace,
                repository.findById(workspace, id).orElseThrow(() -> error(404, "NOT_FOUND")));
    }

    private Accepted checked(String workspace, DeliveryHandoffRepository.Row row) {
        Handoff source = source(workspace, row.projectId(), row.releaseId());
        if (!row.digest().equals(source.digest())
                || !row.digest().equals(Handoff.digestOf(row.snapshotJson())))
            throw error(409, "HANDOFF_DIGEST_MISMATCH");
        return view(row);
    }

    private Handoff source(String workspace, String project, String release) {
        var reader = presales.getIfAvailable();
        if (reader == null) throw error(409, "PRESALES_UNAVAILABLE");
        final Handoff result;
        try {
            result = reader.read(workspace, project, release);
        } catch (PresalesHandoffReader.Unavailable unavailable) {
            throw error(unavailable.status(), unavailable.code());
        }
        if (!project.equals(result.projectId())
                || !release.equals(result.releaseId())
                || !result.digest().equals(Handoff.digestOf(result.snapshotJson())))
            throw error(409, "HANDOFF_DIGEST_MISMATCH");
        return result;
    }

    private Accepted view(DeliveryHandoffRepository.Row row) {
        try {
            JsonNode snapshot = json.readTree(row.snapshotJson());
            if (snapshot == null || !snapshot.isObject()) throw error(409, "HANDOFF_INVALID");
            return new Accepted(row.id(), row.projectId(), row.releaseId(), row.digest(), snapshot);
        } catch (JsonProcessingException invalid) {
            throw error(409, "HANDOFF_INVALID");
        }
    }

    private String require(String workspace, String role) {
        long scope;
        try {
            scope = Long.parseLong(workspace);
            if (scope <= 0) throw new NumberFormatException();
        } catch (RuntimeException invalid) {
            throw error(400, "WORKSPACE_REQUIRED");
        }
        final vip.mate.auth.model.UserEntity user;
        try {
            user = actors.requireCurrent();
        } catch (ActorResolver.Denied denied) {
            throw error(401, "UNAUTHENTICATED");
        }
        if (workspaces.findActiveWorkspace(scope) == null) throw error(404, "NOT_FOUND");
        if (!"admin".equalsIgnoreCase(user.getRole())
                && !workspaces.hasMinimumRole(scope, user.getId(), role))
            throw error(403, "FORBIDDEN");
        return user.getId().toString();
    }

    private static void identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 128)
            throw error(400, "INVALID_REQUEST");
    }

    private static DeliveryRejected error(int status, String code) {
        return new DeliveryRejected(status, code, code);
    }
}
