package aura.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrayConfirmationProviderTest {

    @Test
    void objectArgumentsAreRenderedAsFieldLines() {
        String rendered = TrayConfirmationProvider.describeArguments(
            "{\"command\":\"zxc\",\"description\":\"List all the files\"}");

        assertThat(rendered).isEqualTo("command: zxc\ndescription: List all the files");
    }

    @Test
    void fieldOrderFollowsTheSourceJson() {
        String rendered = TrayConfirmationProvider.describeArguments(
            "{\"file_path\":\"sandbox/a.txt\",\"content\":\"hi\"}");

        assertThat(rendered).isEqualTo("file_path: sandbox/a.txt\ncontent: hi");
    }

    @Test
    void malformedJsonFallsBackToTheRawFlattenedText() {
        String rendered = TrayConfirmationProvider.describeArguments("not json at all");

        assertThat(rendered).isEqualTo("not json at all");
    }

    @Test
    void nonObjectJsonFallsBackToTheRawFlattenedText() {
        String rendered = TrayConfirmationProvider.describeArguments("[1,2,3]");

        assertThat(rendered).isEqualTo("[1,2,3]");
    }

    @Test
    void aLongFieldValueIsTruncated() {
        String longValue = "x".repeat(400);
        String rendered = TrayConfirmationProvider.describeArguments(
            "{\"command\":\"" + longValue + "\"}");

        assertThat(rendered).hasSize("command: ".length() + 300 + 1);
        assertThat(rendered).endsWith("…");
    }

    @Test
    void nullArgumentsFallBackToTheEmptyObjectLiteral() {
        assertThat(TrayConfirmationProvider.describeArguments(null)).isEqualTo("{}");
    }
}
