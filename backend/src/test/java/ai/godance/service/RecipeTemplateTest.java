package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import ai.godance.service.RecipeTemplate.Fault;
import ai.godance.service.RecipeTemplate.Part;
import ai.godance.service.RecipeTemplate.TemplateException;
import ai.godance.service.RecipeTemplate.Variable;

/**
 * The template rules, case by case. This is the table the marketing site's
 * renderer is tested against too
 * ({@code marketing/scripts/gallery-schema/template.test.js}): the two
 * implement the same rules, so a case added to one belongs in the other.
 */
class RecipeTemplateTest {

    private static Variable v(String name, String fallback) {
        return new Variable(name, fallback);
    }

    // ===== Rule 2: a variable name ====================================

    @ParameterizedTest
    @ValueSource(strings = {"a", "x1", "lantern color", "boat-colour", "sign_text", "A B-c_9", "9 lives",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})   // the last is 40 letters
    void isVariableName_givenANameTheRuleAllows_expectsTrue(String name) {
        assertThat(RecipeTemplate.isVariableName(name)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " a", "a ", "-a", "a_", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "colour!", "颜色",
            "café", "a.b", "a{b", "a\tb"})
    void isVariableName_givenANameTheRuleRefuses_expectsFalse(String name) {
        assertThat(RecipeTemplate.isVariableName(name)).isFalse();
    }

    @Test
    void isVariableName_givenNull_expectsFalse() {
        assertThat(RecipeTemplate.isVariableName(null)).isFalse();
    }

    // ===== Rules 3 to 6: rendering ====================================

