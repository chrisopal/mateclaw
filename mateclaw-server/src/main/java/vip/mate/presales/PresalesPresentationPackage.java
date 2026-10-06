package vip.mate.presales;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Immutable executable bytes owned by the server task package, never by a model response. */
record PresalesPresentationPackage(
        String format,
        String bindingId,
        String instructions,
        Map<String, String> filesBase64,
        String runtimeIdentity,
        String digest) {
    private static final String FORMAT = "presales-svg-1-package-v1";
    static final int MAX_FILE_BYTES = 8_000_000;
    private static final int MAX_TOTAL_BYTES = 32_000_000;
    private static final int MAX_FILES = 512;
    private static final List<String> DIRECTORIES =
            List.of(
                    "scripts/svg_to_pptx",
                    "scripts/svg_quality",
                    "scripts/pptx_to_svg",
                    "scripts/pptx_shapes");
    // Audited presales-svg-1 native, quick-generate, no-notes dependency closure. Additional
    // resources inside these module directories are captured too; other workflows are excluded.
    static final Set<String> REQUIRED_FILES =
            Set.of(
                    "LICENSE",
                    "SKILL.md",
                    "SPONSORS.md",
                    "SPONSORS_CN.md",
                    "scripts/attribution_guard.py",
                    "scripts/config.py",
                    "scripts/console_encoding.py",
                    "scripts/error_helper.py",
                    "scripts/language_tags.py",
                    "scripts/native_enhance_pptx.py",
                    "scripts/native_enhance_pptx_core.py",
                    "scripts/native_payloads.py",
                    "scripts/pptx_animation_presets.json",
                    "scripts/pptx_animations.py",
                    "scripts/pptx_effects.py",
                    "scripts/pptx_opc_validation.py",
                    "scripts/pptx_shapes/__init__.py",
                    "scripts/pptx_shapes/data/LICENSE-APACHE-2.0.txt",
                    "scripts/pptx_shapes/data/LICENSE-OPEN-XML-SDK-MIT.txt",
                    "scripts/pptx_shapes/data/NOTICE.md",
                    "scripts/pptx_shapes/data/presetShapeDefinitions.xml",
                    "scripts/pptx_shapes/data/shape_type_values.txt",
                    "scripts/pptx_shapes/errors.py",
                    "scripts/pptx_shapes/formula.py",
                    "scripts/pptx_shapes/loader.py",
                    "scripts/pptx_shapes/models.py",
                    "scripts/pptx_shapes/registry.py",
                    "scripts/pptx_shapes/semantic_hash.py",
                    "scripts/pptx_shapes/xml_safety.py",
                    "scripts/pptx_to_svg/__init__.py",
                    "scripts/pptx_to_svg/chart_to_svg.py",
                    "scripts/pptx_to_svg/chartex_to_svg.py",
                    "scripts/pptx_to_svg/color_resolver.py",
                    "scripts/pptx_to_svg/converter.py",
                    "scripts/pptx_to_svg/custgeom_to_svg.py",
                    "scripts/pptx_to_svg/effect_to_svg.py",
                    "scripts/pptx_to_svg/emu_units.py",
                    "scripts/pptx_to_svg/fill_to_svg.py",
                    "scripts/pptx_to_svg/import_diagnostics.py",
                    "scripts/pptx_to_svg/ln_to_svg.py",
                    "scripts/pptx_to_svg/normalized_chart_svg.py",
                    "scripts/pptx_to_svg/ooxml_loader.py",
                    "scripts/pptx_to_svg/pic_to_svg.py",
                    "scripts/pptx_to_svg/preset_authoring.py",
                    "scripts/pptx_to_svg/preset_registry_to_svg.py",
                    "scripts/pptx_to_svg/preset_svg_markup.py",
                    "scripts/pptx_to_svg/prstgeom_to_svg.py",
                    "scripts/pptx_to_svg/shape_walker.py",
                    "scripts/pptx_to_svg/slide_to_svg.py",
                    "scripts/pptx_to_svg/tbl_to_svg.py",
                    "scripts/pptx_to_svg/txbody_to_svg.py",
                    "scripts/pptx_transitions.py",
                    "scripts/project_management/__init__.py",
                    "scripts/project_management/cli.py",
                    "scripts/project_management/paths.py",
                    "scripts/project_management/project_specs.py",
                    "scripts/project_manager.py",
                    "scripts/project_utils.py",
                    "scripts/register_template.py",
                    "scripts/resource_paths.py",
                    "scripts/slide_roster.py",
                    "scripts/svg_finalize/__init__.py",
                    "scripts/svg_finalize/embed_icons.py",
                    "scripts/svg_finalize/flatten_tspan.py",
                    "scripts/svg_quality/__init__.py",
                    "scripts/svg_quality/checker.py",
                    "scripts/svg_quality/cli.py",
                    "scripts/svg_quality/svg_contracts.py",
                    "scripts/svg_quality/xml_support.py",
                    "scripts/svg_quality_checker.py",
                    "scripts/svg_to_pptx.py",
                    "scripts/svg_to_pptx/__init__.py",
                    "scripts/svg_to_pptx/animation_config.py",
                    "scripts/svg_to_pptx/canvas_contract.py",
                    "scripts/svg_to_pptx/drawingml/__init__.py",
                    "scripts/svg_to_pptx/drawingml/context.py",
                    "scripts/svg_to_pptx/drawingml/converter.py",
                    "scripts/svg_to_pptx/drawingml/elements.py",
                    "scripts/svg_to_pptx/drawingml/paths.py",
                    "scripts/svg_to_pptx/drawingml/styles.py",
                    "scripts/svg_to_pptx/drawingml/text_properties.py",
                    "scripts/svg_to_pptx/drawingml/theme_colors.py",
                    "scripts/svg_to_pptx/drawingml/theme_fonts.py",
                    "scripts/svg_to_pptx/drawingml/utils.py",
                    "scripts/svg_to_pptx/geometry_properties.py",
                    "scripts/svg_to_pptx/native_objects/__init__.py",
                    "scripts/svg_to_pptx/native_objects/chart_data.py",
                    "scripts/svg_to_pptx/native_objects/chart_style.py",
                    "scripts/svg_to_pptx/native_objects/chart_xml.py",
                    "scripts/svg_to_pptx/native_objects/chartex.py",
                    "scripts/svg_to_pptx/native_objects/fallback_hash.py",
                    "scripts/svg_to_pptx/native_objects/marker_attributes.py",
                    "scripts/svg_to_pptx/native_objects/marker_common.py",
                    "scripts/svg_to_pptx/native_objects/marker_status.py",
                    "scripts/svg_to_pptx/native_objects/table.py",
                    "scripts/svg_to_pptx/native_objects/workbook.py",
                    "scripts/svg_to_pptx/pptx_package/__init__.py",
                    "scripts/svg_to_pptx/pptx_package/builder.py",
                    "scripts/svg_to_pptx/pptx_package/cli.py",
                    "scripts/svg_to_pptx/pptx_package/dimensions.py",
                    "scripts/svg_to_pptx/pptx_package/discovery.py",
                    "scripts/svg_to_pptx/pptx_package/media.py",
                    "scripts/svg_to_pptx/pptx_package/narration.py",
                    "scripts/svg_to_pptx/pptx_package/notes.py",
                    "scripts/svg_to_pptx/pptx_package/slide_xml.py",
                    "scripts/svg_to_pptx/pptx_package/template_structure.py",
                    "scripts/svg_to_pptx/pptx_package/template_validation.py",
                    "scripts/svg_to_pptx/semantic_markers.py",
                    "scripts/svg_to_pptx/shape_boolean.py",
                    "scripts/svg_to_pptx/text_outline.py",
                    "scripts/svg_to_pptx/tspan_flattener.py",
                    "scripts/svg_to_pptx/use_expander.py",
                    "scripts/template_fill_pptx.py",
                    "scripts/template_fill_pptx/cli.py",
                    "scripts/template_preview_pptx.py",
                    "scripts/update_spec.py",
                    "scripts/visualization_catalog.py",
                    "scripts/workflow_transcript.py");

    PresalesPresentationPackage {
        if (!FORMAT.equals(format)
                || bindingId == null
                || !bindingId.matches("[0-9]+")
                || instructions == null
                || instructions.isBlank()
                || instructions.length() > MAX_FILE_BYTES
                || runtimeIdentity == null
                || runtimeIdentity.isBlank()
                || runtimeIdentity.length() > 2_000_000)
            throw new IllegalArgumentException("Invalid presentation package identity");
        filesBase64 = Collections.unmodifiableMap(validateFiles(filesBase64));
        if (!canonical(bindingId, instructions, filesBase64, runtimeIdentity).equals(digest))
            throw new IllegalArgumentException("Invalid presentation package digest");
    }

    static PresalesPresentationPackage create(
            String bindingId,
            String instructions,
            Map<String, String> files,
            String runtimeIdentity) {
        var checked = validateFiles(files);
        return new PresalesPresentationPackage(
                FORMAT,
                bindingId,
                instructions,
                checked,
                runtimeIdentity,
                canonical(bindingId, instructions, checked, runtimeIdentity));
    }

    private static TreeMap<String, String> validateFiles(Map<String, String> files) {
        if (files == null
                || files.size() > MAX_FILES
                || !files.keySet().containsAll(REQUIRED_FILES))
            throw new IllegalArgumentException("Incomplete presentation package");
        var checked = new TreeMap<String, String>();
        long total = 0;
        for (var entry : files.entrySet()) {
            String path = entry.getKey(), encoded = entry.getValue();
            validatePath(path);
            if (encoded == null || encoded.length() > ((MAX_FILE_BYTES + 2L) / 3L) * 4L)
                throw new IllegalArgumentException("Oversize presentation resource");
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length > MAX_FILE_BYTES
                    || (total += bytes.length) > MAX_TOTAL_BYTES
                    || !Base64.getEncoder().encodeToString(bytes).equals(encoded))
                throw new IllegalArgumentException("Invalid presentation resource");
            checked.put(path, encoded);
        }
        return checked;
    }

    private static void validatePath(String path) {
        if (path == null
                || path.length() > 240
                || !path.matches("[A-Za-z0-9_-][A-Za-z0-9_./-]*")
                || Arrays.stream(path.split("/", -1))
                        .anyMatch(p -> p.isEmpty() || p.startsWith("."))
                || !(REQUIRED_FILES.contains(path)
                        || DIRECTORIES.stream().anyMatch(d -> path.startsWith(d + "/"))))
            throw new IllegalArgumentException("Unsafe presentation resource path");
    }

    static Map<String, String> captureFiles(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute)) throw new IOException("Symlink presentation root");
        absolute = absolute.toRealPath();
        Set<String> selected = selectFiles(absolute);
        var files = new TreeMap<String, String>();
        long total = 0;
        for (String name : selected) {
            byte[] bytes = stableRead(absolute.resolve(name));
            if ((total += bytes.length) > MAX_TOTAL_BYTES)
                throw new IOException("Oversize presentation package");
            files.put(name, Base64.getEncoder().encodeToString(bytes));
        }
        if (!selected.equals(selectFiles(absolute)))
            throw new IOException("Presentation tree changed");
        for (var entry : files.entrySet()) {
            if (!Arrays.equals(
                    Base64.getDecoder().decode(entry.getValue()),
                    stableRead(absolute.resolve(entry.getKey()))))
                throw new IOException("Presentation resource changed");
        }
        if (!selected.equals(selectFiles(absolute)))
            throw new IOException("Presentation tree changed");
        return files;
    }

    private static Set<String> selectFiles(Path root) throws IOException {
        var selected = new TreeSet<>(REQUIRED_FILES);
        for (String directory : DIRECTORIES) {
            Path dir = root.resolve(directory);
            requireNoSymlinks(dir);
            try (var stream = Files.walk(dir)) {
                var iterator = stream.iterator();
                int visited = 0;
                while (iterator.hasNext()) {
                    if (++visited > MAX_FILES * 4)
                        throw new IOException("Oversize presentation tree");
                    Path file = iterator.next();
                    String name = root.relativize(file).toString().replace('\\', '/');
                    if (Arrays.asList(name.split("/")).contains("__pycache__")
                            || file.getFileName().toString().equals(".DS_Store")
                            || name.endsWith(".pyc")) continue;
                    if (Files.isSymbolicLink(file))
                        throw new IOException("Symlink in presentation package");
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    validatePath(name);
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        throw new IOException("Nonregular presentation resource");
                    selected.add(name);
                    if (selected.size() > MAX_FILES)
                        throw new IOException("Too many presentation resources");
                }
            }
        }
        return selected;
    }

    private static byte[] stableRead(Path file) throws IOException {
        requireNoSymlinks(file);
        var before =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > MAX_FILE_BYTES)
            throw new IOException("Invalid presentation resource");
        byte[] bytes;
        try (SeekableByteChannel channel =
                        Files.newByteChannel(
                                file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                var out = new ByteArrayOutputStream()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) != -1) {
                buffer.flip();
                if (out.size() + buffer.remaining() > MAX_FILE_BYTES)
                    throw new IOException("Resource grew during capture");
                out.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            bytes = out.toByteArray();
        }
        var after =
                Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireNoSymlinks(file);
        if (!after.isRegularFile()
                || !Objects.equals(before.fileKey(), after.fileKey())
                || before.size() != after.size()
                || bytes.length != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IOException("Presentation resource changed during capture");
        return bytes;
    }

    private static void requireNoSymlinks(Path path) throws IOException {
        for (Path part = path; part != null; part = part.getParent())
            if (Files.isSymbolicLink(part)) throw new IOException("Symlink in presentation path");
    }

    void materialize(Path root) throws IOException {
        Files.createDirectory(root);
        root = root.toRealPath();
        for (var file : filesBase64.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.write(
                    target,
                    Base64.getDecoder().decode(file.getValue()),
                    StandardOpenOption.CREATE_NEW);
        }
        verifyMaterialized(root);
    }

    void verifyMaterialized(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("Symlink presentation root");
        root = root.toRealPath();
        Path checkedRoot = root;
        try (var walk = Files.walk(root)) {
            var actual =
                    walk.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                            .map(checkedRoot::relativize)
                            .map(Path::toString)
                            .map(s -> s.replace('\\', '/'))
                            .collect(java.util.stream.Collectors.toSet());
            if (!actual.equals(filesBase64.keySet()))
                throw new IllegalArgumentException("Presentation file set changed");
        }
        for (var entry : filesBase64.entrySet())
            if (!Arrays.equals(
                    Base64.getDecoder().decode(entry.getValue()),
                    stableRead(root.resolve(entry.getKey()))))
                throw new IllegalArgumentException("Presentation bytes changed");
    }

    byte[] bytes(String path) {
        return Base64.getDecoder().decode(filesBase64.get(path));
    }

    private static String canonical(
            String binding, String instructions, Map<String, String> files, String runtime) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            frame(out, FORMAT.getBytes(StandardCharsets.UTF_8));
            frame(out, binding.getBytes(StandardCharsets.UTF_8));
            frame(out, instructions.getBytes(StandardCharsets.UTF_8));
            frame(out, runtime.getBytes(StandardCharsets.UTF_8));
            out.writeInt(files.size());
            for (var file : new TreeMap<>(files).entrySet()) {
                frame(out, file.getKey().getBytes(StandardCharsets.UTF_8));
                frame(out, Base64.getDecoder().decode(file.getValue()));
            }
            return PresalesArtifactRenderer.digest(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void frame(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
