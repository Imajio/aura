package aura.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Looks up a project by name and by a spoken phrase.
 *
 * <p>Matching is deliberately strict in one place and lenient in another: one
 * recognition error inside a word is forgiven, but two named targets are not.
 * Guessing the project in which an agent will get write access is not allowed.
 */
public final class ProjectRegistry {

    private static final int MAX_EDIT_DISTANCE = 1;

    private final List<Project> projects;

    public ProjectRegistry(List<Project> projects) {
        this.projects = List.copyOf(projects);
    }

    public List<Project> all() {
        return projects;
    }

    public Optional<Project> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String needle = name.toLowerCase(Locale.ROOT);
        return projects.stream()
            .filter(p -> p.name().toLowerCase(Locale.ROOT).equals(needle))
            .findFirst();
    }

    /** An empty result means "ask the user", not "pick any one". */
    public Optional<Project> resolveFromSpeech(String phrase) {
        if (phrase == null || phrase.isBlank()) {
            return Optional.empty();
        }
        List<String> words = List.of(normalize(phrase).split("\\s+"));
        Set<Project> hits = new LinkedHashSet<>();

        for (Project project : projects) {
            for (String form : project.spokenForms()) {
                String target = normalize(form);
                boolean matched = words.stream().anyMatch(w ->
                    w.equals(target) || editDistanceWithin(w, target, MAX_EDIT_DISTANCE));
                if (matched) {
                    hits.add(project);
                    break;
                }
            }
        }

        return hits.size() == 1 ? Optional.of(hits.iterator().next()) : Optional.empty();
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static boolean editDistanceWithin(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return false;
        }
        // Short words don't forgive typos: at three characters a single edit
        // reaches too many unrelated words.
        if (b.length() < 4) {
            return false;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()] <= max;
    }
}
