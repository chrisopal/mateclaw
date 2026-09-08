package vip.mate.semantic.application.extraction;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class SourceChunkerTest {
    @Test void unicodeChunksCoverEntireSnapshotWithExactOverlap() {
        String text = "😀".repeat(100_000);
        var chunks = new SourceChunker().split(text);
        assertEquals(18, chunks.size());
        assertTrue(chunks.size() <= 20);
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            assertEquals(i, chunk.ordinal());
            assertEquals(i * 5700, chunk.startCodePoint());
            assertEquals(Math.min(6000, 100_000 - i * 5700), chunk.text().codePointCount(0, chunk.text().length()));
            assertEquals(text.substring(text.offsetByCodePoints(0, i * 5700), text.offsetByCodePoints(0, Math.min(100_000, i * 5700 + 6000))), chunk.text());
        }
    }
    @Test void rejectsInputBeforeItCouldExceedTwentyChunks() {
        assertThrows(IllegalArgumentException.class, () -> new SourceChunker().split("a".repeat(114_001)));
        assertThrows(IllegalArgumentException.class, () -> new SourceChunker().split("😀".repeat(100_001)));
    }
    @Test void handlesEmptyAndExactBoundaryWithoutTrailingOverlapOnlyChunk() {
        assertTrue(new SourceChunker().split("").isEmpty());
        assertEquals(1, new SourceChunker().split("a".repeat(6000)).size());
        assertEquals(2, new SourceChunker().split("a".repeat(6001)).size());
    }
}
