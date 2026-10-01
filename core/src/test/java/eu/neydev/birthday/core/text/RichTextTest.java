package eu.neydev.birthday.core.text;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RichTextTest {

    @Test
    void parsesAllSegmentTypes() {

        RichText text = RichText.parse("hello *bold* _italic_ `code` [link](https://x.y)");
        var styled = text.segments().stream()
                .filter(segment -> !(segment instanceof RichText.Segment.Text))
                .toList();

        assertThat(styled).containsExactly(
                new RichText.Segment.Bold("bold"),
                new RichText.Segment.Italic("italic"),
                new RichText.Segment.Code("code"),
                new RichText.Segment.Link("link", "https://x.y"));

    }

    @Test
    void unclosedMarkersStayPlain() {
        RichText text = RichText.parse("asterisk * and all");
        assertThat(text.toPlainText()).isEqualTo("asterisk * and all");
    }

    @Test
    void backslashEscapesProduceLiterals() {

        RichText text = RichText.parse("star \\* not markup and \\[ not a link");
        assertThat(text.toPlainText()).isEqualTo("star * not markup and [ not a link");
        assertThat(text.segments()).allMatch(segment -> segment instanceof RichText.Segment.Text);

    }

    @Test
    void escapeValueNeutralizesMarkup() {

        String escaped = RichText.escapeValue("name *zv* [x](y)");
        assertThat(RichText.parse("hello " + escaped).toPlainText())
                .isEqualTo("hello name *zv* [x](y)");
        // an escaped value merges into a single text segment without markup
        assertThat(RichText.parse("hello " + escaped).segments()).hasSize(1);

    }

    @Test
    void plainTextRoundTrip() {
        RichText text = RichText.parse("a *b* c");
        assertThat(text.toPlainText()).isEqualTo("a b c");
    }

}
