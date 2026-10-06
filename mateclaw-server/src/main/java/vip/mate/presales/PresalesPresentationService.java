package vip.mate.presales;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import vip.mate.agent.binding.service.AgentBindingService;
import vip.mate.presales.repository.PresalesArtifactRepository;
import vip.mate.skill.runtime.SkillRuntimeService;

/** Constrained SVG compiler adapter. No model-authored code or shell commands are executed. */
@Service
public class PresalesPresentationService implements PresalesPresentationHook {
    private final PresalesArtifactRepository artifacts;
    private final ObjectMapper json;
    private final SkillRuntimeService skills;
    private final AgentBindingService bindings;
    private final TransactionTemplate transaction;

    @Value("${mateclaw.presales.ppt-python:python3}")
    private String python = "python3";

    @Value("${mateclaw.presales.ppt-skill-root:}")
    private String trustedRoot = "";

    private static final int MAX_FILE = 20_000_000;
    private static final Set<String> TAGS =
            Set.of(
                    "svg",
                    "g",
                    "rect",
                    "circle",
                    "ellipse",
                    "line",
                    "polyline",
                    "polygon",
                    "path",
                    "text",
                    "tspan",
                    "title",
                    "desc");
    private static final Set<String> ATTRS =
            Set.of(
                    "xmlns",
                    "viewBox",
                    "width",
                    "height",
                    "x",
                    "y",
                    "x1",
                    "y1",
                    "x2",
                    "y2",
                    "cx",
                    "cy",
                    "r",
                    "rx",
                    "ry",
                    "d",
                    "points",
                    "fill",
                    "fill-opacity",
                    "stroke",
                    "stroke-width",
                    "stroke-opacity",
                    "stroke-linecap",
                    "stroke-linejoin",
                    "opacity",
                    "transform",
                    "font-family",
                    "font-size",
                    "font-weight",
                    "text-anchor",
                    "dominant-baseline",
                    "letter-spacing",
                    "dx",
                    "dy",
                    "id",
                    "data-pptx-page-role",
                    "data-pptx-role");

    public PresalesPresentationService(
            PresalesArtifactRepository artifacts,
            ObjectMapper json,
            SkillRuntimeService skills,
            AgentBindingService bindings,
            PlatformTransactionManager tx) {
        this.artifacts = artifacts;
        this.json = json;
        this.skills = skills;
        this.bindings = bindings;
        this.transaction = new TransactionTemplate(tx);
    }

    private vip.mate.skill.runtime.model.ResolvedSkill requireBinding(
            String scope, String agentId) {
        var skill = skills.findActiveSkill("ppt-master-plus", Long.valueOf(scope));
        var bound = bindings.getBoundSkillIds(Long.valueOf(agentId));
        if (skill == null
                || !skill.isEnabled()
                || !skill.isRuntimeAvailable()
                || bound == null
                || !bound.contains(skill.getId()))
            throw PresalesModelAdapter.error(409, "PPT_SKILL_NOT_BOUND");
        try {
            if (skill.getSkillDir() == null || !skill.getSkillDir().toRealPath().equals(root()))
                throw PresalesModelAdapter.error(409, "PPT_SKILL_PATH_MISMATCH");
        } catch (java.io.IOException e) {
            throw PresalesModelAdapter.error(409, "PPT_SKILL_UNAVAILABLE");
        }
        return skill;
    }

    public String instructions(String scope, String agentId) {
        return instructionsFor(requireBinding(scope, agentId).getContent());
    }

    private static String instructionsFor(String skillContent) {
        return "\nPRESENTATION ENGINE: ppt-master-plus. Author self-contained 1280x720 SVG slides "
                + "in solution.presentation.slides [{title,svg}]. Use editable text and vector shapes only. "
                + "No scripts, images, stylesheets, URLs, links, embedded files, metadata, defs or use. Allowed elements: "
                + TAGS
                + ". White background, enterprise blue #0966D9, dark text, generous margins (64px), "
                + "title 32px and body 22px or larger; split long content over pages. Include 未批准草稿 in each page. "
                + "Use only supplied project facts, preserve unknowns. This platform adapter compiles the authored SVG roster "
                + "with the bound skill's quality checker and native converter, then requires human review before publication. "
                + "It does not run arbitrary skill scripts.\nBOUND SKILL INSTRUCTIONS (platform structured-output contract applies):\n"
                + skillContent;
    }

