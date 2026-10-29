package ai.godance.service;

import java.util.List;

/**
 * The direct notice to a parent (COPPA 16 CFR 312.4(c)) in plain words.
 * One copy, so the email to the parent ({@code MailService}) and the
 * consent page (via {@code GET /v1/auth/parental-consent}) say the same
 * thing. Change it here, and only with the owner: it is a legal text.
 *
 * @param collected        what Godance collects about the child
 * @param notStored        what it does not keep
 * @param sharing          who else sees the child's data, and why
 * @param noSale           what it never does with the data
 * @param parentRights     what the parent can do, and how
 * @param contactEmail     where to ask
 * @param privacyPolicyUrl the full privacy policy
 */
public record ParentalNotice(
        List<String> collected,
        String notStored,
        String sharing,
        String noSale,
        String parentRights,
        String contactEmail,
        String privacyPolicyUrl
) {
    public static final String CONTACT_EMAIL = "hello@godance.ai";

    public static final ParentalNotice CURRENT = new ParentalNotice(
            List.of(
                    "Their email address and name",
                    "Their birth month and year",
                    "The IP addresses of the devices they use",
                    "Usage and billing records: which AI models they used, when, and what it cost",
                    "The prompts they write for images and videos, kept for 90 days"),
            "Chat text is not stored: the Godance app sends it to get an answer and does not keep it.",
            "To produce answers, images and videos, what your child types is sent to third-party "
                    + "AI model providers, the companies that run the models.",
            "Godance does not sell your child's data and does not use it for advertising.",
            "You can review or delete your child's data, or withdraw your consent, at any time "
                    + "by emailing " + CONTACT_EMAIL + ".",
            CONTACT_EMAIL,
            "https://godance.ai/legal/privacy");
}
