package aura.app.ui;

import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;

/**
 * A failure's cause in a few plain words, for a sentence the window shows.
 *
 * <p>The file system's exceptions carry the path as their whole message, so a
 * sentence built from {@code getMessage()} named the file twice and never said
 * what was wrong with it: "Could not save C:\...\config.yaml: C:\...\config.yaml".
 * The kind of exception is the part a person can act on.
 */
final class FailureText {

    private FailureText() {
    }

    /** What went wrong, without the path the sentence around it already names. */
    static String of(Exception failure) {
        if (failure instanceof AccessDeniedException) {
            return "access was denied";
        }
        if (failure instanceof NoSuchFileException) {
            return "part of the path does not exist";
        }
        if (failure instanceof FileAlreadyExistsException) {
            return "a file is in the way";
        }
        if (failure instanceof FileSystemException system && system.getReason() != null
                && !system.getReason().isBlank()) {
            return system.getReason();
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
