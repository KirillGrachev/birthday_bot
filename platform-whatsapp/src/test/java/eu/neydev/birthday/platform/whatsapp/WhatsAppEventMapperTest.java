package eu.neydev.birthday.platform.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.neydev.birthday.core.api.IncomingUpdate;
import eu.neydev.birthday.core.api.Platform;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;

class WhatsAppEventMapperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mapsTextAndButtonReply() throws Exception {

        var text = MAPPER.readTree("""
                {"from":"7999","id":"w1","text":{"body":"/start"}}
                """);
        var mapped = WhatsAppEventMapper.mapMessage(text);
        assertThat(mapped).isPresent();
        assertThat(mapped.get().user().platform()).isEqualTo(Platform.WHATSAPP);
        assertThat(mapped.get().chatId()).isEqualTo("7999");

        var button = MAPPER.readTree("""
                {"from":"7999","id":"w2","interactive":{"button_reply":{"id":"dt","title":"x"}}}
                """);
        var callback = (IncomingUpdate.Callback) WhatsAppEventMapper.mapMessage(button).orElseThrow();
        assertThat(callback.actionId()).isEqualTo("dt");

    }

    /**
     * A row of a list message arrives under another field than a reply button, and on the
     * newer clients under a third one. All three carry the id we put on the control, so a
     * picker press and a button press look the same to the core.
     */
    @Test
    void mapsListRepliesFromBothFlows() throws Exception {

        var list = MAPPER.readTree("""
                {"from":"7999","id":"w3","interactive":{"list_reply":{"id":"zp:Europe/Moscow","title":"Moscow"}}}
                """);

        var fromList = (IncomingUpdate.Callback) WhatsAppEventMapper.mapMessage(list).orElseThrow();
        assertThat(fromList.actionId()).isEqualTo("zp:Europe/Moscow");

        var nfm = MAPPER.readTree("""
                {"from":"7999","id":"w4","interactive":{"nfm_reply":{"id":"lg:ru","title":"Russian"}}}
                """);

        var fromNfm = (IncomingUpdate.Callback) WhatsAppEventMapper.mapMessage(nfm).orElseThrow();
        assertThat(fromNfm.actionId()).isEqualTo("lg:ru");

        var empty = MAPPER.readTree("""
                {"from":"7999","id":"w5","interactive":{}}
                """);

        assertThat(WhatsAppEventMapper.mapMessage(empty)).isEmpty();

    }

}
