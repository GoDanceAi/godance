package ai.godance.config;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.BeanUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.unit.DataSize;

/**
 * The configuration reference in {@code docs/self-hosting.md}, generated from
 * the code (round 14 brief 05). It lists:
 * <ul>
 *   <li>every field of every {@code @ConfigurationProperties} class under
 *       {@code ai.godance}, nested records and beans included;</li>
 *   <li>every {@code ${ENV[:default]}} placeholder in {@code application.yml}
 *       and {@code application-prod.yml};</li>
 *   <li>every {@code @Value("${...}")} key in the main sources.</li>
 * </ul>
 * Each setting's text comes from {@link #DESCRIPTIONS}, the one hand-written
 * file. {@link ConfigurationReferenceTest} fails when a setting has no text,
 * when a text names no setting, or when the guide differs from
 * {@link #render()}; {@code backend/scripts/config-reference.sh} writes it.
 *
 * <p>Defaults are what the {@code prod} profile gives, the profile the
 * production image runs: {@code application-prod.yml} first, then
 * {@code application.yml}, then the code.
 */
final class ConfigurationReference {

    /** Surefire runs in {@code backend/}. */
    static final Path BACKEND = Path.of("").toAbsolutePath();
    static final Path GUIDE = BACKEND.resolveSibling("docs").resolve("self-hosting.md");
    static final Path DESCRIPTIONS = BACKEND.resolve("src/test/resources/config-reference/descriptions.txt");
    static final Path MAIN_SOURCES = BACKEND.resolve("src/main/java");
    static final Path BASE_YAML = BACKEND.resolve("src/main/resources/application.yml");
    static final Path PROD_YAML = BACKEND.resolve("src/main/resources/application-prod.yml");

    /** Paths as a contributor types them, from the repository root. */
    static final String GUIDE_NAME = "docs/self-hosting.md";
    static final String DESCRIPTIONS_NAME = "backend/src/test/resources/config-reference/descriptions.txt";
    static final String REGENERATE = "backend/scripts/config-reference.sh";

    static final String BEGIN = "<!-- BEGIN GENERATED CONFIGURATION REFERENCE: do not edit by hand. "
            + "Edit " + DESCRIPTIONS_NAME + ", then run " + REGENERATE + " -->";
    static final String END = "<!-- END GENERATED CONFIGURATION REFERENCE -->";

