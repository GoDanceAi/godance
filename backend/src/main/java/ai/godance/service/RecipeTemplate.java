package ai.godance.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recipe templates: the one place their rules are written down for the
 * backend. The marketing site implements the same rules
 * ({@code marketing/src/lib/gallery-template.js}) and the two are tested
 * against the same table of cases, so each rule below is stated the way the
 * round-10 coordination notes state it, in the same order.
 *
 * <p>Syntax
 * <ol>
 *   <li>A variable is written {@code {{name}}}.</li>
 *   <li>A name is 1–40 ASCII letters, digits, spaces, hyphens or
 *       underscores, starting and ending with a letter or digit.</li>
 * </ol>
 * Rendering
 * <ol start="3">
 *   <li>Each {@code {{name}}} is replaced by the value given, or by the
 *       variable's default when no value is given for it. An empty string
 *       is a value.</li>
 *   <li>It is one pass: a value that contains {@code {{…}}} is inserted as
 *       it is.</li>
 *   <li>Every {@code {{} in a template must begin a declared variable. A
 *       stray {@code }}} is allowed, so JSON-shaped prompts work.</li>
 *   <li>Every declared variable appears in the template at least once.</li>
 * </ol>
 */
public final class RecipeTemplate {

    /** The longest a variable name can be. */
    public static final int NAME_MAX_LENGTH = 40;

    private static final String NAME = "[A-Za-z0-9](?:[A-Za-z0-9 _-]{0,38}[A-Za-z0-9])?";

    /** Rule 2, as a pattern for a whole name. */
    private static final Pattern VARIABLE_NAME = Pattern.compile(NAME);

    /** Rules 1 and 2 at one position: a name and its closing braces, right after a "{{". */
    private static final Pattern NAME_THEN_CLOSE = Pattern.compile("(" + NAME + ")\\}\\}");

    private RecipeTemplate() { }

    /** A declared variable and its default. */
    public record Variable(String name, String defaultValue) { }

    /** A piece of a template: literal text, or a marker's variable name. Exactly one is set. */
    public record Part(String text, String name) {
        static Part text(String text) { return new Part(text, null); }
        static Part marker(String name) { return new Part(null, name); }
        public boolean isMarker() { return name != null; }
    }

    /** A template cut at its markers, and the positions of every "{{" that begins none. */
    public record Parsed(List<Part> parts, List<Integer> strays) { }

    /** What is wrong with a template. */
    public enum Fault { STRAY_OPEN, UNDECLARED, UNUSED }

    /** One way a template breaks the rules. {@code name} is set for UNDECLARED and UNUSED. */
    public record Problem(Fault fault, String name, String message) { }

    /** Raised by {@link #render} for a template that breaks rule 5 or 6. */
    public static final class TemplateException extends IllegalArgumentException {
        private final transient List<Problem> problems;

        TemplateException(List<Problem> problems) {
            super(String.join("; ", problems.stream().map(Problem::message).toList()));
            this.problems = List.copyOf(problems);
        }

        public List<Problem> problems() { return problems; }
    }

    /** Rule 2. */
    public static boolean isVariableName(String name) {
        return name != null && VARIABLE_NAME.matcher(name).matches();
    }

    /**
     * A template cut into the text between its markers and the markers
     * themselves, read left to right. A "{{" that does not begin a marker
     * (rule 5) is reported and stays in the text. A "}}" on its own is
     * ordinary text; nothing is escaped and nothing nests.
     */
    public static Parsed parse(String template) {
        List<Part> parts = new ArrayList<>();
        List<Integer> strays = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        Matcher marker = NAME_THEN_CLOSE.matcher(template);
        int at = 0;
        while (at < template.length()) {
            int open = template.indexOf("{{", at);
            if (open == -1) break;
            text.append(template, at, open);
            marker.region(open + 2, template.length());
            if (marker.lookingAt()) {
                if (!text.isEmpty()) parts.add(Part.text(text.toString()));
                text.setLength(0);
                parts.add(Part.marker(marker.group(1)));
                at = marker.end();
            } else {
                // Not a marker. Move past one brace only, so "{{{name}}" reports the
                // first "{{" and still finds the marker that starts at its second brace.
                strays.add(open);
                text.append('{');
                at = open + 1;
            }
        }
        text.append(template, at, template.length());
        if (!text.isEmpty()) parts.add(Part.text(text.toString()));
        return new Parsed(parts, strays);
    }

    /** The names a template's markers use, in order, repeats included. */
    public static List<String> markersOf(String template) {
        return parse(template).parts().stream().filter(Part::isMarker).map(Part::name).toList();
    }

    /**
     * Checks a template against the variables declared for it: rules 5 and
     * 6. Returns every problem; an empty list means it can be rendered.
     */
    public static List<Problem> check(String template, List<String> declared) {
        List<Problem> problems = new ArrayList<>();
        Parsed parsed = parse(template);
        for (int index : parsed.strays()) {
            problems.add(new Problem(Fault.STRAY_OPEN, null,
                    "the \"{{\" at position " + index + " does not begin a variable: write {{name}}, with a name of 1–"
                            + NAME_MAX_LENGTH + " ASCII letters, digits, spaces, hyphens or underscores that starts"
                            + " and ends with a letter or digit"));
        }
        Set<String> used = new HashSet<>();
        for (Part part : parsed.parts()) {
            if (!part.isMarker()) continue;
            if (!declared.contains(part.name()) && !used.contains(part.name())) {
                problems.add(new Problem(Fault.UNDECLARED, part.name(),
                        "{{" + part.name() + "}} is not a declared variable"));
            }
            used.add(part.name());
        }
        for (String name : declared) {
            if (!used.contains(name)) {
                problems.add(new Problem(Fault.UNUSED, name,
                        "the variable \"" + name + "\" is declared but the template never uses it"));
            }
        }
        return problems;
    }

    /**
     * Renders a template: rules 3 and 4.
     *
     * <p>{@code values} holds a value for some, all or none of the
     * variables. A variable with no value gets its default. An empty string
     * is a value and is used. What is inserted is never read again, so a
     * value that contains "{{…}}" stays exactly as it is. A value for a
     * name that is not declared is ignored here; whether that is an error
     * is the caller's to say.
     *
     * @throws TemplateException when the template breaks rule 5 or 6
     */
    public static String render(String template, List<Variable> variables, Map<String, String> values) {
        List<Problem> problems = check(template, variables.stream().map(Variable::name).toList());
        if (!problems.isEmpty()) throw new TemplateException(problems);
        StringBuilder out = new StringBuilder();
        for (Part part : parse(template).parts()) {
            if (!part.isMarker()) {
                out.append(part.text());
            } else if (values != null && values.get(part.name()) != null) {
                out.append(values.get(part.name()));
            } else {
                out.append(variables.stream().filter(v -> v.name().equals(part.name()))
                        .findFirst().orElseThrow().defaultValue());
            }
        }
        return out.toString();
    }
}