    PresalesPresentationPackage capturePackage(String scope, String agentId) {
        var skill = requireBinding(scope, agentId);
        Path work = null;
        try {
            var files = PresalesPresentationPackage.captureFiles(root());
            String prompt =
                    instructionsFor(
                            new String(
                                    Base64.getDecoder().decode(files.get("SKILL.md")),
                                    StandardCharsets.UTF_8));
            work = Files.createTempDirectory("presales-ppt-capture-").toRealPath();
            Path engine = work.resolve("engine");
            // Materialize before probing imports, so even capture never executes current loose
            // files.
            var candidate =
                    PresalesPresentationPackage.create(
                            String.valueOf(skill.getId()), prompt, files, "capture");
            candidate.materialize(engine);
            String runtime = runtimeIdentity(engine, work);
            candidate.verifyMaterialized(engine);
            var captured =
                    PresalesPresentationPackage.create(
                            candidate.bindingId(), prompt, files, runtime);
            requireActivePackage(scope, agentId, captured);
            return captured;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw PresalesModelAdapter.error(409, "PPT_SKILL_PACKAGE_UNAVAILABLE");
        } finally {
            deleteWork(work);
        }
    }

    void requireActivePackage(String scope, String agentId, PresalesPresentationPackage pkg) {
        if (pkg == null) throw PresalesModelAdapter.error(409, "PPT_SKILL_PACKAGE_UNAVAILABLE");
        var active = requireBinding(scope, agentId);
        if (!String.valueOf(active.getId()).equals(pkg.bindingId()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
    }

    public record ExecutionPin(String instructions, String digest) {}

    /** Binds the S6 prompt and the actual converter/checker tree before model execution. */
    public ExecutionPin executionPin(String scope, String agentId) {
        String prompt = instructions(scope, agentId);
        Path skillRoot = root();
        try {
            String identity =
                    prompt
                            + "\n"
                            + PresalesArtifactRenderer.digest(
                                    Files.readAllBytes(skillRoot.resolve("SKILL.md")))
                            + "\n"
                            + treeDigest(skillRoot.resolve("scripts"));
            return new ExecutionPin(
                    prompt,
                    PresalesArtifactRenderer.digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw PresalesModelAdapter.error(409, "PPT_SKILL_UNAVAILABLE");
        }
    }

    private Path root() {
        if (trustedRoot.isBlank())
            throw PresalesModelAdapter.error(409, "PPT_ENGINE_NOT_CONFIGURED");
        try {
            Path configured = Path.of(trustedRoot).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(configured))
                throw new java.io.IOException("Symlink engine root");
            return configured.toRealPath();
        } catch (Exception e) {
            throw PresalesModelAdapter.error(409, "PPT_ENGINE_NOT_CONFIGURED");
        }
    }

    public ObjectNode prepare(ObjectNode result, String scope, String projectId, String runId) {
        return compile(result, scope, projectId, runId, null, null);
    }

    private ObjectNode compile(
            ObjectNode result,
            String scope,
            String projectId,
            String runId,
            String agentId,
            PresalesPresentationPackage pkg) {
        JsonNode presentation = result.path("solution").path("presentation");
        if (!presentation.path("slides").isArray()
                || presentation.path("slides").isEmpty()
                || presentation.path("slides").size() > 20)
            throw PresalesModelAdapter.error(422, "PPT_SLIDES_REQUIRED");
        Map<String, byte[]> pages = new LinkedHashMap<>();
        int i = 0;
        for (var slide : presentation.path("slides")) {
            String svg = slide.path("svg").asText();
            validateSvg(svg);
            pages.put(
                    String.format(Locale.ROOT, "page-%02d.svg", ++i),
                    svg.getBytes(StandardCharsets.UTF_8));
        }
        Path root = pkg == null ? root() : null, work = null;
        try {
            work = Files.createTempDirectory("presales-ppt-").toRealPath();
            if (pkg != null) {
                root = work.resolve("engine");
                pkg.materialize(root);
                requireRuntime(pkg, root, work);
            }
            Path output = Files.createDirectories(work.resolve("svg_output"));
            for (var page : pages.entrySet())
                Files.write(output.resolve(page.getKey()), page.getValue());
            run(root.resolve("scripts/attribution_guard.py"), List.of(), work, 30);
            // Platform adapter uses the compiler's explicit lockless flat-roster contract; no
            // interactive workflow is claimed.
            run(
                    root.resolve("scripts/svg_quality_checker.py"),
                    List.of(work.toString(), "--quick-generate", "--stage", "final", "--json"),
                    work,
                    60);
            Path ppt = work.resolve("solution.pptx");
            run(
                    root.resolve("scripts/svg_to_pptx.py"),
                    List.of(
                            work.toString(),
                            "--quick-generate",
                            "--no-notes",
                            "-o",
                            ppt.toString()),
                    work,
                    120);
            if (!Files.isRegularFile(ppt) || Files.size(ppt) > MAX_FILE)
                throw PresalesModelAdapter.error(422, "PPT_OUTPUT_INVALID");
            byte[] bytes = Files.readAllBytes(ppt);
            try (var deck =
                    new org.apache.poi.xslf.usermodel.XMLSlideShow(
                            new java.io.ByteArrayInputStream(bytes))) {
                if (deck.getSlides().size() != pages.size())
                    throw PresalesModelAdapter.error(422, "PPT_PAGE_COUNT_MISMATCH");
            }
            ObjectNode manifest =
                    json.createObjectNode()
                            .put("skill", "ppt-master-plus")
                            .put("adapter", "presales-svg-1")
                            .put("artifactId", runId)
                            .put("sha256", PresalesArtifactRenderer.digest(bytes))
                            .put("pageCount", pages.size())
                            .put(
                                    "skillSha256",
                                    PresalesArtifactRenderer.digest(
                                            Files.readAllBytes(root.resolve("SKILL.md"))))
                            .put(
                                    "engineSha256",
                                    PresalesArtifactRenderer.digest(
                                            Files.readAllBytes(
                                                    root.resolve("scripts/svg_to_pptx.py"))))
                            .put("engineTreeSha256", treeDigest(root.resolve("scripts")))
                            .put(
                                    "inputSha256",
                                    PresalesArtifactRenderer.digest(
                                            json.writeValueAsBytes(result.path("solution"))))
                            .put(
                                    "skillVersion",
                                    version(Files.readString(root.resolve("SKILL.md"))));
            if (pkg != null) {
                pkg.verifyMaterialized(root);
                requireRuntime(pkg, root, work);
                requireActivePackage(scope, agentId, pkg);
                manifest.put("packageSha256", pkg.digest())
                        .put("runtimeIdentity", pkg.runtimeIdentity())
                        .put("engineTreeSha256", pkg.digest());
            }
            var list = manifest.putArray("slides");
            i = 0;
            for (var page : pages.entrySet())
                list.addObject()
                        .put("title", presentation.path("slides").get(i++).path("title").asText())
                        .put("filename", page.getKey())
                        .put("sha256", PresalesArtifactRenderer.digest(page.getValue()));
            Path quality = work.resolve("validation/svg_quality_report.json");
            if (Files.isRegularFile(quality)) {
                byte[] report = Files.readAllBytes(quality);
                manifest.put("qualityReportSha256", PresalesArtifactRenderer.digest(report));
                pages.put("quality-report.json", report);
            }
            pages.put("solution.pptx", bytes);
            transaction.executeWithoutResult(
                    status -> {
                        for (var page : pages.entrySet())
                            artifacts.insert(
                                    projectId,
                                    runId,
                                    page.getKey(),
                                    PresalesArtifactRenderer.digest(page.getValue()),
                                    Base64.getEncoder().encodeToString(page.getValue()));
                    });
            ((ObjectNode) result.path("solution")).set("presentation", manifest);
            return result;
        } catch (vip.mate.semantic.web.SemanticApiException e) {
            throw e;
        } catch (Exception e) {
            throw PresalesModelAdapter.error(422, "PPT_GENERATION_FAILED");
        } finally {
            deleteWork(work);
        }
    }

    private static void deleteWork(Path work) {
        if (work != null)
            try (var paths = Files.walk(work)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (Exception ignored) {
                                    }
                                });
            } catch (Exception ignored) {
            }
    }

