package ai.godance.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ContentPart;
import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.gateway.ChatDtos.TextPart;

/** {@link ChatMessage}: a string is one text part, and reads back as that string. */
class ChatMessageTest {

    @Test
    void content_givenAString_expectsThatStringAndOneTextPart() {
        ChatMessage m = new ChatMessage("user", "Hello\nthere");

        assertThat(m.content()).isEqualTo("Hello\nthere");
        assertThat(m.parts()).containsExactly(new TextPart("Hello\nthere"));
        assertThat(m.hasImages()).isFalse();
        assertThat(m.imageCount()).isZero();
    }

    @Test
    void content_givenNoContent_expectsNull() {
        assertThat(new ChatMessage("assistant", (String) null).content()).isNull();
        assertThat(new ChatMessage("assistant", (String) null).parts()).isEmpty();
        assertThat(new ChatMessage("assistant", (List<ContentPart>) null).content()).isNull();
    }

    @Test
    void content_givenSeveralTextParts_expectsThemJoinedByALineBreak() {
        ChatMessage m = new ChatMessage("user", List.of(new TextPart("one"), new TextPart("two")));

        assertThat(m.content()).isEqualTo("one\ntwo");
        assertThat(m.hasImages()).isFalse();
    }

    @Test
    void content_givenImages_expectsOnlyTheTextAndTheImagesCounted() {
        ImagePart image = new ImagePart(new byte[] {1, 2, 3}, "image/png", null);
        ChatMessage m = new ChatMessage("user", List.of(new TextPart("Look:"), image, image));

        assertThat(m.content()).isEqualTo("Look:");
        assertThat(m.hasImages()).isTrue();
        assertThat(m.imageCount()).isEqualTo(2);
        assertThat(new ChatMessage("user", List.<ContentPart>of(image)).content()).isEmpty();
        assertThat(image.base64()).isEqualTo("AQID");
        assertThat(image.dataUrl()).isEqualTo("data:image/png;base64,AQID");
    }
}
