package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import aura.app.SidecarEvents.SidecarEvent;
import aura.core.Agent;
import aura.core.AgentEvent;
import aura.core.EventKind;
import aura.core.Project;
import aura.core.ProjectRegistry;
import aura.core.ToolClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.AbstractButton;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * The Tasks section, driven the way {@code Main} drives it: the shared dispatch
 * consumer's outcome fed back through {@link TasksPanel#taskRouted} and
 * {@link TasksPanel#taskNotStarted}, and the agent event stream fed through
 * {@link TasksPanel#acceptAgentEvent} - never through a {@code TaskDispatcher} of
 * this test's own, because the panel is never supposed to hold one either.
 *
 * <p>Rows are read from the transcript's own {@link DefaultListModel} rather than
 * from a rendered pixel, the same choice {@code VoicePanelTest} makes and for the
 * same reason: a screenshot fails on a font and passes on a panel that dropped a
 * distinction the model still carried.
 */
class TasksPanelTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendingATaskCallsTheSharedDispatchConsumerAndClearsTheField() {
        // Breaks if Send stops calling the one consumer Main hands every panel, or
        // starts building its own TaskDispatcher call - the "third path" the brief
        // warns against. Also breaks if the field is left holding the old phrase.
        Panel panel = panel();
        onEdt(() -> {
            phraseField(panel.tasks).setText("в проекте песочница прочитай readme.txt");
            button(panel.tasks, "tasks.send").doClick();

            assertThat(panel.sent).containsExactly("в проекте песочница прочитай readme.txt");
            assertThat(phraseField(panel.tasks).getText()).isEmpty();
        });
    }

    @Test
    void pressingEnterInTheFieldSendsTheSameWaySendDoes() {
        // Breaks if the field stops reacting to Enter and Send becomes the only
        // way in - a regression from the tray's own dialog, which takes Enter.
        Panel panel = panel();
        onEdt(() -> {
            phraseField(panel.tasks).setText("почини тесты");
            phraseField(panel.tasks).postActionEvent();

            assertThat(panel.sent).containsExactly("почини тесты");
        });
    }

    @Test
    void blankPhraseIsNotSent() {
        // Breaks if whitespace alone reaches the dispatcher, which would start an
        // agent on nothing and burn a session for it.
        Panel panel = panel();
        onEdt(() -> {
            phraseField(panel.tasks).setText("   ");
            button(panel.tasks, "tasks.send").doClick();

            assertThat(panel.sent).isEmpty();
        });
    }

    @Test
    void sendIsDisabledWhilePendingAndLiveAgainOnceTheTaskIsRouted() {
        // dispatch.accept now hands off to Main's own background executor and
        // returns at once - Main.java:250 - so returning proves nothing by
        // itself. Breaks if Send stays live through that window: the window
        // this section exists to show progress through would look untouched
        // for however long claude.exe takes to start, with nothing telling a
        // person their press registered.
        Panel panel = panel();
        onEdt(() -> {
            phraseField(panel.tasks).setText("почини баг");
            button(panel.tasks, "tasks.send").doClick();

            assertThat(button(panel.tasks, "tasks.send").isEnabled()).isFalse();

            panel.tasks.taskRouted("почини баг", "sandbox");

            assertThat(button(panel.tasks, "tasks.send").isEnabled()).isTrue();
        });
    }

    @Test
    void sendIsLiveAgainWhenTheDispatchDoesNotStart() {
        // The counterpart to the test above: taskNotStarted is the other of
        // the two answers a queued dispatch can bring back, and it has to
        // restore the button exactly as taskRouted does or a project-unknown
        // phrase leaves Send disabled for the rest of the session.
        Panel panel = panel();
        onEdt(() -> {
            phraseField(panel.tasks).setText("почини баг");
            button(panel.tasks, "tasks.send").doClick();

            panel.tasks.taskNotStarted("почини баг",
                "Could not tell which project. Name the project in the phrase.");

            assertThat(button(panel.tasks, "tasks.send").isEnabled()).isTrue();
        });
    }

    @Test
    void aSecondPressWhileTheFirstIsPendingIsIgnored() {
        // Breaks if submit() has no guard of its own and relies only on the
        // button's disabled state - the phrase field stays enabled on purpose
        // (so the next task can be typed while claude.exe starts), and Enter
        // in it calls submit() the same way Send does.
        Panel panel = panel();
        onEdt(() -> {
            phraseField(panel.tasks).setText("почини баг");
            button(panel.tasks, "tasks.send").doClick();
            phraseField(panel.tasks).setText("почини другой баг");
            phraseField(panel.tasks).postActionEvent();

            assertThat(panel.sent).containsExactly("почини баг");
        });
    }

    @Test
    void stopAgentCallsTheSameActionTheTrayUses() {
        // Breaks if the button moved here without being wired, which would look
        // identical until the day somebody presses it and nothing stops.
        Panel panel = panel();
        onEdt(() -> button(panel.tasks, "tasks.stop").doClick());

        assertThat(panel.stops.get()).isEqualTo(1);
    }

    @Test
    void aRoutedTaskRendersItsProject() {
        // Breaks if taskRouted stops rendering the project name Main learned from
        // TaskDispatcher.Sent, which is the one fact the tray could never show
        // anywhere but a balloon.
        Panel panel = panel();
        onEdt(() -> panel.tasks.taskRouted("прочитай readme", "sandbox"));

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).text()).contains("прочитай readme");
        assertThat(rows.get(1).text()).contains("Routed to project sandbox");
    }

    @Test
    void aTaskThatCouldNotStartRendersWhy() {
        // Breaks if taskNotStarted is dropped or its reason goes unrendered - the
        // window would then show a task simply vanishing with no explanation,
        // where the tray at least raises a balloon.
        Panel panel = panel();
        onEdt(() -> panel.tasks.taskNotStarted("почини баг",
            "Could not tell which project. Name the project in the phrase."));

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).text()).contains("Could not tell which project");
        assertThat(rows.get(1).colour()).isEqualTo(UiTheme.BAD);
    }

    @Test
    void eachAgentEventRendersOneRowWithItsKindAndTarget() {
        // Breaks if a non-DONE event stops rendering its kind or its target - the
        // one thing this section exists to show that the tray never could: which
        // tool ran, and against what.
        Panel panel = panel();
        onEdt(() -> panel.tasks.acceptAgentEvent(
            event(EventKind.TOOL_START, ToolClass.EDIT, "readme.txt", null, "")));

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).text()).contains("TOOL_START").contains("EDIT")
            .contains("readme.txt");
    }

    @Test
    void aFailedDoneEventRendersDistinctlyFromASuccessfulOne() {
        // The regression test for a6aa624 on this surface. Breaks if a DONE that
        // is not ok renders the same word as a DONE that is - "DONE" either way,
        // which is exactly the defect that fix closed in Main's own log line.
        Panel panel = panel();
        onEdt(() -> {
            panel.tasks.acceptAgentEvent(
                event(EventKind.DONE, ToolClass.OTHER, "", true, ""));
            panel.tasks.acceptAgentEvent(
                event(EventKind.DONE, ToolClass.OTHER, "", false, "Credit balance is too low"));
        });

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).text()).isEqualTo("Finished");
        assertThat(rows.get(0).colour()).isEqualTo(UiTheme.GOOD);
        assertThat(rows.get(1).text()).contains("Failed").contains("Credit balance is too low");
        assertThat(rows.get(1).colour()).isEqualTo(UiTheme.BAD);
        assertThat(rows.get(1).text()).isNotEqualTo(rows.get(0).text());
    }

    @Test
    void aFailedDoneEventWithNoReasonStillSaysSo() {
        // Breaks if the blank-summaryHint fallback Main's sink carries -
        // "the agent gave no reason" - is dropped here, leaving a bare "Failed"
        // that is distinct from success but not from any other failure.
        Panel panel = panel();
        onEdt(() -> panel.tasks.acceptAgentEvent(
            event(EventKind.DONE, ToolClass.OTHER, "", false, "")));

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows.get(0).text()).contains("the agent gave no reason");
    }

    @Test
    void narrationLinesAppearInTheLog() {
        // Breaks if the panel stops listening for the sidecar's own narration
        // events - the sentence the owner would have heard, shown even with no
        // voice configured, per the same contract StatusPanel and VoicePanel keep.
        Panel panel = panel();
        onEdt(() -> panel.event(
            "{\"ev\":\"narration\",\"text\":\"Reading the readme file.\"}"));

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).text()).contains("Reading the readme file.");
    }

    @Test
    void theListIsTrimmedToItsCapKeepingTheNewestRows() {
        // Breaks if the cap is dropped (an all-day run then grows the transcript
        // without bound) or if it trims from the wrong end - keeping the first
        // events ever seen instead of the most recent ones, which is the one a
        // person actually wants when they glance at a section left running.
        Panel panel = panel();
        int extra = 7;
        onEdt(() -> {
            for (int i = 0; i < TasksPanel.MAX_ROWS + extra; i++) {
                panel.tasks.acceptAgentEvent(
                    event(EventKind.TOOL_START, ToolClass.READ, "file-" + i + ".txt", null, ""));
            }
        });

        List<TasksPanel.Row> rows = rows(panel.tasks);
        assertThat(rows).hasSize(TasksPanel.MAX_ROWS);
        assertThat(rows.get(0).text()).contains("file-" + extra + ".txt");
        assertThat(rows.get(rows.size() - 1).text())
            .contains("file-" + (TasksPanel.MAX_ROWS + extra - 1) + ".txt");
    }

    @Test
    void theProjectsCardListsEachProjectWithItsAliases() {
        // Breaks if the registry's projects stop being shown, or their aliases -
        // the one preview of what routing will match that a person has before
        // they type a phrase and find out the hard way.
        ProjectRegistry registry = new ProjectRegistry(List.of(
            project("sandbox", List.of("песочница", "sandbox"))));
        Panel panel = panel(registry);

        onEdt(() -> {
            assertThat(anyLabelContains(panel.tasks, "sandbox")).isTrue();
            assertThat(anyLabelContains(panel.tasks, "песочница")).isTrue();
        });
    }

    @Test
    void aProjectWithNoAliasesSaysSo() {
        // Breaks if a project with an empty alias list renders a blank cell
        // instead of saying plainly that it has no aliases - a blank reads as a
        // missing feature, not as an honest "there is nothing here".
        ProjectRegistry registry = new ProjectRegistry(List.of(project("backend", List.of())));
        Panel panel = panel(registry);

        onEdt(() -> assertThat(anyLabelContains(panel.tasks, "no aliases")).isTrue());
    }

    private static AgentEvent event(EventKind kind, ToolClass toolClass, String target,
                                    Boolean ok, String summaryHint) {
        return AgentEvent.builder()
            .ts(Instant.EPOCH)
            .sessionId("s1")
            .agent(Agent.CLAUDE)
            .kind(kind)
            .toolClass(toolClass)
            .target(target)
            .ok(ok)
            .summaryHint(summaryHint)
            .build();
    }

    private static Project project(String name, List<String> aliases) {
        return new Project(name, aliases, Path.of("C:\\projects\\" + name), Agent.CLAUDE,
            List.of(), Set.of(), Set.of(), Set.of());
    }

    /** The panel, what it sent, and how many times it was told to stop. */
    private static final class Panel {

        private final List<String> sent = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger stops = new AtomicInteger();
        private final TasksPanel tasks;

        Panel(ProjectRegistry registry) {
            tasks = new TasksPanel(sent::add, stops::incrementAndGet, registry);
        }

        void event(String json) {
            try {
                var node = MAPPER.readTree(json);
                tasks.accept(new SidecarEvent(node.path("ev").asText(), node));
            } catch (Exception e) {
                throw new AssertionError("bad test fixture: " + json, e);
            }
        }
    }

    private static Panel panel() {
        return panel(new ProjectRegistry(List.of(project("sandbox", List.of("песочница")))));
    }

    private static Panel panel(ProjectRegistry registry) {
        Panel[] built = new Panel[1];
        onEdt(() -> built[0] = new Panel(registry));
        return built[0];
    }

    private static List<TasksPanel.Row> rows(TasksPanel panel) {
        JList<?> list = (JList<?>) find(panel, "tasks.log");
        DefaultListModel<?> model = (DefaultListModel<?>) list.getModel();
        List<TasksPanel.Row> result = new ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            result.add((TasksPanel.Row) model.getElementAt(i));
        }
        return result;
    }

    private static JTextField phraseField(Container root) {
        return (JTextField) find(root, "tasks.phrase");
    }

    /** Whether any label anywhere in the panel holds text containing the needle. */
    private static boolean anyLabelContains(Container root, String needle) {
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label && plain(label.getText()).contains(needle)) {
                return true;
            }
            if (child instanceof Container container && anyLabelContains(container, needle)) {
                return true;
            }
        }
        return false;
    }

    private static String plain(String raw) {
        return raw.replaceAll("<[^>]*>", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replaceAll("\\s+", " ").trim();
    }

    private static void onEdt(Runnable work) {
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new AssertionError(e.getCause());
        }
    }

    private static AbstractButton button(Container root, String name) {
        return (AbstractButton) find(root, name);
    }

    private static Component find(Container root, String name) {
        Component found = search(root, name);
        if (found == null) {
            throw new AssertionError("no component named '" + name + "' in the panel");
        }
        return found;
    }

    private static Component search(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                return child;
            }
            if (child instanceof Container container) {
                Component found = search(container, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
