package vip.mate.presales;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vip.mate.workspace.core.service.ProjectSourceAccess;

/** Task scope is built exclusively from server-authorized project material bindings. */
@Component
public class PresalesContextProvider {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PresalesAccess access;
    private final ProjectSourceAccess sourceAccess;

    public PresalesContextProvider(
            JdbcTemplate jdbc,
            ObjectMapper json,
            PresalesAccess access,
            ProjectSourceAccess sourceAccess) {
        this.jdbc = jdbc;
        this.json = json;
        this.access = access;
        this.sourceAccess = sourceAccess;
    }

    public ObjectNode snapshot(String scope, ObjectNode project, String skill, String goal) {
        String actor = access.require(scope, "member");
        ObjectNode out = json.createObjectNode();
        out.put("workspaceId", scope)
                .put("caseRef", project.path("id").asText())
                .put("actorId", actor)
                .put("projectVersion", project.path("version").asInt())
                .put("skill", skill)
                .put("taskGoal", goal)
                .put("skillVersion", "1.0.0")
                .put("schemaVersion", 1);
        ObjectNode records = out.putObject("operational_record");
        for (String key : List.of("name", "customer", "industry", "goal"))
            records.set(key, project.path(key));
        for (String key :
                List.of(
                        "requirements",
                        "clarifications",
                        "baselines",
                        "fitGaps",
                        "cases",
                        "solutions")) out.set(key, project.path(key).deepCopy());
        if ("S6".equals(skill) || "S7".equals(skill)) {
            var solutions = project.path("solutions");
            if (!solutions.isArray() || solutions.isEmpty())
                throw PresalesModelAdapter.error(409, "SOLUTION_REQUIRED");
            var target = solutions.get(solutions.size() - 1).deepCopy();
            out.remove("solutions");
            out.putArray("solutions").add(target);
            out.put("targetSolutionId", target.path("id").asText());
        }
        // Reviewer never receives the author's self-assessment, prior model reviews or chat
        // history.
        var sources = out.putArray("sources");
        int budget = 60000;
        boolean truncated = false;
        Set<String> seen = new HashSet<>();
        for (var binding : project.path("materials")) {
            String kb = binding.path("kbId").asText();
            if (!sourceAccess.canEmployeeReadKb(scope, project.path("agentId").asText(), kb))
                throw PresalesModelAdapter.error(409, "SOURCE_UNAVAILABLE");
            var rows =
                    jdbc.queryForList(
                            "SELECT id,title,COALESCE(NULLIF(extracted_text,''),original_content) AS source_text FROM mate_wiki_raw_material WHERE kb_id=? AND deleted=0 ORDER BY id LIMIT 101",
                            kb);
            if (rows.size() > 100) truncated = true;
            for (var row : rows.stream().limit(100).toList()) {
                String sourceRef = row.get("id").toString();
                if (!seen.add(sourceRef)) continue;
                String graph = binding.path("graphId").asText();
                if (!graph.isBlank()) {
                    Integer withdrawn =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND source_id=? AND state='WITHDRAWN'",
                                    Integer.class,
                                    graph,
                                    sourceRef);
                    if (withdrawn != null && withdrawn > 0)
                        throw PresalesModelAdapter.error(409, "SOURCE_WITHDRAWN");
                }
                String text = Objects.toString(row.get("source_text"), "");
                String digest =
                        PresalesArtifactRenderer.digest(text.getBytes(StandardCharsets.UTF_8));
                if (text.isBlank()) {
                    truncated = true;
                    continue;
                }
                int take = Math.min(text.length(), Math.max(0, budget));
                if (take > 0
                        && take < text.length()
                        && Character.isHighSurrogate(text.charAt(take - 1))) take--;
                if (take < text.length()) truncated = true;
                budget -= take;
                sources.addObject()
                        .put("sourceRef", sourceRef)
                        .put("kbId", kb)
                        .put("title", Objects.toString(row.get("title"), ""))
                        .put("digest", digest)
                        .put("graphId", graph)
                        .put("role", binding.path("role").asText())
                        .put("text", text.substring(0, take));
            }
        }
        out.put("truncated", truncated).put("needsHumanReview", true);
        return out;
    }

    public void revalidate(String scope, ObjectNode project, ObjectNode snapshot) {
        access.requireActor(scope, snapshot.path("actorId").asText(), "member");
        if (project.path("version").asInt() != snapshot.path("projectVersion").asInt())
            throw PresalesModelAdapter.error(409, "VERSION_CONFLICT");
        for (var source : snapshot.path("sources")) {
            if (!sourceAccess.canEmployeeReadKb(
                    scope, project.path("agentId").asText(), source.path("kbId").asText()))
                throw PresalesModelAdapter.error(409, "SOURCE_UNAVAILABLE");
            if (!source.path("graphId").asText().isBlank()) {
                Integer withdrawn =
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mate_semantic_source_governance WHERE graph_id=? AND source_id=? AND state='WITHDRAWN'",
                                Integer.class,
                                source.path("graphId").asText(),
                                source.path("sourceRef").asText());
                if (withdrawn != null && withdrawn > 0)
                    throw PresalesModelAdapter.error(409, "SOURCE_WITHDRAWN");
            }
            var rows =
                    jdbc.queryForList(
                            "SELECT COALESCE(NULLIF(r.extracted_text,''),r.original_content) AS source_text "
                                    + "FROM mate_wiki_raw_material r JOIN mate_wiki_knowledge_base k ON k.id=r.kb_id "
                                    + "WHERE r.id=? AND r.kb_id=? AND k.workspace_id=? AND r.deleted=0 AND k.deleted=0",
                            source.path("sourceRef").asText(),
                            source.path("kbId").asText(),
                            scope);
            if (rows.size() != 1
                    || !source.path("digest")
                            .asText()
                            .equals(
                                    PresalesArtifactRenderer.digest(
                                            Objects.toString(rows.getFirst().get("source_text"), "")
                                                    .getBytes(StandardCharsets.UTF_8))))
                throw PresalesModelAdapter.error(409, "SOURCE_CHANGED");
        }
    }
}
