import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline, allowlist-only RESET utility for the OWL semantic test fixture.
 *
 * This class intentionally has no Spring or application dependency.  It works
 * against a supplied JDBC URL, defaults to a read-only plan, and refuses a
 * mutating run unless the operator explicitly confirms that all semantic
 * writers have been stopped outside this process.
 */
public final class SemanticOwlReset {
  private static final Set<String> SEMANTIC_PREFIXES = Set.of("MATE_SEMANTIC_");
  private static final Set<String> SUPPORTED_TABLES = Set.of(
      "MATE_SEMANTIC_ONTOLOGY", "MATE_SEMANTIC_ONTOLOGY_REVISION", "MATE_SEMANTIC_GRAPH", "MATE_SEMANTIC_ENTITY",
      "MATE_SEMANTIC_SOURCE_SNAPSHOT", "MATE_SEMANTIC_IMPORT_JOB", "MATE_SEMANTIC_EVIDENCE", "MATE_SEMANTIC_STATEMENT",
      "MATE_SEMANTIC_STATEMENT_REVISION", "MATE_SEMANTIC_REVISION_EVIDENCE", "MATE_SEMANTIC_CHANGE_PROPOSAL",
      "MATE_SEMANTIC_CONFLICT", "MATE_SEMANTIC_SOURCE_GOVERNANCE", "MATE_SEMANTIC_SNAPSHOT_EXCLUSION",
      "MATE_SEMANTIC_MUTATION_COMMAND", "MATE_SEMANTIC_GOVERNANCE_EVENT", "MATE_SEMANTIC_EXTRACTION_GATE",
      "MATE_SEMANTIC_EXTRACTION_TASK", "MATE_SEMANTIC_EXTRACTION_ATTEMPT", "MATE_SEMANTIC_EXTRACTION_SUGGESTION",
      "MATE_SEMANTIC_EXTRACTION_EDIT", "MATE_SEMANTIC_EXTRACTION_RECEIPT", "MATE_SEMANTIC_EXTRACTION_SUBMISSION_INTENT",
      "MATE_SEMANTIC_EXTRACTION_ACTION", "MATE_SEMANTIC_IMPORT_ARTIFACT", "MATE_SEMANTIC_ONTOLOGY_AXIOM",
      "MATE_SEMANTIC_AXIOM_SOURCE", "MATE_SEMANTIC_ONTOLOGY_SOURCE_SNAPSHOT", "MATE_SEMANTIC_ONTOLOGY_SOURCE_REVIEW",
      "MATE_SEMANTIC_COMMAND_RECORD", "MATE_SEMANTIC_GOVERNANCE_RECORD", "MATE_SEMANTIC_SOURCE_CHANGE_RUN",
      "MATE_SEMANTIC_SOURCE_CHANGE_LOCK", "MATE_SEMANTIC_SOURCE_CHANGE", "MATE_SEMANTIC_SOURCE_CHANGE_ITEM",
      "MATE_SEMANTIC_GRAPH_MIGRATION_PLAN");
  private static final Pattern ONTOLOGY_IRI = Pattern.compile("Ontology\\s*\\(\\s*<([^>]+)>", Pattern.CASE_INSENSITIVE);
  private static final Pattern IMPORT_IRI = Pattern.compile("Import\\s*\\(\\s*<([^>]+)>", Pattern.CASE_INSENSITIVE);
  private static final String FENCE = "CONFIRMED_OFFLINE_STOPPED";

  record Config(String jdbcUrl, String user, String password, long workspaceId,
                List<String> ontologyIds, List<String> revisionIds, List<String> graphIds,
                List<Long> kbIds, Path outputDir, String runId, boolean execute,
                boolean rebuild, Path fixturesDir, boolean allowNonH2, long sleepBeforeDeleteMs,
                Path priorManifest, Path restoreBackup) {}

  record Table(String name, String sqlName, List<String> columns, List<String> primaryKeys,
               Map<String, List<String>> foreignKeys) {}

  record RowKey(String table, List<Object> values) {}

  record Plan(Config config, Map<String, Table> tables, Map<String, List<RowKey>> rows,
              Map<String, Integer> counts, Map<String, String> selectedDigests,
              Map<String, String> retainedDigests, Map<String, String> graphGuards,
              String schemaFingerprint, String fingerprint) {}

  record Fixture(String name, String ontologyId, String revisionId, String graphId,
                 long kbId, String text, String digest, String ontologyIri) {}

