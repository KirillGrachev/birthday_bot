package eu.neydev.birthday.core.command;

import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * A single command catalog: names, aliases, options and description keys.
 * A single source of truth for the core router and Discord slash-command registration -
 * earlier names lived in two places and could drift apart.
 */
public final class CommandCatalog {

    /**
     * @param name         canonical name without a slash;
     * @param aliases      additional names (text platforms only);
     * @param descriptionKey i18n description key ({@code command.desc.*});
     * @param options      string option names (used by the slash form).
     */
    public record Spec(@NotNull String name,
                       @NotNull List<String> aliases,
                       @NotNull String descriptionKey,
                       @NotNull List<String> options) {

        public Spec(String name, String descriptionKey) {
            this(name, List.of(), descriptionKey, List.of());
        }

        public Spec(String name, String descriptionKey, List<String> options) {
            this(name, List.of(), descriptionKey, options);
        }

        public Spec(String name, List<String> aliases, String descriptionKey) {
            this(name, aliases, descriptionKey, List.of());
        }

    }

    public static final Spec START = new Spec("start", "command.desc.start");
    public static final Spec HELP = new Spec("help", "command.desc.help");
    public static final Spec DATE = new Spec("date", List.of("setdate"),
            "command.desc.date", List.of("date"));
    public static final Spec NOTIFY = new Spec("notify", "command.desc.notify");
    public static final Spec DAYS_TO = new Spec("daysto", "command.desc.daysto");
    public static final Spec DAYS_SINCE = new Spec("dayssince", "command.desc.dayssince");
    public static final Spec SETTINGS = new Spec("settings", "command.desc.settings");
    public static final Spec LANG = new Spec("lang", "command.desc.lang");
    public static final Spec TIME = new Spec("time", "command.desc.time", List.of("time"));
    public static final Spec ZONE = new Spec("zone", "command.desc.zone", List.of("zone"));
    public static final Spec ABOUT = new Spec("about", List.of("info"), "command.desc.about");
    public static final Spec DELETE = new Spec("delete", "command.desc.delete");
    public static final Spec CANCEL = new Spec("cancel", "command.desc.cancel");
    public static final Spec RELOAD = new Spec("reload", "command.desc.reload");

    private static final List<Spec> ALL = List.of(
            START, HELP, DATE, NOTIFY, DAYS_TO, DAYS_SINCE, SETTINGS, LANG,
            TIME, ZONE, ABOUT, DELETE, CANCEL, RELOAD);

    private static final Map<String, Spec> BY_NAME_OR_ALIAS = ALL.stream()
            .flatMap(spec -> java.util.stream.Stream.concat(
                    java.util.stream.Stream.of(spec.name()),
                    spec.aliases().stream())
                    .map(name -> Map.entry(name, spec)))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

    private CommandCatalog() {
    }

    public static List<Spec> all() {
        return ALL;
    }

    /** Resolving a name or alias (without a slash) into a canonical specification. */
    public static Optional<Spec> resolve(@NotNull String nameWithoutSlash) {
        return Optional.ofNullable(BY_NAME_OR_ALIAS.get(nameWithoutSlash.toLowerCase(java.util.Locale.ROOT)));
    }

}