    /** [what, template, variables, values, the result]: the cases that render. */
    static Stream<Arguments> rendered() {
        return Stream.of(
                // Rule 3: the value given, or the default. An empty string is a value.
                Arguments.of("a default fills a variable with no value", "A {{color}} mug.", List.of(v("color", "red")), Map.of(), "A red mug."),
                Arguments.of("a value replaces the default", "A {{color}} mug.", List.of(v("color", "red")), Map.of("color", "blue"), "A blue mug."),
                Arguments.of("an empty value is a value", "A {{color}} mug.", List.of(v("color", "red")), Map.of("color", ""), "A  mug."),
                Arguments.of("an empty default is a default", "A {{color}}mug.", List.of(v("color", "")), Map.of(), "A mug."),
                Arguments.of("a variable used twice is filled twice", "{{a}} and {{a}}", List.of(v("a", "x")), Map.of(), "x and x"),
                Arguments.of("two variables side by side", "{{a}}{{b}}", List.of(v("a", "1"), v("b", "2")), Map.of("b", "3"), "13"),
                Arguments.of("a name with spaces, a hyphen and an underscore", "{{lantern color}} {{boat-colour}} {{sign_text}}",
                        List.of(v("lantern color", "red"), v("boat-colour", "blue"), v("sign_text", "OPEN")), Map.of(), "red blue OPEN"),
                Arguments.of("a value for a variable that is not declared is ignored", "A {{color}} mug.", List.of(v("color", "red")), Map.of("size", "big"), "A red mug."),

                // Rule 4: one pass. What is inserted is not read again.
                Arguments.of("a value containing a marker is inserted as it is", "A {{color}} mug.", List.of(v("color", "red")), Map.of("color", "{{color}}"), "A {{color}} mug."),
                Arguments.of("a value containing another variable is not expanded", "{{a}} {{b}}", List.of(v("a", "1"), v("b", "2")), Map.of("a", "{{b}}"), "{{b}} 2"),
                Arguments.of("a default containing a marker is inserted as it is", "{{a}}", List.of(v("a", "{{a}} {{zzz}}")), Map.of(), "{{a}} {{zzz}}"),
                Arguments.of("a value containing a stray \"{{\" is inserted as it is", "{{a}}!", List.of(v("a", "x")), Map.of("a", "{{"), "{{!"),

                // Rule 5: a stray "}}" is allowed.
                Arguments.of("a stray \"}}\" is text", "x }} {{a}} }}", List.of(v("a", "1")), Map.of(), "x }} 1 }}"),
                Arguments.of("a JSON-shaped template that ends in \"}}\"", "{\"subject\":{\"colour\":\"{{colour}}\"}}",
                        List.of(v("colour", "teal")), Map.of(), "{\"subject\":{\"colour\":\"teal\"}}"),
                Arguments.of("single braces are text", "{ {{a}} } {a}", List.of(v("a", "1")), Map.of(), "{ 1 } {a}"),
                Arguments.of("a marker right after a closing pair", "}}{{a}}", List.of(v("a", "1")), Map.of(), "}}1"),

                // Rule 6, at its edge: nothing declared and nothing used.
                Arguments.of("a template with no markers and no variables", "Just text, with {single} braces.", List.<Variable>of(), Map.of(),
                        "Just text, with {single} braces."));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rendered")
    void render_givenAValidTemplate_expectsTheResult(String what, String template, List<Variable> variables,
                                                    Map<String, String> values, String result) {
        assertThat(RecipeTemplate.check(template, variables.stream().map(Variable::name).toList())).isEmpty();
        assertThat(RecipeTemplate.render(template, variables, values)).isEqualTo(result);
    }

    /** [what, template, variables, the faults in order]: the cases that are refused. */
    static Stream<Arguments> refused() {
        return Stream.of(
                // Rule 5: every "{{" begins a declared variable.
                Arguments.of("a stray \"{{\"", "x {{ y {{a}}", List.of("a"), List.of(Fault.STRAY_OPEN)),
                Arguments.of("a \"{{\" at the very end", "{{a}} {{", List.of("a"), List.of(Fault.STRAY_OPEN)),
                Arguments.of("spaces inside the braces", "{{ a }}", List.of("a"), List.of(Fault.STRAY_OPEN, Fault.UNUSED)),
                Arguments.of("a marker that is never closed", "{{a} {{a}}", List.of("a"), List.of(Fault.STRAY_OPEN)),
                Arguments.of("an empty marker", "{{}} {{a}}", List.of("a"), List.of(Fault.STRAY_OPEN)),
                Arguments.of("a name that breaks the naming rule", "{{a!}} {{a}}", List.of("a"), List.of(Fault.STRAY_OPEN)),
                Arguments.of("three braces: the first two begin nothing", "{{{a}}}", List.of("a"), List.of(Fault.STRAY_OPEN)),
                Arguments.of("a variable that is not declared", "{{a}} {{b}}", List.of("a"), List.of(Fault.UNDECLARED)),
                Arguments.of("an undeclared variable is reported once", "{{b}} {{b}} {{a}}", List.of("a"), List.of(Fault.UNDECLARED)),
                Arguments.of("names are compared exactly", "{{A}}", List.of("a"), List.of(Fault.UNDECLARED, Fault.UNUSED)),

                // Rule 6: every declared variable appears at least once.
                Arguments.of("a declared variable the template never uses", "{{a}}", List.of("a", "b"), List.of(Fault.UNUSED)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refused")
    void check_givenATemplateThatBreaksARule_expectsItsFaults(String what, String template, List<String> declared,
                                                             List<Fault> faults) {
        assertThat(RecipeTemplate.check(template, declared)).extracting(RecipeTemplate.Problem::fault).isEqualTo(faults);
        List<Variable> variables = declared.stream().map(name -> v(name, "1")).toList();
        assertThatThrownBy(() -> RecipeTemplate.render(template, variables, Map.of())).isInstanceOf(TemplateException.class);
    }

    @Test
    void check_givenProblems_expectsEachToSayWhatIsWrongAndWhere() {
        assertThat(RecipeTemplate.check("ab {{ c", List.of()).get(0).message())
                .contains("the \"{{\" at position 3 does not begin a variable");
        assertThat(RecipeTemplate.check("{{b}}", List.of()).get(0).message()).isEqualTo("{{b}} is not a declared variable");
        assertThat(RecipeTemplate.check("x", List.of("b")).get(0).message())
                .isEqualTo("the variable \"b\" is declared but the template never uses it");
    }

    @Test
    void markersOf_givenATemplate_expectsItsNamesInOrder() {
        assertThat(RecipeTemplate.markersOf("{{b}} x {{a}} }} {{b}}")).containsExactly("b", "a", "b");
        assertThat(RecipeTemplate.markersOf("no markers {here}")).isEmpty();
        assertThat(RecipeTemplate.parse("a{{b}}c").parts())
                .containsExactly(Part.text("a"), Part.marker("b"), Part.text("c"));
    }

    @Test
    void render_givenNullValues_expectsEveryDefault() {
        assertThat(RecipeTemplate.render("{{a}}-{{b}}", List.of(v("a", "1"), v("b", "2")), null)).isEqualTo("1-2");
    }
}
