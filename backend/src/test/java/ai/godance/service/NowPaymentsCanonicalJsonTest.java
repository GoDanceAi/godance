package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.core.JacksonException;

class NowPaymentsCanonicalJsonTest {

    /**
     * Input → what NOWPayments' reference code prints for it,
     * {@code JSON.stringify(sortObjectDeep(JSON.parse(input)))} with
     * {@code sortObjectDeep} from their official Node SDK, run on Node 22.
     * The expected strings are pasted from Node, not computed in Java.
     */
    static Stream<Arguments> jsReferenceOutput() {
        return Stream.of(
                // Small amounts (BTC / ETH) — Jackson prints these as 1.2345E-4 etc.
                Arguments.of("0.00012345", "0.00012345"),
                Arguments.of("0.00000123", "0.00000123"),
                Arguments.of("0.000001", "0.000001"),
                Arguments.of("6.2e-7", "6.2e-7"),
                Arguments.of("1e-7", "1e-7"),
                Arguments.of("1.5E-10", "1.5e-10"),
                Arguments.of("-0.00012345", "-0.00012345"),
                // Large amounts — Jackson switches to 1.234567812345679E7 at 10^7.
                Arguments.of("12345678.123456789", "12345678.12345679"),
                Arguments.of("1e7", "10000000"),
                Arguments.of("123456789012345678901", "123456789012345680000"),
                Arguments.of("1e21", "1e+21"),
                Arguments.of("1.5e300", "1.5e+300"),
                // Integers and trailing zeros — every number is a double in JS.
                Arguments.of("100.0", "100"),
                Arguments.of("25.50", "25.5"),
                Arguments.of("1E2", "100"),
                Arguments.of("24.9", "24.9"),
                Arguments.of("-0", "0"),
                Arguments.of("-0.0", "0"),
                Arguments.of("9007199254740993", "9007199254740992"),
                Arguments.of("12345678901234567890", "12345678901234567000"),
                Arguments.of("5e-324", "5e-324"),
                Arguments.of("1.7976931348623157e308", "1.7976931348623157e+308"),
                Arguments.of("1e400", "null"),
                // Strings — non-ASCII literal, escapes decoded, only control chars and lone surrogates re-escaped.
                Arguments.of("\"Zürich – 東京 🎉\"", "\"Zürich – 東京 🎉\""),
                Arguments.of("\"caf\\u00e9 \\ud83c\\udf89\"", "\"café 🎉\""),
                Arguments.of("\"a\\/b\"", "\"a/b\""),
                Arguments.of("\"\\u0001\\u001F\\u007f\"", "\"\\u0001\\u001f\u007f\""),
                Arguments.of("\"\\b\\f\\n\\r\\t \\\" \\\\\"", "\"\\b\\f\\n\\r\\t \\\" \\\\\""),
                Arguments.of("\"\\ud800 \\uDC00\"", "\"\\ud800 \\udc00\""),
                Arguments.of("\"\\u2028\\u2029\"", "\"\u2028\u2029\""),
                // Objects — keys sorted recursively, array order kept, whitespace dropped.
                Arguments.of("{\"b\":2,\"a\":{\"d\":4,\"c\":3},\"_\":null,\"B\":[{\"z\":true,\"y\":false}]}",
                        "{\"B\":[{\"y\":false,\"z\":true}],\"_\":null,\"a\":{\"c\":3,\"d\":4},\"b\":2}"),
                Arguments.of(" {\n  \"a\" : [ 1 , 2.0 ] }\n", "{\"a\":[1,2]}"),
                // JS enumerates array-index keys first, numerically, regardless of sort().
                Arguments.of("{\"b\":1,\"10\":2,\"2\":3,\"a\":4,\"007\":5,\"-1\":6}",
                        "{\"2\":3,\"10\":2,\"-1\":6,\"007\":5,\"a\":4,\"b\":1}"),
                Arguments.of("{\"a\":1,\"a\":2}", "{\"a\":2}"));
    }

    @ParameterizedTest
    @MethodSource("jsReferenceOutput")
    void canonicalise_givenJson_matchesNowPaymentsJsReference(String input, String expected) throws Exception {
        assertThat(NowPaymentsCanonicalJson.canonicalise(input)).isEqualTo(expected);
    }

    @Test
    void canonicalise_givenTrailingGarbage_throws() {
        assertThatThrownBy(() -> NowPaymentsCanonicalJson.canonicalise("{\"a\":1} {"))
                .isInstanceOf(JacksonException.class);
    }
}