  public static void main(String[] args) throws Exception {
    try {
      Config config = parse(args);
      enforceSafety(config);
      try (Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password())) {
        connection.setReadOnly(!config.execute());
        if (config.restoreBackup() != null) {
          if (config.priorManifest() == null || !"COMMITTED".equals(manifestStatus(config.priorManifest())))
            throw new ResetRefused("backup restore requires a COMMITTED manifest");
          verifyBackup(connection, config.priorManifest(), config.restoreBackup());
          restore(connection, config, config.restoreBackup());
          System.out.println("RESET backup restored: " + config.restoreBackup().toAbsolutePath());
          return;
        }
        if (config.execute() && config.priorManifest() != null && "COMMITTED".equals(manifestStatus(config.priorManifest()))
            && !targetOntologyPresent(connection, config)) {
          verifyFixturesOnly(connection, config);
          System.out.println("RESET already completed for committed manifest; no changes made");
          return;
        }
        Plan plan = plan(connection, config);
        if (config.execute() && config.priorManifest() != null) {
          String expected = manifestFingerprint(config.priorManifest());
          if (expected == null || !expected.equals(plan.fingerprint()))
            throw new ResetRefused("allowlist closure changed since the supplied dry-run manifest");
        }
        Path runDir = config.outputDir().resolve(config.runId());
        Files.createDirectories(runDir);
        Path backup = runDir.resolve("backup.ndjson");
        String backupDigest = backup(connection, plan, backup);
        Path manifest = runDir.resolve("reset-manifest.json");
        writeManifest(manifest, plan, backup, backupDigest, "PLANNED", List.of("dry-run"));
        System.out.println("RESET plan generated: " + manifest.toAbsolutePath());
        System.out.println("target=" + fingerprintTarget(config) + " tables=" + plan.counts().size()
            + " rows=" + plan.counts().values().stream().mapToInt(Integer::intValue).sum());
        if (!config.execute()) return;
        if (config.sleepBeforeDeleteMs() > 0) Thread.sleep(config.sleepBeforeDeleteMs());
        execute(connection, plan, config, manifest, backup, backupDigest);
        System.out.println("RESET completed: " + manifest.toAbsolutePath());
      }
    } catch (ResetRefused e) {
      System.err.println("RESET REFUSED: " + e.getMessage());
      System.exit(2);
    }
  }

  private static Config parse(String[] args) throws IOException {
    Map<String, List<String>> values = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (!arg.startsWith("--")) throw new ResetRefused("unexpected argument " + arg);
      String key = arg.substring(2);
      if (Set.of("execute", "rebuild", "allow-non-h2").contains(key)) {
        values.computeIfAbsent(key, ignored -> new ArrayList<>()).add("true");
      } else {
        if (++i >= args.length) throw new ResetRefused("missing value for --" + key);
        values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[i]);
      }
    }
    String jdbc = first(values, "jdbc-url", System.getenv("SEMANTIC_RESET_JDBC_URL"));
    if (jdbc == null || jdbc.isBlank()) throw new ResetRefused("--jdbc-url or SEMANTIC_RESET_JDBC_URL is required");
    long workspace = parseLong(first(values, "workspace-id", null), "workspace-id");
    List<String> ontologies = split(first(values, "ontology-id", null));
    List<String> revisions = split(first(values, "revision-id", ""));
    List<String> graphs = split(first(values, "graph-id", null));
    List<Long> kbs = split(first(values, "kb-id", null)).stream().map(v -> parseLong(v, "kb-id")).toList();
    if (ontologies.isEmpty() || graphs.isEmpty() || kbs.isEmpty())
      throw new ResetRefused("workspace, ontology, graph and kb allowlists are required");
    String output = first(values, "output-dir", "docs/validation/semantic-owl-reset/runs");
    String run = first(values, "run-id", "reset-" + Instant.now().toString().replaceAll("[^0-9A-Za-z]", ""));
    String fixtures = first(values, "fixtures-dir", "docs/validation/semantic-owl-reset/fixtures");
    String priorManifest = first(values, "manifest", null);
    String restoreBackup = first(values, "restore-backup", null);
    if (restoreBackup != null && !values.containsKey("execute"))
      throw new ResetRefused("--restore-backup requires --execute and the offline writer fence");
    String passwordEnv = first(values, "password-env", "SEMANTIC_RESET_DB_PASSWORD");
    String password = System.getenv().getOrDefault(passwordEnv, "");
    return new Config(jdbc, first(values, "user", "sa"), password, workspace, ontologies, revisions,
        graphs, kbs, Path.of(output), run, values.containsKey("execute"), values.containsKey("rebuild"),
        Path.of(fixtures), values.containsKey("allow-non-h2"),
        Long.parseLong(first(values, "sleep-before-delete-ms", "0")),
        priorManifest == null ? null : Path.of(priorManifest),
        restoreBackup == null ? null : Path.of(restoreBackup));
  }

  private static void enforceSafety(Config c) {
    if (c.execute() && !c.jdbcUrl().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:")) {
      if (!c.allowNonH2()) throw new ResetRefused("mutating non-H2 targets require --allow-non-h2 and an offline fence");
      String normalized = c.jdbcUrl().toLowerCase(Locale.ROOT);
      if (!normalized.contains("semantic_reset") && !normalized.contains("owl_reset") && !normalized.contains("semantic-reset") && !normalized.contains("owl-reset"))
        throw new ResetRefused("mutating non-H2 targets must name an isolated semantic_reset/owl_reset database");
    }
    if (c.execute() && c.jdbcUrl().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:") && !c.jdbcUrl().toLowerCase(Locale.ROOT).startsWith("jdbc:h2:mem:")) {
      String normalized = c.jdbcUrl().toLowerCase(Locale.ROOT);
      if (!normalized.contains("semantic-reset") && !normalized.contains("owl-reset"))
        throw new ResetRefused("mutating H2 file targets must contain semantic-reset or owl-reset in their path");
    }
    String fence = System.getenv("SEMANTIC_RESET_WRITER_FENCE");
    if (c.execute() && !FENCE.equals(fence))
      throw new ResetRefused("SEMANTIC_RESET_WRITER_FENCE must equal " + FENCE + " for offline execution");
    if (c.execute() && c.outputDir().toAbsolutePath().normalize().startsWith(Path.of("/Users").resolve("guojiexie/Development/mateclaw/data").normalize()))
      throw new ResetRefused("output path may not be the application data directory");
  }

  private static Plan plan(Connection c, Config config) throws SQLException {
    Map<String, Table> tables = loadTables(c);
    for (String table : tables.keySet()) if (!SUPPORTED_TABLES.contains(table)) throw new ResetRefused("unknown semantic table requires a schema-aware RESET update: " + table);
    requireTables(tables, Set.of("MATE_SEMANTIC_ONTOLOGY", "MATE_SEMANTIC_ONTOLOGY_REVISION", "MATE_SEMANTIC_GRAPH"));
    Map<String, Set<String>> ids = new LinkedHashMap<>();
    ids.put("ontology", new LinkedHashSet<>(config.ontologyIds()));
    ids.put("revision", new LinkedHashSet<>(config.revisionIds()));
    ids.put("graph", new LinkedHashSet<>(config.graphIds()));
    ids.put("task", new LinkedHashSet<>());
    ids.put("suggestion", new LinkedHashSet<>());
    ids.put("attempt", new LinkedHashSet<>());
    ids.put("statement", new LinkedHashSet<>());
    ids.put("snapshot", new LinkedHashSet<>());
    ids.put("evidence", new LinkedHashSet<>());
    ids.put("binding", new LinkedHashSet<>());
    ids.put("sourceSnapshot", new LinkedHashSet<>());
    ids.put("axiom", new LinkedHashSet<>());
    ids.put("importArtifact", new LinkedHashSet<>());

    validateOwnership(c, config, ids);
    boolean changed;
    do {
      changed = false;
      changed |= collectByColumn(c, tables, ids.get("revision"), "MATE_SEMANTIC_ONTOLOGY_REVISION", "ID", "ONTOLOGY_ID", ids.get("ontology"));
      changed |= collectByColumn(c, tables, ids.get("axiom"), "MATE_SEMANTIC_ONTOLOGY_AXIOM", "AXIOM_ID", "REVISION_ID", ids.get("revision"));
      changed |= collectByColumn(c, tables, ids.get("binding"), "MATE_SEMANTIC_AXIOM_SOURCE", "ID", "REVISION_ID", ids.get("revision"));
      changed |= collectSourceSnapshots(c, ids, config);
      changed |= collectImportArtifacts(c, ids);
      changed |= collectByColumn(c, tables, ids.get("statement"), "MATE_SEMANTIC_STATEMENT", "ID", "GRAPH_ID", ids.get("graph"));
      changed |= collectByColumn(c, tables, ids.get("snapshot"), "MATE_SEMANTIC_SOURCE_SNAPSHOT", "ID", "GRAPH_ID", ids.get("graph"));
      changed |= collectByColumn(c, tables, ids.get("evidence"), "MATE_SEMANTIC_EVIDENCE", "ID", "GRAPH_ID", ids.get("graph"));
      changed |= collectByColumn(c, tables, ids.get("task"), "MATE_SEMANTIC_EXTRACTION_TASK", "ID", "GRAPH_ID", ids.get("graph"));
      changed |= collectByColumn(c, tables, ids.get("suggestion"), "MATE_SEMANTIC_EXTRACTION_SUGGESTION", "ID", "TASK_ID", ids.get("task"));
      changed |= collectByColumn(c, tables, ids.get("attempt"), "MATE_SEMANTIC_EXTRACTION_ATTEMPT", "ID", "TASK_ID", ids.get("task"));
      changed |= collectGenericReferences(c, tables, ids, config);
    } while (changed);

    rejectSharedOntologySnapshots(c, ids, config);
    ensureNoUnallowlistedGraphReferences(c, config, tables, ids);
    validateMigrationPlanReferences(c, tables, ids, config);
    Map<String, List<RowKey>> rows = materializeRows(c, tables, ids, config);
    Map<String, Integer> counts = new TreeMap<>();
    Map<String, String> selectedDigests = new TreeMap<>();
    for (Map.Entry<String, List<RowKey>> e : rows.entrySet()) {
      if (!e.getValue().isEmpty()) {
        counts.put(e.getKey(), e.getValue().size());
        selectedDigests.put(e.getKey(), digestKeys(e.getValue()));
      }
    }
    Map<String, String> retained = retainedDigests(c, config, rows.keySet());
    Map<String, String> graphGuards = graphGuards(c, tables, rows);
    String schemaFingerprint = schemaFingerprint(tables);
    String fingerprint = fingerprint(counts, selectedDigests, graphGuards, schemaFingerprint);
    return new Plan(config, tables, rows, counts, selectedDigests, retained, graphGuards, schemaFingerprint, fingerprint);
  }

  private static void validateOwnership(Connection c, Config config, Map<String, Set<String>> ids) throws SQLException {
    if (config.ontologyIds().stream().anyMatch(String::isBlank) || config.graphIds().stream().anyMatch(String::isBlank))
      throw new ResetRefused("allowlist contains a blank identifier");
    try (PreparedStatement p = c.prepareStatement("SELECT id FROM mate_semantic_ontology WHERE workspace_id=? AND id=?")) {
      for (String id : config.ontologyIds()) { p.setLong(1, config.workspaceId()); p.setString(2, id); if (!exists(p)) throw new ResetRefused("ontology is absent or outside workspace: " + id); }
    }
    try (PreparedStatement p = c.prepareStatement("SELECT id,ontology_id FROM mate_semantic_ontology_revision WHERE id=?")) {
      for (String id : config.revisionIds()) { p.setString(1, id); try (ResultSet r = p.executeQuery()) { if (!r.next() || !config.ontologyIds().contains(r.getString(2))) throw new ResetRefused("revision is absent or outside ontology allowlist: " + id); } }
    }
    try (PreparedStatement p = c.prepareStatement("SELECT id,workspace_id,ontology_revision_id,kb_id FROM mate_semantic_graph WHERE id=?")) {
      for (String id : config.graphIds()) { p.setString(1, id); try (ResultSet r = p.executeQuery()) { if (!r.next() || r.getLong(2) != config.workspaceId() || !config.kbIds().contains(r.getLong(4))) throw new ResetRefused("graph is absent, cross-workspace, or outside KB allowlist: " + id); ids.get("revision").add(r.getString(3)); } }
    }
    try (PreparedStatement p = c.prepareStatement("SELECT id FROM mate_semantic_ontology_revision WHERE ontology_id=?")) {
      for (String ontology : config.ontologyIds()) { p.setString(1, ontology); try (ResultSet r = p.executeQuery()) { while (r.next()) ids.get("revision").add(r.getString(1)); } }
    }
  }

  private static void ensureNoUnallowlistedGraphReferences(Connection c, Config config, Map<String, Table> tables, Map<String, Set<String>> ids) throws SQLException {
    if (!tables.containsKey("MATE_SEMANTIC_GRAPH")) return;
    try (PreparedStatement p = c.prepareStatement("SELECT id FROM mate_semantic_graph WHERE workspace_id=? AND ontology_revision_id IN (SELECT id FROM mate_semantic_ontology_revision WHERE ontology_id IN (" + qmarks(config.ontologyIds().size()) + "))")) {
      int index = 1; p.setLong(index++, config.workspaceId()); for (String id : config.ontologyIds()) p.setString(index++, id);
      try (ResultSet r = p.executeQuery()) { while (r.next()) if (!config.graphIds().contains(r.getString(1))) throw new ResetRefused("selected ontology has an unallowlisted graph reference: " + r.getString(1)); }
    }
  }

  private static void validateMigrationPlanReferences(Connection c, Map<String, Table> tables, Map<String, Set<String>> ids, Config config) throws SQLException {
    Table table = tables.get("MATE_SEMANTIC_GRAPH_MIGRATION_PLAN");
    if (table == null) return;
    String sql = "SELECT ID,GRAPH_ID,SOURCE_REVISION_ID,TARGET_REVISION_ID FROM " + table.sqlName();
    try (Statement statement = c.createStatement(); ResultSet r = statement.executeQuery(sql)) {
      while (r.next()) {
        String graph = r.getString(2), source = r.getString(3), target = r.getString(4);
        boolean graphSelected = ids.get("graph").contains(graph);
        boolean sourceSelected = ids.get("revision").contains(source), targetSelected = ids.get("revision").contains(target);
        if (graphSelected && (!sourceSelected || !targetSelected))
          throw new ResetRefused("M8 migration plan references a revision outside the RESET allowlist: " + r.getString(1));
        if (!graphSelected && (sourceSelected || targetSelected))
          throw new ResetRefused("M8 migration plan for an outside graph references a selected revision: " + r.getString(1));
      }
    }
  }

  private static boolean collectByColumn(Connection c, Map<String, Table> tables, Set<String> target, String table, String targetColumn, String filterColumn, Set<String> filterValues) throws SQLException {
    if (!tables.containsKey(table) || filterValues.isEmpty()) return false;
    Set<String> before = new HashSet<>(target);
    String sql = "SELECT " + targetColumn + " FROM " + tables.get(table).sqlName() + " WHERE " + filterColumn + " IN (" + qmarks(filterValues.size()) + ")";
    try (PreparedStatement p = c.prepareStatement(sql)) { int i = 1; for (String value : filterValues) p.setString(i++, value); try (ResultSet r = p.executeQuery()) { while (r.next()) target.add(String.valueOf(r.getObject(1))); } }
    return !before.equals(target);
  }

  private static boolean collectSourceSnapshots(Connection c, Map<String, Set<String>> ids, Config config) throws SQLException {
    if (ids.get("binding").isEmpty()) return false;
    Set<String> before = new HashSet<>(ids.get("sourceSnapshot"));
    String sql = "SELECT source_snapshot_id FROM mate_semantic_axiom_source WHERE id IN (" + qmarks(ids.get("binding").size()) + ") AND source_snapshot_id IS NOT NULL";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      int i = 1; for (String id : ids.get("binding")) p.setString(i++, id);
      try (ResultSet r = p.executeQuery()) { while (r.next()) ids.get("sourceSnapshot").add(r.getString(1)); }
    }
    if (tableExists(c, "MATE_SEMANTIC_ONTOLOGY_SOURCE_REVIEW")) {
      String reviewSql = "SELECT observed_snapshot_id FROM mate_semantic_ontology_source_review WHERE binding_id IN (" + qmarks(ids.get("binding").size()) + ") AND observed_snapshot_id IS NOT NULL";
      try (PreparedStatement p = c.prepareStatement(reviewSql)) {
        int i = 1; for (String id : ids.get("binding")) p.setString(i++, id);
        try (ResultSet r = p.executeQuery()) { while (r.next()) ids.get("sourceSnapshot").add(r.getString(1)); }
      }
    }
    rejectSharedOntologySnapshots(c, ids, config);
    return !before.equals(ids.get("sourceSnapshot"));
  }

  private static void rejectSharedOntologySnapshots(Connection c, Map<String, Set<String>> ids, Config config) throws SQLException {
    if (ids.get("sourceSnapshot").isEmpty()) return;
    String marks = qmarks(ids.get("sourceSnapshot").size());
    try (PreparedStatement p = c.prepareStatement("SELECT id,revision_id FROM mate_semantic_axiom_source WHERE source_snapshot_id IN (" + marks + ")")) {
      int i = 1; for (String id : ids.get("sourceSnapshot")) p.setString(i++, id);
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          if (!ids.get("binding").contains(r.getString(1)))
            throw new ResetRefused("ontology source snapshot is shared by an outside axiom binding: " + r.getString(1));
        }
      }
    }
    if (tableExists(c, "MATE_SEMANTIC_ONTOLOGY_SOURCE_REVIEW")) {
      try (PreparedStatement p = c.prepareStatement("SELECT binding_id,observed_snapshot_id FROM mate_semantic_ontology_source_review WHERE observed_snapshot_id IN (" + marks + ")")) {
        int i = 1; for (String id : ids.get("sourceSnapshot")) p.setString(i++, id);
        try (ResultSet r = p.executeQuery()) {
          while (r.next()) {
            if (!ids.get("binding").contains(r.getString(1)))
              throw new ResetRefused("observed ontology source snapshot is shared by an outside review: " + r.getString(1));
          }
        }
      }
    }
    for (String table : List.of("MATE_SEMANTIC_SOURCE_CHANGE", "MATE_SEMANTIC_SOURCE_CHANGE_ITEM")) {
      if (!tableExists(c, table)) continue;
      try (PreparedStatement p = c.prepareStatement("SELECT graph_id,old_snapshot_id,new_snapshot_id FROM " + table + " WHERE old_snapshot_id IN (" + marks + ") OR new_snapshot_id IN (" + marks + ")")) {
        int i = 1; for (String id : ids.get("sourceSnapshot")) p.setString(i++, id); for (String id : ids.get("sourceSnapshot")) p.setString(i++, id);
        try (ResultSet r = p.executeQuery()) {
          while (r.next()) {
            if (!ids.get("graph").contains(r.getString(1)))
              throw new ResetRefused("ontology source snapshot is referenced by an outside source-change graph: " + r.getString(1));
          }
        }
      }
    }
  }

  private static boolean collectImportArtifacts(Connection c, Map<String, Set<String>> ids) throws SQLException {
    if (!tableExists(c, "MATE_SEMANTIC_IMPORT_ARTIFACT") || ids.get("revision").isEmpty()) return false;
    Set<String> before = new HashSet<>(ids.get("importArtifact"));
    String sql = "SELECT imports_json FROM mate_semantic_ontology_revision WHERE id IN (" + qmarks(ids.get("revision").size()) + ")";
    try (PreparedStatement p = c.prepareStatement(sql)) { int i=1; for(String id:ids.get("revision"))p.setString(i++,id); try(ResultSet r=p.executeQuery()){while(r.next()){String json=r.getString(1);if(json==null)continue;Matcher m=Pattern.compile("(?:\\\"?artifactId\\\"?|\\\"?artifact_id\\\"?|\\\"?id\\\"?)\\s*[:=]\\s*\\\"([^\\\"]+)\\\"").matcher(json);while(m.find())ids.get("importArtifact").add(m.group(1));}} }
    return !before.equals(ids.get("importArtifact"));
  }

  private static boolean collectGenericReferences(Connection c, Map<String, Table> tables, Map<String, Set<String>> ids, Config config) throws SQLException {
    boolean changed = false;
    for (Table table : tables.values()) {
      if (table.primaryKeys().isEmpty()) continue;
      List<String> referenceColumns = table.columns().stream().filter(col -> referenceTargets(table.name(), col, ids) != null).toList();
      if (referenceColumns.isEmpty()) continue;
      String sql = "SELECT " + String.join(",", table.primaryKeys()) + "," + String.join(",", referenceColumns) + " FROM " + table.sqlName();
      try (Statement statement = c.createStatement(); ResultSet r = statement.executeQuery(sql)) {
        while (r.next()) {
          boolean match = false;
          for (int i = 0; i < referenceColumns.size(); i++) {
            Set<String> targets = referenceTargets(table.name(), referenceColumns.get(i), ids);
            Object value = r.getObject(table.primaryKeys().size() + i + 1);
            if (value != null && targets != null && targets.contains(String.valueOf(value))) match = true;
          }
          if (match) {
            List<Object> key = new ArrayList<>(); for (int i = 1; i <= table.primaryKeys().size(); i++) key.add(r.getObject(i));
            if (pendingRowsFor(table.name(), key) == null) { registerPending(table.name(), new RowKey(table.name(), key)); changed = true; }
          }
        }
      }
    }
    changed |= collectCommandRecords(c, tables, ids, config);
    return changed;
  }

  private static Set<String> referenceTargets(String table, String column, Map<String, Set<String>> ids) {
    return switch (column) {
      case "ONTOLOGY_ID" -> ids.get("ontology");
      case "REVISION_ID", "SOURCE_REVISION_ID", "TARGET_REVISION_ID" -> ids.get("revision");
      case "GRAPH_ID" -> ids.get("graph");
      case "TASK_ID" -> ids.get("task");
      case "SUGGESTION_ID" -> ids.get("suggestion");
      case "ATTEMPT_ID" -> ids.get("attempt");
      case "STATEMENT_ID" -> ids.get("statement");
      case "SNAPSHOT_ID" -> ids.get("snapshot");
      case "SOURCE_SNAPSHOT_ID", "OBSERVED_SNAPSHOT_ID", "OLD_SNAPSHOT_ID", "NEW_SNAPSHOT_ID" -> ids.get("sourceSnapshot");
      case "BINDING_ID" -> ids.get("binding");
      case "AXIOM_ID" -> ids.get("axiom");
      case "IMPORT_ARTIFACT_ID" -> ids.get("importArtifact");
      default -> null;
    };
  }

  private static boolean collectCommandRecords(Connection c, Map<String, Table> tables, Map<String, Set<String>> ids, Config config) throws SQLException {
    if (!tables.containsKey("MATE_SEMANTIC_COMMAND_RECORD")) return false;
    boolean changed = false;
    String sql = "SELECT id,result_json FROM " + tables.get("MATE_SEMANTIC_COMMAND_RECORD").sqlName() + " WHERE workspace_id=?";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setLong(1, config.workspaceId());
      try (ResultSet r = p.executeQuery()) {
        while (r.next()) {
          String json = r.getString(2);
          if (json == null || !jsonReferencesSelected(json, ids)) continue;
          List<Object> key = List.of(r.getObject(1));
          if (pendingRowsFor("MATE_SEMANTIC_COMMAND_RECORD", key) == null) { registerPending("MATE_SEMANTIC_COMMAND_RECORD", new RowKey("MATE_SEMANTIC_COMMAND_RECORD", key)); changed = true; }
        }
      }
    }
    return changed;
  }

  private static boolean jsonReferencesSelected(String json, Map<String, Set<String>> ids) {
    Map<String, String> keys = Map.of("graphId", "graph", "ontologyId", "ontology", "revisionId", "revision", "sourceSnapshotId", "sourceSnapshot");
    for (Map.Entry<String, String> entry : keys.entrySet()) {
      Matcher matcher = Pattern.compile("\\\"" + entry.getKey() + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
      while (matcher.find()) if (ids.get(entry.getValue()).contains(matcher.group(1))) return true;
    }
    return false;
  }

  /* Generic reference discovery is materialized from this process-local set. */
  private static final Map<String, Set<String>> GENERIC_KEYS = new HashMap<>();
  private static void registerPending(String table, RowKey key) { GENERIC_KEYS.computeIfAbsent(table, ignored -> new LinkedHashSet<>()).add(key.values().toString()); }
  private static List<RowKey> pendingRowsFor(String table, List<Object> values) {
    Set<String> valuesSet = GENERIC_KEYS.get(table); return valuesSet != null && valuesSet.contains(values.toString()) ? List.of(new RowKey(table, values)) : null;
  }

  private static Map<String, List<RowKey>> materializeRows(Connection c, Map<String, Table> tables, Map<String, Set<String>> ids, Config config) throws SQLException {
    Map<String, List<RowKey>> result = new TreeMap<>();
    for (Table table : tables.values()) {
      if (table.primaryKeys().isEmpty()) continue;
      List<RowKey> selected = new ArrayList<>();
      if (GENERIC_KEYS.containsKey(table.name())) {
        String sql = "SELECT " + String.join(",", table.primaryKeys()) + " FROM " + table.sqlName();
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) { while (r.next()) { List<Object> key = new ArrayList<>(); for (int i=1;i<=table.primaryKeys().size();i++) key.add(r.getObject(i)); if (GENERIC_KEYS.get(table.name()).contains(key.toString())) selected.add(new RowKey(table.name(), key)); } }
      }
      selected.addAll(explicitRows(c, table, ids, config));
      selected = selected.stream().distinct().toList();
      if (!selected.isEmpty()) result.put(table.name(), selected);
    }
    return result;
  }

  private static List<RowKey> explicitRows(Connection c, Table table, Map<String, Set<String>> ids, Config config) throws SQLException {
    Set<String> selectedIds = new HashSet<>();
    for (String col : table.columns()) {
      if (col.equals("ID")) selectedIds.addAll(idsForTable(table.name(), ids));
    }
    List<RowKey> rows = new ArrayList<>();
    if (selectedIds.isEmpty()) return rows;
    String sql = "SELECT " + String.join(",", table.primaryKeys()) + " FROM " + table.sqlName() + " WHERE ID IN (" + qmarks(selectedIds.size()) + ")";
    if (!table.columns().contains("ID")) return rows;
    try (PreparedStatement p = c.prepareStatement(sql)) { int i=1; for (String id : selectedIds) p.setString(i++, id); try(ResultSet r=p.executeQuery()){while(r.next()){List<Object> k=new ArrayList<>();for(int j=1;j<=table.primaryKeys().size();j++)k.add(r.getObject(j));rows.add(new RowKey(table.name(),k));}} }
    return rows;
  }

  private static Set<String> idsForTable(String table, Map<String, Set<String>> ids) {
    if (table.contains("ONTOLOGY_REVISION")) return ids.get("revision");
    if (table.equals("MATE_SEMANTIC_ONTOLOGY")) return ids.get("ontology");
    if (table.equals("MATE_SEMANTIC_GRAPH")) return ids.get("graph");
    if (table.contains("EXTRACTION_TASK")) return ids.get("task");
    if (table.contains("EXTRACTION_SUGGESTION")) return ids.get("suggestion");
    if (table.contains("EXTRACTION_ATTEMPT")) return ids.get("attempt");
    if (table.equals("MATE_SEMANTIC_STATEMENT")) return ids.get("statement");
    if (table.equals("MATE_SEMANTIC_SOURCE_SNAPSHOT")) return ids.get("snapshot");
    if (table.equals("MATE_SEMANTIC_EVIDENCE")) return ids.get("evidence");
    if (table.equals("MATE_SEMANTIC_AXIOM_SOURCE")) return ids.get("binding");
    if (table.equals("MATE_SEMANTIC_ONTOLOGY_SOURCE_SNAPSHOT")) return ids.get("sourceSnapshot");
    if (table.equals("MATE_SEMANTIC_ONTOLOGY_AXIOM")) return ids.get("axiom");
    if (table.equals("MATE_SEMANTIC_IMPORT_ARTIFACT")) return ids.get("importArtifact");
    return Set.of();
  }

  private static Map<String, Table> loadTables(Connection c) throws SQLException {
    Map<String, Table> result = new TreeMap<>(); DatabaseMetaData meta = c.getMetaData();
    try (ResultSet rs = meta.getTables(meta.getConnection().getCatalog(), null, null, new String[]{"TABLE"})) {
      while (rs.next()) {
        String sqlName = rs.getString("TABLE_NAME");
        String name = sqlName.toUpperCase(Locale.ROOT);
        if (name.startsWith("MATE_SEMANTIC_")) result.put(name, loadTable(meta, name, sqlName));
      }
    }
    return result;
  }

  private static Table loadTable(DatabaseMetaData meta, String name) throws SQLException {
    return loadTable(meta, name, name.toLowerCase(Locale.ROOT));
  }

  private static Table loadTable(DatabaseMetaData meta, String name, String sqlName) throws SQLException {
    List<String> cols = new ArrayList<>();
    try (ResultSet rs=meta.getColumns(meta.getConnection().getCatalog(),null,sqlName,null)) {
      while(rs.next()) cols.add(rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
    }
    Map<Short,String> pkOrder = new TreeMap<>();
    try(ResultSet rs=meta.getPrimaryKeys(meta.getConnection().getCatalog(),null,sqlName)) {
      while(rs.next()) pkOrder.put(rs.getShort("KEY_SEQ"),rs.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
    }
    Map<String,List<String>> fks = new HashMap<>();
    try(ResultSet rs=meta.getImportedKeys(meta.getConnection().getCatalog(),null,sqlName)) {
      while(rs.next()) fks.computeIfAbsent(rs.getString("PKTABLE_NAME").toUpperCase(Locale.ROOT),ignored->new ArrayList<>()).add(rs.getString("FKCOLUMN_NAME").toUpperCase(Locale.ROOT));
    }
    return new Table(name, sqlName, cols, new ArrayList<>(pkOrder.values()), fks);
  }

  private static void requireTables(Map<String, Table> tables, Set<String> names) { for (String name:names) if(!tables.containsKey(name)) throw new ResetRefused("schema is missing " + name); }

  private static Map<String,String> retainedDigests(Connection c, Config config, Set<String> deletedTables) throws SQLException {
    Map<String,String> retained = new TreeMap<>();
    for (String table : List.of("MATE_WIKI_RAW_MATERIAL", "MATE_WIKI_PAGE", "MATE_WIKI_KNOWLEDGE_BASE")) {
      if (!tableExists(c, table)) continue;
      Table t=loadTable(c.getMetaData(), table); String scope = t.columns().contains("KB_ID") ? "KB_ID IN ("+qmarks(config.kbIds().size())+")" : (t.columns().contains("WORKSPACE_ID") ? "WORKSPACE_ID=?" : "1=1");
      String sql="SELECT * FROM "+t.sqlName()+" WHERE "+scope; try(PreparedStatement p=c.prepareStatement(sql)){int i=1;if(t.columns().contains("KB_ID"))for(long kb:config.kbIds())p.setLong(i++,kb);else if(t.columns().contains("WORKSPACE_ID"))p.setLong(i++,config.workspaceId());try(ResultSet r=p.executeQuery()){MessageDigest md=sha();int count=0;while(r.next()){count++;md.update(rowText(r,t.columns()).getBytes(StandardCharsets.UTF_8));}retained.put(table,count+":"+hex(md.digest()));}} }
    return retained;
  }

  private static String backup(Connection c, Plan plan, Path path) throws SQLException, IOException {
    try(BufferedWriter out=Files.newBufferedWriter(path,StandardCharsets.UTF_8)){for(String table:orderedTables(plan)){Table t=plan.tables().get(table);for(RowKey key:plan.rows().getOrDefault(table,List.of())){out.write(rowJson(c,t,key));out.newLine();}}}
    return sha256(Files.readAllBytes(path));
  }

  private static void execute(Connection c, Plan initial, Config config, Path manifest, Path backup, String backupDigest) throws Exception {
    Plan current=plan(c,config); if(!current.fingerprint().equals(initial.fingerprint()))throw new ResetRefused("allowlist closure changed after dry-run; rerun dry-run");
    c.setReadOnly(false); c.setAutoCommit(false);
    try { if(config.sleepBeforeDeleteMs()>0)Thread.sleep(config.sleepBeforeDeleteMs()); for(String table:orderedTables(current)){Table t=current.tables().get(table);int expected=current.rows().getOrDefault(table,List.of()).size();int deleted=0;for(RowKey key:current.rows().getOrDefault(table,List.of()))deleted+=delete(c,t,key);if(deleted!=expected)throw new ResetRefused("row count mismatch deleting "+table+": expected "+expected+" got "+deleted);} if(config.rebuild()) for(Fixture fixture:fixtures(config)) insertFixture(c,fixture,config); verify(c,current,config); c.commit(); writeManifest(manifest,current,backup,backupDigest,"COMMITTED",List.of("delete-allowlist","rebuild-fixtures","verify")); }
    catch(Exception e){try{c.rollback();}catch(SQLException ignored){};throw e;}
  }

  private static int delete(Connection c, Table t, RowKey key) throws SQLException {String sql="DELETE FROM "+t.sqlName()+" WHERE "+String.join("=? AND ",t.primaryKeys())+"=?";try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<key.values().size();i++)p.setObject(i+1,key.values().get(i));return p.executeUpdate();}}

  private static void verify(Connection c, Plan old, Config config) throws Exception {
    for (Map.Entry<String,List<RowKey>> entry : old.rows().entrySet()) {
      Table table = old.tables().get(entry.getKey());
      for (RowKey key : entry.getValue()) {
        String sql = "SELECT 1 FROM " + table.sqlName() + " WHERE " + String.join("=? AND ", table.primaryKeys()) + "=?";
        try (PreparedStatement p=c.prepareStatement(sql)) { for (int i=0;i<key.values().size();i++) p.setObject(i+1,key.values().get(i)); try (ResultSet r=p.executeQuery()) { if (r.next()) throw new ResetRefused("selected row remains before commit: " + table.name() + " " + key.values()); } }
      }
    }
    if(config.rebuild()){for(Fixture f:fixtures(config)){try(PreparedStatement p=c.prepareStatement("SELECT document_digest FROM mate_semantic_ontology_revision WHERE id=?")){p.setString(1,f.revisionId());try(ResultSet r=p.executeQuery()){if(!r.next()||!f.digest().equals(r.getString(1)))throw new ResetRefused("fixture verification failed for "+f.name());}}}}}

  private static List<Fixture> fixtures(Config c) throws IOException {if(!Files.isDirectory(c.fixturesDir()))throw new ResetRefused("fixtures directory is missing: "+c.fixturesDir());List<Fixture> result=new ArrayList<>();List<String> names=List.of("quality","inventory");for(int i=0;i<names.size();i++){String name=names.get(i);Path file=c.fixturesDir().resolve(name+".ofn");if(!Files.isRegularFile(file))throw new ResetRefused("required fixture is missing: "+file);String text=Files.readString(file);String digest=sha256(text.getBytes(StandardCharsets.UTF_8));String suffix=shortHash(c.runId()+":"+name);long kb=c.kbIds().get(Math.min(i,c.kbIds().size()-1));result.add(new Fixture(name,"reset-"+suffix+"-ont","reset-"+suffix+"-rev","reset-"+suffix+"-graph",kb,text,digest,ontologyIri(text)));}return result;}

  private static void insertFixture(Connection c,Fixture f,Config config)throws SQLException{if(exists(c,"SELECT 1 FROM mate_semantic_ontology WHERE id=?",f.ontologyId()))return;Timestamp now=Timestamp.from(Instant.now());try(PreparedStatement p=c.prepareStatement("INSERT INTO mate_semantic_ontology(id,workspace_id,name,description,latest_version,latest_revision_id,draft_id,draft_counter,updated_at) VALUES(?,?,?,?,?,?,?,?,?)")){p.setString(1,f.ontologyId());p.setLong(2,config.workspaceId());p.setString(3,f.name());p.setString(4,"OWL RESET fixture "+f.name());p.setInt(5,1);p.setString(6,f.revisionId());p.setNull(7,Types.VARCHAR);p.setLong(8,1);p.setTimestamp(9,now);p.executeUpdate();}try(PreparedStatement p=c.prepareStatement("INSERT INTO mate_semantic_ontology_revision(id,ontology_id,version,draft_version,revision_state,draft_slot,name,description,base_revision_id,available_for_new_bindings,published_at,published_by,publication_note,document_text,document_syntax,document_digest,ontology_iri,version_iri,import_lock_digest,model_schema,imports_json,policy_json) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){int i=1;p.setString(i++,f.revisionId());p.setString(i++,f.ontologyId());p.setInt(i++,1);p.setLong(i++,1);p.setString(i++,"PUBLISHED");p.setNull(i++,Types.INTEGER);p.setString(i++,f.name());p.setString(i++,"OWL RESET fixture "+f.name());p.setNull(i++,Types.VARCHAR);p.setBoolean(i++,true);p.setTimestamp(i++,now);p.setString(i++,"reset");p.setString(i++,"RESET rebuild "+config.runId());p.setString(i++,f.text());p.setString(i++,"FUNCTIONAL");p.setString(i++,f.digest());p.setString(i++,f.ontologyIri());p.setNull(i++,Types.VARCHAR);p.setString(i++,sha256(new byte[0]));p.setString(i++,"owl-document-v1");p.setString(i++,"[]");p.setString(i++,"{\"version\":\"reset-v1\",\"rules\":[]}");p.executeUpdate();}try(PreparedStatement p=c.prepareStatement("INSERT INTO mate_semantic_graph(id,workspace_id,kb_id,ontology_revision_id,enabled,mutation_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)")){p.setString(1,f.graphId());p.setLong(2,config.workspaceId());p.setLong(3,f.kbId());p.setString(4,f.revisionId());p.setBoolean(5,true);p.setLong(6,0);p.setTimestamp(7,now);p.setTimestamp(8,now);p.executeUpdate();}insertAxioms(c,f,config,now);}

  private static void insertAxioms(Connection c,Fixture f,Config config,Timestamp now)throws SQLException{for(String axiom:topLevelAxioms(f.text())){String id="a-"+shortHash(axiom);try(PreparedStatement p=c.prepareStatement("INSERT INTO mate_semantic_ontology_axiom(revision_id,axiom_id,axiom_kind,axiom_text,signature_json) VALUES(?,?,?,?,?)")){p.setString(1,f.revisionId());p.setString(2,id);p.setString(3,axiom.substring(0,Math.max(0,axiom.indexOf('('))));p.setString(4,axiom);p.setString(5,"[]");p.executeUpdate();}}}

  private static List<String> topLevelAxioms(String text){List<String>out=new ArrayList<>();int ontology=text.indexOf("Ontology("),depth=0,start=-1;for(int i=Math.max(0,ontology+"Ontology(".length());i<text.length();i++){char ch=text.charAt(i);if(ch=='('){if(depth==0){int j=i-1;while(j>=0&&Character.isWhitespace(text.charAt(j)))j--;int k=j;while(k>=0&&Character.isJavaIdentifierPart(text.charAt(k)))k--;start=k+1;}depth++;}else if(ch==')'){depth--;if(depth==0&&start>=0){out.add(text.substring(start,i+1).trim());start=-1;}}}return out;}
  private static String ontologyIri(String text){Matcher m=ONTOLOGY_IRI.matcher(text);return m.find()?m.group(1):null;}

  private static Map<String, String> graphGuards(Connection c, Map<String, Table> tables, Map<String, List<RowKey>> rows) throws SQLException {
    Table graph = tables.get("MATE_SEMANTIC_GRAPH");
    if (graph == null || !graph.columns().contains("MUTATION_VERSION")) return Map.of();
    Map<String, String> result = new TreeMap<>();
    for (RowKey key : rows.getOrDefault("MATE_SEMANTIC_GRAPH", List.of())) {
      String sql = "SELECT MUTATION_VERSION FROM " + graph.sqlName() + " WHERE ID=?";
      try (PreparedStatement p = c.prepareStatement(sql)) {
        p.setObject(1, key.values().get(0));
        try (ResultSet r = p.executeQuery()) {
          if (r.next()) result.put(String.valueOf(key.values().get(0)), String.valueOf(r.getObject(1)));
        }
      }
    }
    return result;
  }

  private static List<String> actualIds(Plan plan, String table) {
    return plan.rows().getOrDefault(table, List.of()).stream()
        .filter(key -> !key.values().isEmpty())
        .map(key -> String.valueOf(key.values().get(0)))
        .sorted().toList();
  }

  private static void writeManifest(Path path,Plan plan,Path backup,String backupDigest,String status,List<String>actions)throws IOException{StringBuilder b=new StringBuilder();b.append("{\n  \"runId\":").append(q(plan.config().runId())).append(",\n  \"baselineCommit\":").append(q(System.getenv().getOrDefault("SEMANTIC_RESET_BASELINE_COMMIT","unknown"))).append(",\n  \"datasourceFingerprint\":").append(q(fingerprintJdbc(plan.config().jdbcUrl()))).append(",\n  \"workspaceId\":").append(plan.config().workspaceId()).append(",\n  \"ontologyIds\":").append(array(plan.config().ontologyIds())).append(",\n  \"revisionIds\":").append(array(actualIds(plan,"MATE_SEMANTIC_ONTOLOGY_REVISION"))).append(",\n  \"graphIds\":").append(array(actualIds(plan,"MATE_SEMANTIC_GRAPH"))).append(",\n  \"graphMutationVersions\":").append(mapString(plan.graphGuards())).append(",\n  \"schemaFingerprint\":").append(q(plan.schemaFingerprint())).append(",\n  \"expectedCountsByTable\":").append(mapInt(plan.counts())).append(",\n  \"retainedCountsAndDigests\":").append(mapString(plan.retainedDigests())).append(",\n  \"backupPath\":").append(q(backup.toAbsolutePath().toString())).append(",\n  \"backupDigest\":").append(q(backupDigest)).append(",\n  \"writerFence\":").append(q(plan.config().execute()?FENCE:"DRY_RUN_ONLY")).append(",\n  \"status\":").append(q(status)).append(",\n  \"plannedActions\":").append(array(actions)).append(",\n  \"planFingerprint\":").append(q(plan.fingerprint())).append(",\n  \"generatedAt\":").append(q(Instant.now().toString())).append("\n}\n");Files.writeString(path,b.toString(),StandardCharsets.UTF_8);}

  private record BackupRow(String table, Map<String, Object> values) {}

  private static void verifyBackup(Connection c, Path manifest, Path backup) throws IOException, SQLException {
    if (!Files.isRegularFile(backup)) throw new ResetRefused("backup file does not exist: " + backup);
    String expected = manifestBackupDigest(manifest);
    if (expected == null || !expected.equals(sha256(Files.readAllBytes(backup))))
      throw new ResetRefused("backup digest does not match the supplied manifest");
    Map<String, Table> tables = loadTables(c);
    for (String table : tables.keySet()) if (!SUPPORTED_TABLES.contains(table))
      throw new ResetRefused("unknown semantic table requires a schema-aware RESET update: " + table);
    String expectedSchema = manifestSchemaFingerprint(manifest);
    if (expectedSchema == null || !expectedSchema.equals(schemaFingerprint(tables)))
      throw new ResetRefused("backup restore refused because the current schema fingerprint differs from the manifest");
  }

  private static void restore(Connection c, Config config, Path backup) throws Exception {
    Map<String, Table> tables = loadTables(c);
    for (String table : tables.keySet()) {
      if (!SUPPORTED_TABLES.contains(table))
        throw new ResetRefused("unknown semantic table requires a schema-aware RESET update: " + table);
    }
    List<BackupRow> rows = new ArrayList<>();
    for (String line : Files.readAllLines(backup, StandardCharsets.UTF_8)) {
      if (line.isBlank()) continue;
      Object parsed = new JsonParser(line).parse();
      if (!(parsed instanceof Map<?, ?> root) || !(root.get("table") instanceof String tableName)
          || !(root.get("row") instanceof Map<?, ?> rowMap))
        throw new ResetRefused("backup row has an invalid shape");
      Map<String, Object> values = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : rowMap.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw new ResetRefused("backup row has a non-string column");
        values.put(key.toUpperCase(Locale.ROOT), entry.getValue());
      }
      rows.add(new BackupRow(tableName.toUpperCase(Locale.ROOT), values));
    }
    rows.sort(Comparator.comparingInt(row -> deletionRank(row.table())));
    c.setReadOnly(false);
    c.setAutoCommit(false);
    try {
      int restored = 0;
      for (BackupRow row : rows) {
        Table table = tables.get(row.table());
        if (table == null) throw new ResetRefused("backup references a table absent from the current schema: " + row.table());
        if (row.values().isEmpty()) throw new ResetRefused("backup row has no columns: " + row.table());
        if (!table.columns().containsAll(row.values().keySet()))
          throw new ResetRefused("backup row contains columns absent from current schema: " + row.table());
        String columns = String.join(",", row.values().keySet());
        String sql = "INSERT INTO " + table.sqlName() + " (" + columns + ") VALUES (" + qmarks(row.values().size()) + ")";
        try (PreparedStatement p = c.prepareStatement(sql)) {
          int index = 1;
          for (Object value : row.values().values()) p.setObject(index++, value);
          if (p.executeUpdate() != 1) throw new ResetRefused("backup restore affected an unexpected row count: " + row.table());
          restored++;
        }
      }
      c.commit();
      System.out.println("restoredRows=" + restored);
    } catch (Exception e) {
      try { c.rollback(); } catch (SQLException ignored) {}
      throw e;
    }
  }

  private static String manifestBackupDigest(Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new ResetRefused("manifest does not exist: " + path);
    Matcher m = Pattern.compile("\\\"backupDigest\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(Files.readString(path));
    return m.find() ? m.group(1) : null;
  }

  private static String manifestSchemaFingerprint(Path path) throws IOException {
    if (!Files.isRegularFile(path)) throw new ResetRefused("manifest does not exist: " + path);
    Matcher m = Pattern.compile("\\\"schemaFingerprint\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(Files.readString(path));
    return m.find() ? m.group(1) : null;
  }

  private static final class JsonParser {
    private final String text;
    private int index;
    JsonParser(String text) { this.text = text; }
    Object parse() {
      Object value = value();
      whitespace();
      if (index != text.length()) throw new ResetRefused("backup contains trailing JSON data");
      return value;
    }
    private Object value() {
      whitespace();
      if (index >= text.length()) throw new ResetRefused("backup contains incomplete JSON");
      return switch (text.charAt(index)) {
        case '{' -> object();
        case '[' -> array();
        case '"' -> string();
        case 't' -> literal("true", Boolean.TRUE);
        case 'f' -> literal("false", Boolean.FALSE);
        case 'n' -> literal("null", null);
        default -> number();
      };
    }
    private Map<String, Object> object() {
      expect('{'); Map<String, Object> result = new LinkedHashMap<>(); whitespace();
      if (accept('}')) return result;
      while (true) {
        whitespace(); String key = string(); whitespace(); expect(':'); result.put(key, value()); whitespace();
        if (accept('}')) return result; expect(',');
      }
    }
    private List<Object> array() {
      expect('['); List<Object> result = new ArrayList<>(); whitespace();
      if (accept(']')) return result;
      while (true) { result.add(value()); whitespace(); if (accept(']')) return result; expect(','); }
    }
    private String string() {
      expect('"'); StringBuilder result = new StringBuilder();
      while (index < text.length()) {
        char ch = text.charAt(index++);
        if (ch == '"') return result.toString();
        if (ch != '\\') { result.append(ch); continue; }
        if (index >= text.length()) throw new ResetRefused("backup contains an incomplete string");
        char escaped = text.charAt(index++);
        switch (escaped) {
          case '"' -> result.append('"'); case '\\' -> result.append('\\'); case '/' -> result.append('/');
          case 'b' -> result.append('\b'); case 'f' -> result.append('\f'); case 'n' -> result.append('\n');
          case 'r' -> result.append('\r'); case 't' -> result.append('\t');
          case 'u' -> { if (index + 4 > text.length()) throw new ResetRefused("backup contains an incomplete unicode escape"); result.append((char) Integer.parseInt(text.substring(index, index + 4), 16)); index += 4; }
          default -> throw new ResetRefused("backup contains an invalid string escape");
        }
      }
      throw new ResetRefused("backup contains an unterminated string");
    }
    private Object number() {
      int start = index; if (index < text.length() && text.charAt(index) == '-') index++;
      while (index < text.length() && Character.isDigit(text.charAt(index))) index++;
      if (index < text.length() && text.charAt(index) == '.') { index++; while (index < text.length() && Character.isDigit(text.charAt(index))) index++; }
      if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) { index++; if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) index++; while (index < text.length() && Character.isDigit(text.charAt(index))) index++; }
      String value = text.substring(start, index); try { return value.contains(".") || value.contains("e") || value.contains("E") ? new java.math.BigDecimal(value) : Long.valueOf(value); } catch (NumberFormatException e) { throw new ResetRefused("backup contains an invalid number"); }
    }
    private Object literal(String expected, Object value) { if (!text.startsWith(expected, index)) throw new ResetRefused("backup contains an invalid literal"); index += expected.length(); return value; }
    private void whitespace() { while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++; }
    private void expect(char expected) { whitespace(); if (index >= text.length() || text.charAt(index++) != expected) throw new ResetRefused("backup contains invalid JSON"); }
    private boolean accept(char expected) { whitespace(); if (index < text.length() && text.charAt(index) == expected) { index++; return true; } return false; }
  }

  private static String rowJson(Connection c,Table t,RowKey key)throws SQLException{String sql="SELECT * FROM "+t.sqlName()+" WHERE "+String.join("=? AND ",t.primaryKeys())+"=?";try(PreparedStatement p=c.prepareStatement(sql)){for(int i=0;i<key.values().size();i++)p.setObject(i+1,key.values().get(i));try(ResultSet r=p.executeQuery()){if(!r.next())throw new ResetRefused("selected row disappeared while backing up "+t.name());StringBuilder b=new StringBuilder("{\"table\":").append(q(t.name())).append(",\"key\":").append(array(key.values().stream().map(String::valueOf).toList())).append(",\"row\":{");for(int i=0;i<t.columns().size();i++){if(i>0)b.append(',');b.append(q(t.columns().get(i))).append(':').append(valueJson(r.getObject(i+1)));}return b.append("}}").toString();}}}
  private static String rowText(ResultSet r,List<String> cols)throws SQLException{StringBuilder b=new StringBuilder();for(int i=0;i<cols.size();i++)b.append(cols.get(i)).append('=').append(String.valueOf(r.getObject(i+1))).append('\n');return b.toString();}
  private static String valueJson(Object o){if(o==null)return"null";if(o instanceof Number||o instanceof Boolean)return String.valueOf(o);return q(String.valueOf(o));}
  private static String q(String s){return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"\"";}
  private static String array(Collection<?> v){return "["+v.stream().map(x->q(String.valueOf(x))).reduce((a,b)->a+","+b).orElse("")+"]";}
  private static String mapInt(Map<String,Integer> v){StringBuilder b=new StringBuilder("{");int i=0;for(var e:v.entrySet()){if(i++>0)b.append(',');b.append(q(e.getKey())).append(':').append(e.getValue());}return b.append('}').toString();}
  private static String mapString(Map<String,String> v){StringBuilder b=new StringBuilder("{");int i=0;for(var e:v.entrySet()){if(i++>0)b.append(',');b.append(q(e.getKey())).append(':').append(q(e.getValue()));}return b.append('}').toString();}
  private static String digestKeys(List<RowKey> rows){return sha256(rows.stream().map(r->r.table()+r.values()).sorted().reduce("",String::concat).getBytes(StandardCharsets.UTF_8));}
  private static String schemaFingerprint(Map<String, Table> tables) {
    StringBuilder value = new StringBuilder();
    for (Table table : tables.values()) {
      value.append(table.name()).append('|').append(table.columns()).append('|').append(table.primaryKeys()).append('|');
      table.foreignKeys().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> value.append(entry.getKey()).append('=').append(entry.getValue()).append(';'));
      value.append('\n');
    }
    return sha256(value.toString().getBytes(StandardCharsets.UTF_8));
  }
  private static String fingerprint(Map<String,Integer> counts,Map<String,String> digests,Map<String,String> graphGuards,String schemaFingerprint){return sha256((counts+"|"+digests+"|"+graphGuards+"|"+schemaFingerprint).getBytes(StandardCharsets.UTF_8));}
  private static String fingerprintTarget(Config c){return c.workspaceId()+"/"+c.ontologyIds()+"/"+c.revisionIds()+"/"+c.graphIds()+"/"+c.kbIds();}
  private static String fingerprintJdbc(String jdbc){return sha256(jdbc.replaceAll("(?i)(password|pwd)=[^;&]*","$1=<redacted>").getBytes(StandardCharsets.UTF_8));}
  private static String sha256(byte[] bytes){try{MessageDigest m=sha();return hex(m.digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
  private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
  private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x));return s.toString();}
  private static String shortHash(String s){return sha256(s.getBytes(StandardCharsets.UTF_8)).substring(0,20);}
  private static boolean exists(PreparedStatement p)throws SQLException{try(ResultSet r=p.executeQuery()){return r.next();}}
  private static boolean exists(Connection c,String sql,Object value)throws SQLException{try(PreparedStatement p=c.prepareStatement(sql)){p.setObject(1,value);return exists(p);}}
  private static boolean tableExists(Connection c,String table)throws SQLException{try(ResultSet rs=c.getMetaData().getTables(c.getCatalog(),null,null,new String[]{"TABLE"})){while(rs.next())if(rs.getString("TABLE_NAME").equalsIgnoreCase(table))return true;}return false;}
  private static String qmarks(int n){return String.join(",",Collections.nCopies(n,"?"));}
  private static List<String> split(String v){if(v==null||v.isBlank())return List.of();return Arrays.stream(v.split(",")).map(String::trim).filter(s->!s.isBlank()).distinct().toList();}
  private static long parseLong(String v,String key){try{return Long.parseLong(v);}catch(Exception e){throw new ResetRefused("invalid --"+key);}}
  private static String first(Map<String,List<String>> m,String key,String fallback){List<String> v=m.get(key);return v==null||v.isEmpty()?fallback:v.get(v.size()-1);}
  private static String manifestFingerprint(Path path) throws IOException {if(!Files.isRegularFile(path))throw new ResetRefused("manifest does not exist: "+path);Matcher m=Pattern.compile("\\\"planFingerprint\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(Files.readString(path));return m.find()?m.group(1):null;}
  private static String manifestStatus(Path path) throws IOException {if(!Files.isRegularFile(path))throw new ResetRefused("manifest does not exist: "+path);Matcher m=Pattern.compile("\\\"status\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(Files.readString(path));return m.find()?m.group(1):null;}
  private static boolean targetOntologyPresent(Connection c, Config config) throws SQLException {try(PreparedStatement p=c.prepareStatement("SELECT COUNT(*) FROM mate_semantic_ontology WHERE workspace_id=? AND id IN ("+qmarks(config.ontologyIds().size())+")")){int i=1;p.setLong(i++,config.workspaceId());for(String id:config.ontologyIds())p.setString(i++,id);try(ResultSet r=p.executeQuery()){r.next();return r.getInt(1)>0;}}}
  private static void verifyFixturesOnly(Connection c, Config config) throws Exception {if(!config.rebuild())return;for(Fixture f:fixtures(config)){try(PreparedStatement p=c.prepareStatement("SELECT document_digest FROM mate_semantic_ontology_revision WHERE id=?")){p.setString(1,f.revisionId());try(ResultSet r=p.executeQuery()){if(!r.next()||!f.digest().equals(r.getString(1)))throw new ResetRefused("committed manifest fixture verification failed for "+f.name());}}}}
  private static List<String> orderedTables(Plan p){List<String> result=new ArrayList<>(p.rows().keySet());result.sort((a,b)->Integer.compare(deletionRank(b),deletionRank(a)));return result;}
  private static int deletionRank(String table){
    if(table.equals("MATE_SEMANTIC_GRAPH_MIGRATION_PLAN"))return 140;
    if(table.equals("MATE_SEMANTIC_SOURCE_CHANGE_ITEM"))return 135;
    if(table.equals("MATE_SEMANTIC_SOURCE_CHANGE"))return 130;
    if(table.equals("MATE_SEMANTIC_SOURCE_CHANGE_LOCK"))return 125;
    if(table.equals("MATE_SEMANTIC_SOURCE_CHANGE_RUN"))return 115;
    if(table.contains("SOURCE_REVIEW"))return 120;
    if(table.equals("MATE_SEMANTIC_AXIOM_SOURCE"))return 110;
    if(table.equals("MATE_SEMANTIC_REVISION_EVIDENCE"))return 105;
    if(table.contains("ONTOLOGY_SOURCE_SNAPSHOT"))return 100;
    if(table.contains("ONTOLOGY_AXIOM"))return 95;
    if(table.contains("EXTRACTION_ACTION"))return 105;
    if(table.contains("EXTRACTION_RECEIPT")||table.contains("SUBMISSION_INTENT"))return 100;
    if(table.contains("EXTRACTION_EDIT"))return 90;
    if(table.contains("EXTRACTION_SUGGESTION"))return 80;
    if(table.contains("EXTRACTION_ATTEMPT"))return 70;
    if(table.contains("EXTRACTION_TASK"))return 60;
    if(table.contains("CHANGE_PROPOSAL")||table.contains("CONFLICT"))return 80;
    if(table.contains("STATEMENT_REVISION"))return 75;
    if(table.equals("MATE_SEMANTIC_STATEMENT"))return 70;
    if(table.contains("EVIDENCE")||table.equals("MATE_SEMANTIC_SOURCE_SNAPSHOT")||table.contains("IMPORT_JOB"))return 65;
    if(table.contains("GOVERNANCE_EVENT")||table.contains("SNAPSHOT_EXCLUSION")||table.contains("SOURCE_GOVERNANCE")||table.contains("MUTATION_COMMAND")||table.contains("COMMAND_RECORD"))return 60;
    if(table.equals("MATE_SEMANTIC_ENTITY"))return 55;
    if(table.equals("MATE_SEMANTIC_GRAPH"))return 50;
    if(table.equals("MATE_SEMANTIC_ONTOLOGY_REVISION"))return 40;
    if(table.equals("MATE_SEMANTIC_ONTOLOGY"))return 30;
    if(table.contains("EXTRACTION_GATE"))return 0;
    return 20;
  }
  static final class ResetRefused extends RuntimeException {
    private static final long serialVersionUID = 1L;
    ResetRefused(String message){super(message);}
  }
}
