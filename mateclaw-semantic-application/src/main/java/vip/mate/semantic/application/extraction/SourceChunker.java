package vip.mate.semantic.application.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import static vip.mate.semantic.application.extraction.ExtractionContracts.*;

/** Deterministic, complete code point coverage. Input is rejected, never silently truncated. */
public final class SourceChunker {
    public static final int MAX_CODE_POINTS = 100_000;
    public static final int CHUNK_SIZE = 6_000;
    public static final int OVERLAP = 300;
    public static final int MAX_CHUNKS = 20;

    public List<Chunk> split(String text) {
        Objects.requireNonNull(text, "text");
        int length = text.codePointCount(0, text.length());
        int stride = CHUNK_SIZE - OVERLAP;
        int count = length == 0 ? 0 : 1 + Math.max(0, (length - CHUNK_SIZE + stride - 1) / stride);
        if (length > MAX_CODE_POINTS || count > MAX_CHUNKS) {
            throw new IllegalArgumentException("source exceeds extraction input limits");
        }
        List<Chunk> chunks = new ArrayList<>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            int start = ordinal * stride;
            int end = Math.min(start + CHUNK_SIZE, length);
            chunks.add(new Chunk(ordinal, start, text.substring(text.offsetByCodePoints(0, start),
                    text.offsetByCodePoints(0, end))));
        }
        return List.copyOf(chunks);
    }
}
