package eu.neydev.birthday.platform.telegram;

import com.sun.net.httpserver.HttpServer;
import eu.neydev.birthday.core.api.InlineKeyboard;
import eu.neydev.birthday.core.api.OutboundMessage;
import eu.neydev.birthday.core.api.Platform;
import eu.neydev.birthday.core.text.RichText;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.TelegramUrl;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/** The execute() contract: requests go to the given base URL in Bot API format. */
class TelegramContractTest {

    private static final String TOKEN = "123456:TEST";

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        server.createContext("/", exchange -> {

            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String json = """
                    {"ok":true,"result":{"message_id":7,"date":1,
                     "chat":{"id":100,"type":"private"}}}
                    """;
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);

            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }

        });

        server.start();

    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private TelegramAdapter adapter() {
        return new TelegramAdapter(TOKEN, null, "Birthday",
                TelegramUrl.builder()
                        .schema("http")
                        .host("127.0.0.1")
                        .port(server.getAddress().getPort())
                        .build());
    }

    @Test
    void sendGoesToBotApiWithHtmlAndKeyboard() {

        adapter().execute(new OutboundMessage.Send(Platform.TELEGRAM, "100",
                RichText.parse("*bold*"),
                new InlineKeyboard(List.of(List.of(
                        InlineKeyboard.KeyboardButton.callback("Date", "sd"))))));

        assertThat(paths.get(0)).isEqualToIgnoringCase("/bot" + TOKEN + "/sendMessage");
        String body = bodies.get(0);
        assertThat(body).contains("\"chat_id\":\"100\"");
        assertThat(body).contains("\"parse_mode\":\"html\"");
        assertThat(body).contains("<b>");
        assertThat(body).contains("\"callback_data\":\"sd\"");

    }

}
