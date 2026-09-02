package aura.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A project from the registry. An agent is launched only inside the project's
 * directory and its {@code addDirs}; a path outside the registry is never
 * launched.
 */
public record Project(
    String name,
    List<String> aliases,
    Path path,
    Agent agent,
    List<Path> addDirs,
    Set<String> allow,
    Set<String> confirm,
    Set<String> deny
) {
    public Project {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(agent, "agent");
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        addDirs = addDirs == null ? List.of() : List.copyOf(addDirs);
        allow = allow == null ? Set.of() : Set.copyOf(allow);
        confirm = confirm == null ? Set.of() : Set.copyOf(confirm);
        deny = deny == null ? Set.of() : Set.copyOf(deny);
    }

    /** All the words the project can be called by aloud: its name plus its aliases. */
    public List<String> spokenForms() {
        List<String> forms = new java.util.ArrayList<>(aliases.size() + 1);
        forms.add(name);
        forms.addAll(aliases);
        return List.copyOf(forms);
    }
}
