package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The words a failed file operation turns into. The file system's exceptions
 * carry only the path as their message, and a sentence that already names the
 * path then said nothing about what went wrong.
 */
class FailureTextTest {

    /**
     * Breaks if a read-only file is described by its path again, which is what
     * "Use this voice" and the theme control would then print twice. Written
     * against a real read-only file, so the exception is the one Windows raises.
     */
    @Test
    void aReadOnlyFileSaysAccessWasDeniedAndNotItsPath(@TempDir Path tmp) throws Exception {
        Path file = Files.writeString(tmp.resolve("config.yaml"), "theme: dark\n");
        assertThat(file.toFile().setReadOnly()).isTrue();
        try {
            Files.writeString(file, "theme: light\n");
            throw new AssertionError("the read-only file took a write");
        } catch (java.io.IOException e) {
            assertThat(FailureText.of(e)).isEqualTo("access was denied").doesNotContain(tmp.toString());
        } finally {
            file.toFile().setWritable(true);
        }
    }

    @Test
    void aMissingParentSaysPartOfThePathDoesNotExist() {
        assertThat(FailureText.of(new NoSuchFileException("C:\\nowhere\\logs")))
            .isEqualTo("part of the path does not exist");
    }

    @Test
    void anotherFileSystemFailureGivesItsReasonRatherThanItsPath() {
        assertThat(FailureText.of(new FileSystemException("C:\\x", null, "the disk is full")))
            .isEqualTo("the disk is full");
    }

    @Test
    void aFailureWithNoMessageIsNamedByItsKind() {
        assertThat(FailureText.of(new UnsupportedOperationException()))
            .isEqualTo("UnsupportedOperationException");
        assertThat(FailureText.of(new AccessDeniedException("C:\\x"))).isEqualTo("access was denied");
    }
}
