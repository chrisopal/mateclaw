package vip.mate.semantic.core.evidence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import vip.mate.semantic.core.identity.GraphScope;
import vip.mate.semantic.core.identity.SemanticIds.SnapshotId;

/** Immutable captured source text used as the authority for evidence spans. */
public record SourceSnapshot(SnapshotId snapshotId, GraphScope scope, String text, String textDigest) {

    public SourceSnapshot {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(textDigest, "textDigest");
    }

    public SourceSnapshot(SnapshotId snapshotId, GraphScope scope, String text) {
        this(snapshotId, scope, text, sha256(text));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }
}
