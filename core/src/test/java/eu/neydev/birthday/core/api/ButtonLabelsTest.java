package eu.neydev.birthday.core.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The ellipsis rule every platform shares, so a clipped caption looks the same everywhere. */
class ButtonLabelsTest {

    @Test
    void aCaptionWithinTheLimitIsUntouched() {
        assertThat(ButtonLabels.truncate("Moscow UTC+3", 24)).isEqualTo("Moscow UTC+3");
        assertThat(ButtonLabels.clipped("Moscow UTC+3", 24)).isFalse();
    }

    @Test
    void aLongCaptionLosesItsTailAndSaysSo() {

        // the longest zone caption we ship, with the bullet that marks the current one
        assertThat(ButtonLabels.truncate("• Cathair Mheicsiceo UTC-6", 24))
                .isEqualTo("• Cathair Mheicsiceo UT…")
                .hasSize(24);
        assertThat(ButtonLabels.clipped("• Cathair Mheicsiceo UTC-6", 24)).isTrue();

    }

    @Test
    void aLimitOfOneStillProducesOneCharacter() {
        assertThat(ButtonLabels.truncate("Back", 1)).hasSize(1);
    }

    @Test
    void aMissingCaptionIsEmptyRatherThanNull() {
        assertThat(ButtonLabels.truncate(null, 10)).isEmpty();
        assertThat(ButtonLabels.clipped(null, 10)).isFalse();
    }

}
