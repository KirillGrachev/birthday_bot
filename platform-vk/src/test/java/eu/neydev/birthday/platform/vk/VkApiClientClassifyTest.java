package eu.neydev.birthday.platform.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.PlatformException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The VK error taxonomy: which failures wait, which die, and which are our own
 * bug. Error 911 (a keyboard the platform refuses) must never climb the retry
 * ladder - a retry sends the same invalid payload - and must never disable the
 * user's reminders, because the chat is healthy and the mistake is ours.
 */
class VkApiClientClassifyTest {

    private static RuntimeException classify(int code) throws Exception {
        return VkApiClient.classify(new ObjectMapper().readTree(
                "{\"error_code\": " + code + ", \"error_msg\": \"msg " + code + "\"}"), "messages.send");
    }

    @Test
    void rateLimitsWait() throws Exception {
        assertThat(classify(6)).isInstanceOf(PlatformException.RateLimitedException.class);
        assertThat(classify(29)).isInstanceOf(PlatformException.RateLimitedException.class);
    }

    @Test
    void deadChatsArePermanent() throws Exception {
        assertThat(classify(201)).isInstanceOf(PlatformException.PermanentDeliveryException.class);
        assertThat(classify(900)).isInstanceOf(PlatformException.PermanentDeliveryException.class);
    }

    @Test
    void anInvalidKeyboardIsOurBugNotTheChats() throws Exception {
        assertThat(classify(911)).isInstanceOf(PlatformException.InvalidMessageException.class);
        assertThat(classify(911)).isNotInstanceOf(PlatformException.PermanentDeliveryException.class);
    }

    @Test
    void unknownErrorsStayRetryable() throws Exception {
        RuntimeException e = classify(10);
        assertThat(e).isInstanceOf(PlatformException.class);
        assertThat(e).isNotInstanceOf(PlatformException.RateLimitedException.class);
        assertThat(e).isNotInstanceOf(PlatformException.PermanentDeliveryException.class);
        assertThat(e).isNotInstanceOf(PlatformException.InvalidMessageException.class);
    }
}
