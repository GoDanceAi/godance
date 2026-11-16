package ai.godance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import ai.godance.config.ConfigurationReference.Setting;

/**
 * Keeps the configuration reference in {@code docs/self-hosting.md} true to
 * the code. A new setting (a {@code @ConfigurationProperties} field, a
 * {@code ${...}} in {@code application*.yml}, a {@code @Value} key) fails
 * the build until it is described and the section regenerated:
 *
 * <pre>
 *   1. describe it in backend/src/test/resources/config-reference/descriptions.txt
 *   2. backend/scripts/config-reference.sh
 *   3. commit both files
 * </pre>
 *
 * The script runs this class with {@code CONFIG_REFERENCE_WRITE=1}, which
 * rewrites the section instead of comparing it. No Spring context, no Docker.
 */
class ConfigurationReferenceTest {

    private static final String HOW_TO_FIX = """

            To fix it, from the repository root:
              1. Describe each setting listed above in %s
                 (one line each: `<setting>: What it does.`; in a name, `*` stands for one segment)
              2. Run %s   (rewrites the reference in %s; about a minute)
              3. Commit both files.
            Two branches that each add settings: whichever merges second does steps 1-3 after merging.
            """.formatted(ConfigurationReference.DESCRIPTIONS_NAME, ConfigurationReference.REGENERATE,
                    ConfigurationReference.GUIDE_NAME);

    @Test
    void collect_givenTheCode_expectsEverySettingDescribed() throws IOException {
        Map<String, Setting> settings = ConfigurationReference.collect();
        List<String> missing = ConfigurationReference.undescribed(settings, ConfigurationReference.descriptions());
        if (!missing.isEmpty()) {
            fail("The configuration reference has no description for " + missing.size() + " setting(s):\n"
                    + missing.stream().map(n -> "  " + ConfigurationReference.summary(settings.get(n)))
                            .collect(Collectors.joining("\n"))
                    + "\n" + HOW_TO_FIX);
        }
    }

    @Test
    void descriptions_givenTheCode_expectsEachToNameASetting() throws IOException {
        List<String> stale = ConfigurationReference.stale(
                ConfigurationReference.collect(), ConfigurationReference.descriptions());
        if (!stale.isEmpty()) {
            fail("These entries in " + ConfigurationReference.DESCRIPTIONS_NAME
                    + " name no setting the code has (removed or renamed?):\n"
                    + stale.stream().map(n -> "  " + n).collect(Collectors.joining("\n"))
                    + "\n\nDelete or rename them, then run " + ConfigurationReference.REGENERATE
                    + " and commit both files.");
        }
    }

    @Test
    void guide_givenTheCode_expectsGeneratedReferenceCurrent() throws IOException {
        Map<String, Setting> settings = ConfigurationReference.collect();
        Map<String, String> descriptions = ConfigurationReference.descriptions();
        String generated = ConfigurationReference.render(settings, descriptions);

        if (ConfigurationReference.writeRequested()) {
            List<String> missing = ConfigurationReference.undescribed(settings, descriptions);
            if (!missing.isEmpty()) {
                fail("Not written: describe these settings first:\n  " + String.join("\n  ", missing)
                        + "\n" + HOW_TO_FIX);
            }
            ConfigurationReference.writeSection(generated);
        }

        String current = ConfigurationReference.currentSection();
        if (!current.equals(generated)) {
            fail("The configuration reference in " + ConfigurationReference.GUIDE_NAME
                    + " is out of date with the code.\n" + firstDifference(current, generated)
                    + "\nRun " + ConfigurationReference.REGENERATE + " and commit "
                    + ConfigurationReference.GUIDE_NAME + ". If it also lists undescribed settings,"
                    + HOW_TO_FIX);
        }
        assertThat(current).contains("| `godance.region` |");
    }

    @Test
    void matches_givenWildcardSegment_expectsOneSegmentOnly() {
        assertThat(ConfigurationReference.matches("godance.providers.*.api-key", "godance.providers.openai.api-key"))
                .isTrue();
        assertThat(ConfigurationReference.matches("godance.providers.*.api-key", "godance.providers.api-key"))
                .isFalse();
        assertThat(ConfigurationReference.matches("godance.providers.*.api-key", "godance.providers.a.b.api-key"))
                .isFalse();
        assertThat(ConfigurationReference.kebab("maxUnfinishedPerAccount")).isEqualTo("max-unfinished-per-account");
    }

    private static String firstDifference(String current, String generated) {
        String[] a = current.split("\n", -1);
        String[] b = generated.split("\n", -1);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            String left = i < a.length ? a[i] : "(end of section)";
            String right = i < b.length ? b[i] : "(end of section)";
            if (!left.equals(right)) {
                return "First difference, line " + (i + 1) + " of the section:\n  in the guide: " + left
                        + "\n  generated:    " + right;
            }
        }
        return "";
    }
}
