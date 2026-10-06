package vip.mate.presales.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Public consumer port. Implementations recheck the current reader and source permissions. */
public interface PresalesHandoffReader {
    Handoff read(String workspaceId, String projectId, String releaseId);

    final class Unavailable extends RuntimeException {
        private final int status;
        private final String code;

        public Unavailable(int status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    record Handoff(String projectId, String releaseId, String snapshotJson, String digest) {
        public static Handoff from(String projectId, String releaseId, String snapshotJson) {
            return new Handoff(projectId, releaseId, snapshotJson, digestOf(snapshotJson));
        }

        public static String digestOf(String text) {
            try {
                return HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