    @Override
    public ObjectNode prepare(
            ObjectNode result,
            String scope,
            String projectId,
            String runId,
            String expectedDigest,
            String agentId,
            PresalesPresentationPackage pkg) {
        requireActivePackage(scope, agentId, pkg);
        if (expectedDigest == null || !expectedDigest.equals(pkg.digest()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        return compile(result, scope, projectId, runId, agentId, pkg);
    }

    @Override
    public ObjectNode prepare(
            ObjectNode result,
            String scope,
            String projectId,
            String runId,
            String expectedDigest,
            String agentId) {
        if (expectedDigest == null
                || expectedDigest.isBlank()
                || !expectedDigest.equals(executionPin(scope, agentId).digest()))
            throw PresalesModelAdapter.error(409, "EXECUTION_PIN_CHANGED");
        return prepare(result, scope, projectId, runId);
    }

    private static String treeDigest(Path scripts) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        try (var files = Files.walk(scripts)) {
            for (Path p :
                    files.filter(f -> Files.isRegularFile(f) && f.toString().endsWith(".py"))
                            .sorted()
                            .toList()) {
                digest.update(scripts.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(p));
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String version(String skill) {
        var m =
                java.util.regex.Pattern.compile("(?m)^\\s*version:\\s*[\"']?([^\\s\"']+)")
                        .matcher(skill);
        return m.find() ? m.group(1) : "unknown";
    }

    private void run(Path script, List<String> args, Path work, int seconds) throws Exception {
        if (!Files.isRegularFile(script))
            throw PresalesModelAdapter.error(409, "PPT_ENGINE_NOT_CONFIGURED");
        List<String> command = new ArrayList<>(List.of(python, script.toString()));
        command.addAll(args);
        Path log = work.resolve("compiler.log");
        ProcessBuilder builder =
                new ProcessBuilder(command)
                        .directory(work.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile());
        String path = System.getenv("PATH");
        builder.environment().clear();
        if (path != null) builder.environment().put("PATH", path);
        builder.environment().put("HOME", work.toString());
        builder.environment().put("PYTHONIOENCODING", "utf-8");
        builder.environment().put("TMPDIR", work.toString());
        builder.environment().put("PYTHONNOUSERSITE", "1");
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.environment().put("PPT_MASTER_DISABLE_WORKFLOW_TRANSCRIPT", "1");
        Process p = builder.start();
        try {
            if (!p.waitFor(seconds, TimeUnit.SECONDS))
                throw PresalesModelAdapter.error(422, "PPT_GENERATION_TIMEOUT");
            if (p.exitValue() != 0)
                throw PresalesModelAdapter.error(422, "PPT_QUALITY_OR_COMPILER_FAILED");
        } finally {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    private void requireRuntime(PresalesPresentationPackage pkg, Path engine, Path work)
            throws Exception {
        if (!pkg.runtimeIdentity().equals(runtimeIdentity(engine, work)))
            throw PresalesModelAdapter.error(409, "PPT_RUNTIME_CHANGED");
    }

    private String runtimeIdentity(Path engine, Path work) throws Exception {
        Path probe = work.resolve("runtime-probe.py");
        Files.writeString(probe, RUNTIME_PROBE);
        run(probe, List.of(engine.toString()), work, 60);
        String identity = Files.readString(work.resolve("compiler.log")).strip();
        if (!identity.matches("[0-9a-f]{64}"))
            throw PresalesModelAdapter.error(409, "PPT_RUNTIME_UNAVAILABLE");
        return identity;
    }

    private static final String RUNTIME_PROBE =
            """
            import ast, hashlib, importlib.metadata, json, platform, sys
            from pathlib import Path
            root = Path(sys.argv[1]).resolve()
            sys.path.insert(0, str(root / 'scripts'))
            import svg_quality.checker as checker
            import svg_to_pptx.pptx_package.cli
            import pptx, lxml, PIL
            fallbacks = set()
            for node in ast.walk(ast.parse((root / 'scripts/svg_quality/checker.py').read_text())):
                if isinstance(node, ast.ExceptHandler) and node.type is not None and 'ImportError' in ast.unparse(node.type):
                    for stmt in node.body:
                        if isinstance(stmt, ast.Assign) and isinstance(stmt.value, ast.Constant) and stmt.value.value is None:
                            fallbacks.update(t.id for t in stmt.targets if isinstance(t, ast.Name))
            missing = sorted(n for n in fallbacks if not n.endswith('_import_error') and getattr(checker, n, None) is None)
            if missing:
                raise RuntimeError('Incomplete presentation checker imports: ' + ','.join(missing))
            # Identity includes all files of external distributions actually imported by the supported path,
            # not just their declared version; this includes python-pptx's installed default template.
            external = set()
            modules = {}
            for name, module in list(sys.modules.items()):
                filename = getattr(module, '__file__', None)
                if not filename or name == '__main__':
                    continue
                path = Path(filename).resolve()
                if path == Path(__file__).resolve() or path.is_relative_to(root) or not path.is_file():
                    continue
                modules[str(path)] = hashlib.sha256(path.read_bytes()).hexdigest()
                external.add(name.split('.')[0])
            packages = {}
            for top, distributions in importlib.metadata.packages_distributions().items():
                if top not in external:
                    continue
                for name in distributions:
                    dist = importlib.metadata.distribution(name)
                    files = {}
                    for file in dist.files or []:
                        path = Path(dist.locate_file(file)).resolve()
                        if path.is_file() and '__pycache__' not in path.parts and path.suffix != '.pyc':
                            files[str(file)] = hashlib.sha256(path.read_bytes()).hexdigest()
                    packages[name] = {'version': dist.version, 'files': files}
            template = Path(pptx.__file__).parent / 'templates/default.pptx'
            identity = {'python': sys.version, 'platform': platform.platform(),
                        'executable': hashlib.sha256(Path(sys.executable).resolve().read_bytes()).hexdigest(),
                        'template': hashlib.sha256(template.read_bytes()).hexdigest(),
                        'modules': modules, 'packages': packages}
            print(hashlib.sha256(json.dumps(identity, sort_keys=True, separators=(',', ':')).encode()).hexdigest())
            """;

    static void validateSvg(String svg) {
        if (svg.isBlank() || svg.length() > 150_000)
            throw PresalesModelAdapter.error(422, "PPT_SVG_INVALID");
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(svg)));
            Element root = doc.getDocumentElement();
            if (!"svg".equals(root.getLocalName())
                    || !"0 0 1280 720".equals(root.getAttribute("viewBox")))
                throw new IllegalArgumentException();
            var nodes = doc.getElementsByTagName("*");
            if (nodes.getLength() > 2000) throw new IllegalArgumentException();
            for (int n = 0; n < nodes.getLength(); n++) {
                Element e = (Element) nodes.item(n);
                if (!TAGS.contains(e.getLocalName())
                        || !"http://www.w3.org/2000/svg".equals(e.getNamespaceURI()))
                    throw new IllegalArgumentException();
                var attrs = e.getAttributes();
                for (int a = 0; a < attrs.getLength(); a++) {
                    var attr = attrs.item(a);
                    String v = attr.getNodeValue().toLowerCase(Locale.ROOT);
                    if (!ATTRS.contains(attr.getNodeName())
                            || v.contains("url(")
                            || v.contains("javascript:")
                            || v.contains("data:")) throw new IllegalArgumentException();
                }
            }
            if (!root.getTextContent().contains("未批准草稿")) throw new IllegalArgumentException();
        } catch (Exception e) {
            throw PresalesModelAdapter.error(422, "PPT_SVG_INVALID");
        }
    }
}
