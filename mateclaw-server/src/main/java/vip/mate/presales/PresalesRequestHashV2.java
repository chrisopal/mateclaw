package vip.mate.presales;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned hash of the exact serialized presales request, including malformed UTF-16 units. */
final class PresalesRequestHashV2 {
    private static final byte[] DOMAIN =
            "mateclaw:presales:request:v2\0".getBytes(StandardCharsets.US_ASCII);

    enum Version {
        LEGACY,
        V2
    }

    private PresalesRequestHashV2() {}

    static String digest(String encodedRequest) {
        Objects.requireNonNull(encodedRequest, "encodedRequest");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            // Emit code units directly: a charset encoder replaces isolated surrogates with '?'.
            for (int i = 0; i < encodedRequest.length(); i++) {
                char unit = encodedRequest.charAt(i);
                digest.update((byte) (unit >>> 8));
                digest.update((byte) unit);
            }
            return "v2:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean hasLegacyEncodingAmbiguity(String encodedRequest) {
        for (int i = 0; i < encodedRequest.length(); i++) {
            char unit = encodedRequest.charAt(i);
            if (unit == '?' || Character.isLowSurrogate(unit)) return true;
            if (Character.isHighSurrogate(unit)) {
                if (i + 1 >= encodedRequest.length()
                        || !Character.isLowSurrogate(encodedRequest.charAt(i + 1))) return true;
                i++;
            }
        }
        return false;
    }

    static Version versionOf(String storedHash) {
        if (storedHash != null) {
            if (storedHash.matches("[0-9a-f]{64}")) return Version.LEGACY;
            if (storedHash.matches("v2:[0-9a-f]{64}")) return Version.V2;
        }
        throw new IllegalArgumentException("Unsupported presales request hash version or format");
    }
}
