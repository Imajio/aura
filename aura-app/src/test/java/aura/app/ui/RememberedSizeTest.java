package aura.app.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * When the window writes its size, and which size it writes. AuraWindow feeds
 * this every resize and asks it to save when the window closes and when Aura
 * exits, so each rule below is one the window follows.
 */
class RememberedSizeTest {

    private static final Dimension OPENING = new Dimension(900, 640);

    private final List<Dimension> written = new ArrayList<>();
    private final RememberedSize.Writer recorder =
        (width, height) -> written.add(new Dimension(width, height));

    @Test
    void aWindowNobodyResizedWritesNothing() throws IOException {
        // Breaks if saveIfChanged writes without comparing: every close and
        // every exit would then pin today's default size into config.yaml.
        RememberedSize size = new RememberedSize(OPENING);

        size.saveIfChanged(recorder);

        assertThat(written).isEmpty();
    }

    @Test
    void aResizedWindowWritesItsNewSizeOnceAndThenNothing() throws IOException {
        // Breaks if a successful write is not recorded as saved: the close and
        // the shutdown hook that follows Exit would then both write.
        RememberedSize size = new RememberedSize(OPENING);
        size.resized(new Dimension(1000, 700), true);

        size.saveIfChanged(recorder);
        size.saveIfChanged(recorder);

        assertThat(written).containsExactly(new Dimension(1000, 700));
    }

    @Test
    void aWindowMaximisedAfterResizingWritesTheSizeFromBeforeMaximising() throws IOException {
        // Breaks if a resize while maximised is counted: the next start would
        // open at the whole work area with nothing to restore to.
        RememberedSize size = new RememberedSize(OPENING);
        size.resized(new Dimension(1000, 700), true);
        size.resized(new Dimension(1455, 915), false);

        size.saveIfChanged(recorder);

        assertThat(written).containsExactly(new Dimension(1000, 700));
    }

    @Test
    void aWindowResizedBackToTheSizeItOpenedAtWritesNothing() throws IOException {
        // The file already holds that size, or none at all for the default.
        RememberedSize size = new RememberedSize(OPENING);
        size.resized(new Dimension(1000, 700), true);
        size.resized(new Dimension(OPENING), true);

        size.saveIfChanged(recorder);

        assertThat(written).isEmpty();
    }

    @Test
    void aWindowOpenedAtAClampedSizeAndLeftAloneWritesNothing() throws IOException {
        // WindowSize.opening fits a saved size to the screen. The file keeps
        // the larger one, and the next start clamps it again, so an untouched
        // window has nothing new to say.
        Dimension clamped = WindowSize.opening(3000, 2000, OPENING, new Dimension(720, 480),
            new Rectangle(0, 0, 1440, 852));
        RememberedSize size = new RememberedSize(clamped);

        size.saveIfChanged(recorder);

        assertThat(clamped).isEqualTo(new Dimension(1440, 852));
        assertThat(written).isEmpty();
    }

    @Test
    void aFailedWriteIsTriedAgainByTheNextSave() throws IOException {
        // Breaks if the size is recorded as saved before the write succeeds:
        // a close that failed to write would then stop the shutdown hook from
        // trying again.
        RememberedSize size = new RememberedSize(OPENING);
        size.resized(new Dimension(1000, 700), true);

        assertThatThrownBy(() -> size.saveIfChanged((width, height) -> {
            throw new IOException("read-only");
        })).isInstanceOf(IOException.class);
        size.saveIfChanged(recorder);

        assertThat(written).containsExactly(new Dimension(1000, 700));
    }

    @Test
    void aSizeTheCallerChangesAfterwardsIsNotTheSizeRemembered() throws IOException {
        // Frame.getSize() hands out a fresh Dimension today, but a Dimension is
        // mutable, and a kept reference would let a caller's later change
        // rewrite what the next save writes.
        RememberedSize size = new RememberedSize(OPENING);
        Dimension reported = new Dimension(1000, 700);
        size.resized(reported, true);
        reported.setSize(1200, 800);

        size.saveIfChanged(recorder);

        assertThat(written).containsExactly(new Dimension(1000, 700));
    }
}