    private static final String BASE_PACKAGE = "ai.godance";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^:}]+)(?::([^}]*))?}");
    private static final Pattern VALUE_ANNOTATION =
            Pattern.compile("@Value\\(\\s*\"\\$\\{([^:}\"]+)(?::([^}\"]*))?}\"\\s*\\)");
    /** Settings outside {@code godance.*} are listed only when the YAML names an environment variable. */
    private static final String GODANCE = "godance.";

    /** One setting: a property path, or a pattern for map keys ({@code *}) and list items ({@code [*]}). */
    static final class Setting {
        final String name;
        final Set<String> readBy = new TreeSet<>();
        String codeDefault;          // from the class: @DefaultValue, an initializer, the constructor
        String valueDefault;         // from @Value("${key:default}")
        YamlValue base;                   // application.yml
        YamlValue prod;                   // application-prod.yml

        Setting(String name) {
            this.name = name;
        }

        /** The YAML entry the prod profile uses: prod's, else the base file's. */
        YamlValue effective() {
            return prod != null ? prod : base;
        }

        boolean required() {
            YamlValue y = effective();
            return y != null && y.env != null && y.envDefault == null;
        }

        String env() {
            YamlValue y = effective();
            if (y != null && y.env != null) return y.env;
            if (name.contains("*")) return null;
            if (name.equals(name.toUpperCase()) && !name.contains(".")) return name;
            return name.replace(".", "_").replace("-", "").toUpperCase();
        }

        /** A base-file variable that the prod profile overrides with a literal, so it does nothing there. */
        String ignoredEnv() {
            if (prod != null && prod.env == null && base != null && base.env != null) return base.env;
            return null;
        }

        String defaultText() {
            YamlValue y = effective();
            if (y != null) {
                if (y.env != null) return y.envDefault == null ? null : y.envDefault;
                return y.literal;
            }
            if (codeDefault != null) return codeDefault;
            return valueDefault;
        }
    }

    /** A setting's value in one YAML file: a placeholder, or a literal. */
    record YamlValue(String env, String envDefault, String literal) { }

    private ConfigurationReference() { }

    // ------------------------------------------------------------------
    // Collecting
    // ------------------------------------------------------------------

    /** Every setting, by name. */
    static Map<String, Setting> collect() throws IOException {
        Map<String, Setting> settings = new TreeMap<>();
        collectConfigurationProperties(settings);
        collectYaml(settings, BASE_YAML, true);
        collectYaml(settings, PROD_YAML, false);
        collectValueAnnotations(settings);
        return settings;
    }

    private static void collectConfigurationProperties(Map<String, Setting> settings) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(bd -> load(bd.getBeanClassName()))
                .filter(ConfigurationReference::isMainClass)
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .forEach(type -> {
                    ConfigurationProperties annotation = type.getAnnotation(ConfigurationProperties.class);
                    String prefix = annotation.prefix().isEmpty() ? annotation.value() : annotation.prefix();
                    walk(settings, prefix, type, codeDefaults(type), type.getSimpleName());
                });
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Only classes from {@code src/main/java}: the test classpath may hold others. */
    private static boolean isMainClass(Class<?> type) {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) top = top.getEnclosingClass();
        return Files.exists(MAIN_SOURCES.resolve(top.getName().replace('.', '/') + ".java"));
    }

    /** Walks a bound type: one setting per leaf, recursing into this application's own nested types. */
    private static void walk(Map<String, Setting> settings, String prefix, Class<?> type,
                             Map<String, Object> defaults, String owner) {
        for (Property p : properties(type)) {
            String name = prefix + "." + kebab(p.name());
            Object fallback = defaults.get(p.name());
            Class<?> raw = rawClass(p.type());
            if (isLeaf(raw)) {
                Setting s = settings.computeIfAbsent(name, Setting::new);
                s.readBy.add(owner);
                String text = p.defaultAnnotation() != null ? p.defaultAnnotation() : format(fallback);
                if (text != null) s.codeDefault = text;
            } else if (Map.class.isAssignableFrom(raw)) {
                Class<?> valueType = rawClass(typeArgument(p.type(), 1));
                if (isLeaf(valueType) || Collection.class.isAssignableFrom(valueType)) {
                    Setting s = settings.computeIfAbsent(name + ".*", Setting::new);
                    s.readBy.add(owner);
                    if (fallback instanceof Map<?, ?> m && !m.isEmpty()) s.codeDefault = format(fallback);
                } else {
                    walk(settings, name + ".*", valueType, Map.of(), owner);
                }
            } else if (Collection.class.isAssignableFrom(raw) && isOwnType(rawClass(typeArgument(p.type(), 0)))) {
                walk(settings, name + "[*]", rawClass(typeArgument(p.type(), 0)), Map.of(), owner);
            } else if (Collection.class.isAssignableFrom(raw)) {
                Setting s = settings.computeIfAbsent(name, Setting::new);
                s.readBy.add(owner);
                String text = p.defaultAnnotation() != null ? p.defaultAnnotation() : format(fallback);
                if (text != null) s.codeDefault = text;
            } else if (isOwnType(raw)) {
                walk(settings, name, raw, fallback == null ? Map.of() : valuesOf(fallback), owner);
            } else {
                throw new IllegalStateException("ConfigurationReference does not know how to list " + name
                        + " of type " + p.type() + " (in " + owner + "); teach isLeaf() about it");
            }
        }
    }

    private record Property(String name, Type type, String defaultAnnotation) { }

    /** A record's components, or a bean's writable properties. */
    private static List<Property> properties(Class<?> type) {
        List<Property> out = new ArrayList<>();
        if (type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            Constructor<?> canonical = canonicalConstructor(type);
            for (int i = 0; i < components.length; i++) {
                DefaultValue dv = canonical.getParameters()[i].getAnnotation(DefaultValue.class);
                String text = dv == null ? null : String.join(",", dv.value());
                out.add(new Property(components[i].getName(), components[i].getGenericType(), text));
            }
        } else {
            for (var pd : BeanUtils.getPropertyDescriptors(type)) {
                if (pd.getWriteMethod() == null || pd.getReadMethod() == null) continue;
                out.add(new Property(pd.getName(), pd.getReadMethod().getGenericReturnType(), null));
            }
        }
        return out;
    }

    private static Constructor<?> canonicalConstructor(Class<?> record) {
        Class<?>[] types = Stream.of(record.getRecordComponents()).map(RecordComponent::getType).toArray(Class<?>[]::new);
        try {
            return record.getDeclaredConstructor(types);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Defaults the class itself supplies: a bean's initialized fields, or what
     * a record's constructor fills in for a missing (null) value. A record
     * whose constructor refuses missing values has none.
     */
    private static Map<String, Object> codeDefaults(Class<?> type) {
        try {
            if (type.isRecord()) {
                Constructor<?> canonical = canonicalConstructor(type);
                canonical.setAccessible(true);
                Object[] args = Stream.of(canonical.getParameterTypes()).map(ConfigurationReference::zero).toArray();
                Object instance = canonical.newInstance(args);
                Map<String, Object> out = valuesOf(instance);
                // A primitive's zero is what was passed in, not a default.
                for (RecordComponent c : type.getRecordComponents()) {
                    if (c.getType().isPrimitive()) out.remove(c.getName());
                }
                return out;
            }
            Constructor<?> noArgs = type.getDeclaredConstructor();
            noArgs.setAccessible(true);
            return valuesOf(noArgs.newInstance());
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return 0;
    }

    /** A record's or bean's current values, by Java property name; nulls left out. */
    private static Map<String, Object> valuesOf(Object instance) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (instance.getClass().isRecord()) {
                for (RecordComponent c : instance.getClass().getRecordComponents()) {
                    c.getAccessor().setAccessible(true);
                    Object v = c.getAccessor().invoke(instance);
                    if (v != null) out.put(c.getName(), v);
                }
            } else {
                for (var pd : BeanUtils.getPropertyDescriptors(instance.getClass())) {
                    if (pd.getReadMethod() == null || pd.getWriteMethod() == null) continue;
                    Object v = pd.getReadMethod().invoke(instance);
                    if (v != null) out.put(pd.getName(), v);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static boolean isOwnType(Class<?> type) {
        return type.getName().startsWith(BASE_PACKAGE + ".") && !type.isEnum();
    }

    private static boolean isLeaf(Class<?> type) {
        return type.isPrimitive() || type.isEnum()
                || Stream.of(String.class, Boolean.class, Character.class, Number.class, Duration.class,
                        Period.class, DataSize.class, URI.class, URL.class, Charset.class, Path.class)
                        .anyMatch(leaf -> leaf.isAssignableFrom(type));
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        return Object.class;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType p && p.getActualTypeArguments().length > index) {
            return p.getActualTypeArguments()[index];
        }
        return Object.class;
    }

    static String kebab(String javaName) {
        return javaName.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase();
    }

    /** Reads one YAML file's first document (the profile-independent one) into the settings. */
    private static void collectYaml(Map<String, Setting> settings, Path file, boolean base) throws IOException {
        Map<String, Object> flat = new TreeMap<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            Object first = new org.yaml.snakeyaml.Yaml().loadAll(reader).iterator().next();
            flatten("", first, flat);
        }
        List<String> mapPatterns = settings.keySet().stream()
                .filter(n -> n.endsWith(".*")).map(n -> n.substring(0, n.length() - 1)).toList();
        for (var entry : flat.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String target = key;
            String mapPrefix = mapPatterns.stream().filter(key::startsWith).findFirst().orElse(null);
            if (mapPrefix != null) {
                // One entry of a map (a fallback chain, an alias): the setting is the map.
                target = mapPrefix + "*";
                Setting s = settings.get(target);
                YamlValue y = new YamlValue(null, null, "defined in " + file.getFileName());
                if (base) s.base = y; else s.prod = y;
                continue;
            }
            YamlValue y = yaml(value);
            boolean listed = settings.containsKey(target) || target.startsWith(GODANCE) || y.env() != null;
            if (!listed) continue;
            Setting s = settings.computeIfAbsent(target, Setting::new);
            if (base) s.base = y; else s.prod = y;
            s.readBy.add(file.getFileName().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object node, Map<String, Object> out) {
        if (node instanceof Map<?, ?> map) {
            for (var e : ((Map<Object, Object>) map).entrySet()) {
                String key = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flatten(key, e.getValue(), out);
            }
        } else {
            out.put(prefix, node);
        }
    }

    private static YamlValue yaml(Object value) {
        if (value instanceof String text) {
            Matcher m = PLACEHOLDER.matcher(text.trim());
            if (m.matches()) return new YamlValue(m.group(1), m.group(2), null);
            return new YamlValue(null, null, text);
        }
        return new YamlValue(null, null, format(value));
    }

    private static void collectValueAnnotations(Map<String, Setting> settings) throws IOException {
        List<Path> sources;
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            sources = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        for (Path source : sources) {
            Matcher m = VALUE_ANNOTATION.matcher(Files.readString(source));
            String owner = source.getFileName().toString().replace(".java", "");
            while (m.find()) {
                Setting s = settings.computeIfAbsent(m.group(1), Setting::new);
                s.readBy.add("@Value in " + owner);
                if (m.group(2) != null && s.valueDefault == null) s.valueDefault = m.group(2);
            }
        }
    }

    // ------------------------------------------------------------------
    // Describing
    // ------------------------------------------------------------------

    /**
     * The hand-written texts, by exact setting name or by a pattern where
     * {@code *} is one segment. One {@code <setting>: text} per line; a line
     * starting with whitespace continues the one above; {@code #} starts a
     * comment line. Plain text on purpose: no quoting rules to trip over.
     */
    static Map<String, String> descriptions() throws IOException {
        Map<String, String> out = new TreeMap<>();
        String key = null;
        int lineNumber = 0;
        for (String line : Files.readAllLines(DESCRIPTIONS)) {
            lineNumber++;
            if (line.isBlank() || line.startsWith("#")) continue;
            if (Character.isWhitespace(line.charAt(0))) {
                if (key == null) throw new IllegalStateException(DESCRIPTIONS_NAME + ":" + lineNumber
                        + ": a continuation line with no setting above it");
                out.put(key, out.get(key) + " " + line.strip());
                continue;
            }
            int colon = line.indexOf(": ");
            if (colon <= 0) throw new IllegalStateException(DESCRIPTIONS_NAME + ":" + lineNumber
                    + ": expected `<setting>: What it does.`");
            key = line.substring(0, colon).strip();
            if (out.containsKey(key)) throw new IllegalStateException(DESCRIPTIONS_NAME + ":" + lineNumber
                    + ": " + key + " is described twice");
            out.put(key, line.substring(colon + 2).strip());
        }
        return out;
    }

    /** The text for a setting: its exact entry, else the matching pattern with the fewest wildcards. */
    static Optional<String> describe(String setting, Map<String, String> descriptions) {
        if (descriptions.containsKey(setting)) return Optional.of(descriptions.get(setting));
        return descriptions.entrySet().stream()
                .filter(e -> e.getKey().contains("*") && matches(e.getKey(), setting))
                .min((a, b) -> Long.compare(wildcards(a.getKey()), wildcards(b.getKey())))
                .map(Map.Entry::getValue);
    }

    static boolean matches(String pattern, String setting) {
        String regex = Stream.of(pattern.split("\\.", -1))
                .map(segment -> segment.equals("*") ? "[^.]+" : Pattern.quote(segment))
                .collect(Collectors.joining("\\."));
        return setting.matches(regex);
    }

    private static long wildcards(String pattern) {
        return pattern.chars().filter(c -> c == '*').count();
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    /** The guide's section, between and including the markers. Undescribed settings say so. */
    static String render(Map<String, Setting> settings, Map<String, String> descriptions) {
        Map<String, List<Setting>> groups = new TreeMap<>((a, b) -> {
            int rank = Integer.compare(groupRank(a), groupRank(b));
            return rank != 0 ? rank : a.compareTo(b);
        });
        for (Setting s : settings.values()) {
            groups.computeIfAbsent(group(s.name), g -> new ArrayList<>()).add(s);
        }
        StringBuilder out = new StringBuilder();
        out.append(BEGIN).append("\n\n");
        out.append("Generated from the code by `").append(REGENERATE).append("`: every `@ConfigurationProperties` ")
                .append("field, every `${…}` placeholder in `application.yml` and `application-prod.yml`, and every ")
                .append("`@Value` key, ").append(settings.size()).append(" settings in all. ")
                .append("The default is what the `prod` profile gives. **required** means the API does not start ")
                .append("without the variable (an empty value counts as set). `*` stands for any map key, `[*]` for ")
                .append("any list item: those are set in `application.yml`, not the environment.\n");
        for (var group : groups.entrySet()) {
            out.append("\n#### ").append(group.getKey()).append("\n\n");
            out.append("| Setting | Environment variable | Default | What it does |\n");
            out.append("|---|---|---|---|\n");
            for (Setting s : group.getValue()) {
                out.append("| `").append(cell(s.name)).append("` | ")
                        .append(envCell(s)).append(" | ")
                        .append(defaultCell(s)).append(" | ")
                        .append(cell(describe(s.name, descriptions).orElse("(no description)")))
                        .append(" |\n");
            }
        }
        out.append("\n").append(END);
        return out.toString();
    }

    private static String group(String name) {
        if (name.startsWith(GODANCE)) {
            String[] parts = name.split("\\.");
            return "godance." + parts[1].replace("[*]", "");
        }
        if (name.startsWith("spring.") || name.startsWith("server.") || name.startsWith("management.")
                || name.startsWith("logging.")) {
            return "Spring Boot";
        }
        return "The container";
    }

    private static int groupRank(String group) {
        if (group.startsWith(GODANCE)) return 0;
        if (group.equals("Spring Boot")) return 1;
        return 2;
    }

    private static String envCell(Setting s) {
        String env = s.env();
        if (env == null) return "—";
        String cell = "`" + env + "`";
        String ignored = s.ignoredEnv();
        if (ignored != null) cell += " (`" + ignored + "` works only without the prod profile)";
        return cell;
    }

    private static String defaultCell(Setting s) {
        if (s.required()) return "**required**";
        String text = s.defaultText();
        if (text == null) return "none";
        if (text.isEmpty()) return "empty";
        if (text.startsWith("defined in ")) return cell(text);
        return "`" + cell(text) + "`";
    }

    private static String cell(String text) {
        return text.replace("|", "\\|").replace("\n", " ");
    }

    /** A value as it is written in YAML or an environment variable. */
    static String format(Object value) {
        if (value == null) return null;
        if (value instanceof Duration d) return duration(d);
        if (value instanceof DataSize size) return dataSize(size);
        if (value instanceof Collection<?> c) {
            return c.isEmpty() ? "" : c.stream().map(ConfigurationReference::format).map(String::valueOf)
                    .sorted().collect(Collectors.joining(","));
        }
        if (value instanceof Map<?, ?> m) return m.isEmpty() ? "" : "a map of " + m.size();
        if (value instanceof Enum<?> e) return e.name();
        if (value.getClass().isRecord()) {
            return valuesOf(value).entrySet().stream().map(e -> kebab(e.getKey()) + "=" + format(e.getValue()))
                    .collect(Collectors.joining(", "));
        }
        return String.valueOf(value);
    }

    private static String duration(Duration d) {
        long ms = d.toMillis();
        if (ms % 86_400_000 == 0 && ms > 0) return (ms / 86_400_000) + "d";
        if (ms % 3_600_000 == 0 && ms > 0) return (ms / 3_600_000) + "h";
        if (ms % 60_000 == 0 && ms > 0) return (ms / 60_000) + "m";
        if (ms % 1_000 == 0) return (ms / 1_000) + "s";
        return ms + "ms";
    }

    private static String dataSize(DataSize size) {
        long bytes = size.toBytes();
        if (bytes > 0 && bytes % (1L << 30) == 0) return (bytes >> 30) + "GB";
        if (bytes > 0 && bytes % (1L << 20) == 0) return (bytes >> 20) + "MB";
        if (bytes > 0 && bytes % (1L << 10) == 0) return (bytes >> 10) + "KB";
        return bytes + "B";
    }

    // ------------------------------------------------------------------
    // The guide
    // ------------------------------------------------------------------

    /** The guide's current section, between and including the markers. */
    static String currentSection() throws IOException {
        String guide = Files.readString(GUIDE);
        int begin = guide.indexOf(BEGIN);
        int end = guide.indexOf(END);
        if (begin < 0 || end < begin) {
            throw new IllegalStateException(GUIDE_NAME + " has lost its generated-section markers; restore\n  "
                    + BEGIN + "\n  " + END);
        }
        return guide.substring(begin, end + END.length());
    }

    /** Replaces the guide's section with {@code section}. */
    static void writeSection(String section) throws IOException {
        String guide = Files.readString(GUIDE);
        String updated = guide.replace(currentSection(), section);
        if (!updated.equals(guide)) Files.writeString(GUIDE, updated);
    }

    static boolean writeRequested() {
        return Boolean.getBoolean("config-reference.write")
                || "1".equals(System.getenv("CONFIG_REFERENCE_WRITE"));
    }

    /** Names of settings that no description matches. */
    static List<String> undescribed(Map<String, Setting> settings, Map<String, String> descriptions) {
        return settings.keySet().stream().filter(n -> describe(n, descriptions).isEmpty()).toList();
    }

    /** Description keys that match no setting. */
    static List<String> stale(Map<String, Setting> settings, Map<String, String> descriptions) {
        return descriptions.keySet().stream()
                .filter(key -> !settings.containsKey(key)
                        && settings.keySet().stream().noneMatch(n -> key.contains("*") && matches(key, n)))
                .toList();
    }

    /** One line per setting: name, variable, default, where it is read. For the failure messages. */
    static String summary(Setting s) {
        return Objects.toString(s.name) + "   (env " + Objects.toString(s.env(), "none")
                + ", default " + (s.required() ? "required" : Objects.toString(s.defaultText(), "none"))
                + ", read by " + String.join(", ", s.readBy) + ")";
    }
}
