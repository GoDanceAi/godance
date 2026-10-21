package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import ai.godance.config.BillingProperties;
import ai.godance.config.NowPaymentsProperties;
import ai.godance.service.NowPaymentsService.InvalidSignatureException;
import ai.godance.service.NowPaymentsService.NowPaymentsIpn;

/**
 * IPN signature verification against signatures computed outside Java.
 * Each {@code *_SIG} below was produced in Node 22 by both of NOWPayments'
 * reference implementations, which agree: the Node.js snippet in their API
 * docs ("Instant Payments Notifications") and {@code createWebhookSignature}
 * from their official SDK ({@code NowPaymentsIO/nowpayments-sdk-nodejs},
 * {@code src/ipn.js}), keyed with {@link #IPN_SECRET}. Don't regenerate them
 * with Java; that would make the test circular again.
 */
class NowPaymentsServiceTest {

    private static final String IPN_SECRET = "np_unit_test_ipn_secret";

    /** BTC IPN as a JS sender serialises it — sub-0.001 amounts, plus a fee below 1e-6. */
    private static final String BTC_IPN = """
            {"payment_id":5077125051,"parent_payment_id":null,"invoice_id":4901234567,\
            "payment_status":"finished","pay_address":"bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",\
            "payin_extra_id":null,"price_amount":10,"price_currency":"usd",\
            "pay_amount":0.00012345,"actually_paid":0.00012345,"actually_paid_at_fiat":0,\
            "pay_currency":"btc","order_id":"0b6f6f5e-2d3c-4a8e-9a51-6f1c2b3d4e5f",\
            "order_description":"Godance credit top-up","purchase_id":"5839201745",\
            "outcome_amount":0.00011987,"outcome_currency":"btc","payment_extra_ids":null,\
            "fee":{"currency":"btc","depositFee":0.00000123,"withdrawalFee":0,"serviceFee":6.2e-7}}""";
    private static final String BTC_IPN_SIG =
            "f50fd65eed7fb61caa22358b243c977fb463438b85b511854822f4ab78dda61a"
                    + "d843ebaaf30e16a43b48a7f9c0fd4803cfbfd365bc44229b217bb61f61edfd81";

    /** Low-unit-price coin — amounts at or above 10^7. */
    private static final String SHIB_IPN = """
            {"payment_id":5077125052,"parent_payment_id":null,"invoice_id":4901234568,\
            "payment_status":"finished","pay_address":"0x52908400098527886E0F7030069857D2E4169EE7",\
            "payin_extra_id":null,"price_amount":25,"price_currency":"usd",\
            "pay_amount":11904761.904761905,"actually_paid":11904761.904761905,"actually_paid_at_fiat":0,\
            "pay_currency":"shib","order_id":"0b6f6f5e-2d3c-4a8e-9a51-6f1c2b3d4e5f",\
            "order_description":"Godance credit top-up","purchase_id":"5839201746",\
            "outcome_amount":11785714.285714285,"outcome_currency":"shib","payment_extra_ids":null,\
            "fee":{"currency":"shib","depositFee":119047.61904761905,"withdrawalFee":0,"serviceFee":0}}""";
    private static final String SHIB_IPN_SIG =
            "75a43c9fe739521f7b7e385eba689439491b27f4912e3ff38d8dc2bca45bae67"
                    + "a163cd334f0b6242442587a50201cbaee173a2df4c969f250720744ec54b47a3";

    private final NowPaymentsService service = new NowPaymentsService(
            new NowPaymentsProperties("np_unit_test_api_key", IPN_SECRET, "http://localhost:1",
                    "http://localhost/success", "http://localhost/cancel"),
            new BillingProperties(100L, 100_000L, "usd", 5.0, 10.0, 12.0),
            JsonMapper.builderWithJackson2Defaults().build());

    @Test
    void verifyAndParse_givenBtcIpnWithSubMilliAmounts_acceptsNowPaymentsSignature() {
        NowPaymentsIpn ipn = service.verifyAndParse(BTC_IPN, BTC_IPN_SIG);

        assertThat(ipn.paymentId()).isEqualTo("5077125051");
        assertThat(ipn.isFinished()).isTrue();
        assertThat(ipn.orderId()).isEqualTo("0b6f6f5e-2d3c-4a8e-9a51-6f1c2b3d4e5f");
        assertThat(ipn.fiatAmountCents("usd")).isEqualTo(10_00L);
    }

    @Test
    void verifyAndParse_givenIpnWithAmountsAbove1e7_acceptsNowPaymentsSignature() {
        NowPaymentsIpn ipn = service.verifyAndParse(SHIB_IPN, SHIB_IPN_SIG);

        assertThat(ipn.paymentId()).isEqualTo("5077125052");
        assertThat(ipn.fiatAmountCents("usd")).isEqualTo(25_00L);
    }

    @Test
    void verifyAndParse_givenSameIpnReorderedAndPrettyPrinted_acceptsSameSignature() {
        String reformatted = """
                {
                  "fee": { "serviceFee": 6.2e-7, "withdrawalFee": 0, "depositFee": 0.00000123, "currency": "btc" },
                  "payment_status": "finished",
                  "payment_id": 5077125051,
                  "pay_amount": 0.00012345,
                  "actually_paid": 0.00012345,
                  "outcome_amount": 0.00011987,
                  "price_amount": 10,
                  "price_currency": "usd",
                  "pay_currency": "btc",
                  "outcome_currency": "btc",
                  "order_id": "0b6f6f5e-2d3c-4a8e-9a51-6f1c2b3d4e5f",
                  "order_description": "Godance credit top-up",
                  "purchase_id": "5839201745",
                  "invoice_id": 4901234567,
                  "parent_payment_id": null,
                  "payin_extra_id": null,
                  "payment_extra_ids": null,
                  "actually_paid_at_fiat": 0,
                  "pay_address": "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh"
                }
                """;

        assertThat(service.verifyAndParse(reformatted, BTC_IPN_SIG).paymentId()).isEqualTo("5077125051");
    }

    @Test
    void verifyAndParse_givenPaymentIdRespeltAsDecimal_yieldsSameIdempotencyKey() {
        // Same JS value, so NOWPayments' signature still verifies; a replay
        // spelt this way must not get a fresh ledger idempotency key.
        String respelt = BTC_IPN.replace("\"payment_id\":5077125051", "\"payment_id\":5077125051.0");

        assertThat(service.verifyAndParse(respelt, BTC_IPN_SIG).paymentId()).isEqualTo("5077125051");
    }

    @Test
    void verifyAndParse_givenTamperedPayAmount_throwsInvalidSignature() {
        String tampered = BTC_IPN.replace("\"pay_amount\":0.00012345", "\"pay_amount\":0.00012346");

        assertThatThrownBy(() -> service.verifyAndParse(tampered, BTC_IPN_SIG))
                .isInstanceOf(InvalidSignatureException.class)
                .hasMessage("signature mismatch");
    }
}
