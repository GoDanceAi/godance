package ai.godance.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The string NOWPayments HMACs for {@code x-nowpayments-sig}: exactly what
 * its reference code yields, {@code JSON.stringify(sortObjectDeep(JSON.parse(body)))}
 * — the Node.js snippet in the NOWPayments API docs and
 * {@code createWebhookSignature} in the official SDK
 * ({@code NowPaymentsIO/nowpayments-sdk-nodejs}, {@code src/ipn.js}).
 *
 * <p>Jackson can't produce this by re-serialising: it prints numbers
 * Java-style, so a BTC amount {@code 0.00012345} comes back as
 * {@code 1.2345E-4}, {@code 12345678.5} as {@code 1.23456785E7} and
 * {@code 100.0} stays {@code 100.0}, and every such IPN fails the HMAC.
 * JavaScript has a single number type (an IEEE-754 double) printed per
 * ECMAScript {@code Number::toString}; this class reproduces that, along
 * with {@code JSON.stringify}'s string escaping and JS object key order.
 * Arrays keep their order, as in the official SDK.
 */
public final class NowPaymentsCanonicalJson {

    // Jackson 2 defaults: this parser must keep accepting exactly what it did under Jackson 2.
    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NowPaymentsCanonicalJson() { }

    public static String canonicalise(String payload) throws JacksonException {
        StringBuilder out = new StringBuilder(payload.length());
        write(MAPPER.readTree(payload), out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        switch (node.getNodeType()) {
            case OBJECT -> {
                List<String> keys = new ArrayList<>(node.propertyNames());
                keys.sort(NowPaymentsCanonicalJson::compareKeys);
                out.append('{');
                for (int i = 0; i < keys.size(); i++) {
                    if (i > 0) out.append(',');
                    writeString(keys.get(i), out);
                    out.append(':');
                    write(node.get(keys.get(i)), out);
                }
                out.append('}');
            }
            case ARRAY -> {
                out.append('[');
                for (int i = 0; i < node.size(); i++) {
                    if (i > 0) out.append(',');
                    write(node.get(i), out);
                }
                out.append(']');
            }
            case STRING -> writeString(node.stringValue(), out);
            // JSON.parse turns every number literal into a double.
            case NUMBER -> out.append(numberToString(node.doubleValue()));
            case BOOLEAN -> out.append(node.booleanValue());
            case NULL -> out.append("null");
            default -> throw new IllegalArgumentException("unexpected JSON node: " + node.getNodeType());
        }
    }

    /**
     * Key order of the object {@code sortObjectDeep} builds: {@code sort()}
     * orders keys by UTF-16 code unit, like {@link String#compareTo}, but
     * JS enumerates array-index keys ({@code "0"}, {@code "17"}) first, in
     * numeric order, whatever order they were inserted in.
     */
    private static int compareKeys(String a, String b) {
        long ia = arrayIndex(a);
        long ib = arrayIndex(b);
        if (ia >= 0 && ib >= 0) return Long.compare(ia, ib);
        if (ia >= 0) return -1;
        if (ib >= 0) return 1;
        return a.compareTo(b);
    }

    /** {@code key}'s value if it's a JS array index (canonical integer in [0, 2^32 - 2]), else -1. */
    private static long arrayIndex(String key) {
        int len = key.length();
        if (len == 0 || len > 10 || (len > 1 && key.charAt(0) == '0')) return -1;
        long value = 0;
        for (int i = 0; i < len; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') return -1;
            value = value * 10 + (c - '0');
        }
        return value <= 0xFFFF_FFFEL ? value : -1;
    }

    /** ECMAScript {@code Number::toString(x)}, which {@code JSON.stringify} applies to finite numbers. */
    static String numberToString(double x) {
        if (!Double.isFinite(x)) return "null";      // JSON.parse("1e400") is Infinity; stringify prints null
        if (x == 0) return "0";                      // -0 too
        if (x < 0) return "-" + numberToString(-x);

        // The fewest digits s (k of them) that round-trip to x — the one
        // closest to x if several do — with x = s × 10^(n − k).
        BigDecimal exact = new BigDecimal(x);
        BigDecimal shortest;
        int precision = 0;
        do {
            shortest = exact.round(new MathContext(++precision, RoundingMode.HALF_EVEN));
        } while (shortest.doubleValue() != x);
        shortest = shortest.stripTrailingZeros();
        String s = shortest.unscaledValue().toString();
        int k = s.length();
        int n = k - shortest.scale();

        if (k <= n && n <= 21) return s + "0".repeat(n - k);
        if (0 < n && n <= 21) return s.substring(0, n) + '.' + s.substring(n);
        if (-6 < n && n <= 0) return "0." + "0".repeat(-n) + s;
        String exponent = (n - 1 < 0 ? "e-" : "e+") + Math.abs(n - 1);
        return k == 1 ? s + exponent : s.charAt(0) + "." + s.substring(1) + exponent;
    }

    /** {@code JSON.stringify}'s QuoteJSONString: non-ASCII stays literal; lone surrogates are escaped. */
    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    boolean loneSurrogate = Character.isHighSurrogate(c)
                            ? i + 1 == s.length() || !Character.isLowSurrogate(s.charAt(i + 1))
                            : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || loneSurrogate) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
