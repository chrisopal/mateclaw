package vip.mate.presales;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Original execution bytes, never reconstructed from today's installed skill. */
record PresalesTaskPackage(
        String format,
        String skill,
        String skillName,
        String instructions,
        Map<String, String> skillFiles,
        PresalesPresentationPackage presentation,
        String digest) {
    private static final String FORMAT = "presales-task-package-1";

    PresalesTaskPackage {
        if (!FORMAT.equals(format)
                || skillFiles == null
                || skillFiles.isEmpty()
                || skillFiles.size() > 256
                || !skillFiles.containsKey("SKILL.md")
                || instructions == null
                || instructions.isBlank()
                || instructions.getBytes(StandardCharsets.UTF_8).length > 2_000_000
                || !PresalesModelAdapter.skillName(skill).equals(skillName)
                || ("S6".equals(skill) != (presentation != null)))
            throw new IllegalArgumentException("Invalid task package");
        long size = 0;
        for (var entry : skillFiles.entrySet()) {
            String path = entry.getKey();
            if (path == null
                    || path.isBlank()
                    || path.startsWith("/")
                    || path.contains("\\")
                    || path.contains(":")
                    || path.indexOf('\0') >= 0
                    || entry.getValue() == null)
                throw new IllegalArgumentException("Invalid task package path");
            for (String part : path.split("/", -1))
                if (part.isEmpty() || part.equals(".") || part.equals(".."))
                    throw new IllegalArgumentException("Invalid task package path");
            size += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (size > 2_000_000) throw new IllegalArgumentException("Task package too large");
        skillFiles = Map.copyOf(skillFiles);
        if (!hash(format, skill, skillName, instructions, skillFiles, presentation).equals(digest))
            throw new IllegalArgumentException("Task package digest mismatch");
    }

    static PresalesTaskPackage create(
            String skill, Map<String, String> files, PresalesPresentationPackage presentation) {
        String name = PresalesModelAdapter.skillName(skill);
        String instructions = PresalesModelAdapter.instructions(skill, files.get("SKILL.md"));
        return new PresalesTaskPackage(
                FORMAT,
                skill,
                name,
                instructions,
                files,
                presentation,
                hash(FORMAT, skill, name, instructions, files, presentation));
    }

    String skillDigest() {
        return PresalesArtifactRenderer.digest(instructions.getBytes(StandardCharsets.UTF_8));
    }

    String presentationDigest() {
        return presentation == null ? "" : presentation.digest();
    }

    private static String hash(
            String format,
            String skill,
            String name,
            String instructions,
            Map<String, String> files,
            PresalesPresentationPackage presentation) {
        try {
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                for (String value : new String[] {format, skill, name, instructions})
                    write(out, value);
                out.writeInt(files.size());
                for (var entry : new TreeMap<>(files).entrySet()) {
                    write(out, entry.getKey());
                    write(out, entry.getValue());
                }
                write(out, presentation == null ? "" : presentation.digest());
            }
            return PresalesArtifactRenderer.digest(bytes.toByteArray());
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws java.io.IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }
}
