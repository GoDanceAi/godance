package ai.godance.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import ai.godance.security.ApiKeyMinter.Minted;

class ApiKeyMinterTest {

    private final ApiKeyMinter minter = new ApiKeyMinter();

    @Test
    void mint_givenAnyCall_returnsPrefixedPlaintextAndDeterministicHash() {
        Minted m = minter.mint();
        assertThat(m.plaintext()).startsWith("gd_live_");
        assertThat(m.prefix()).hasSize(8);
        assertThat(m.hash()).hasSize(64);                       // SHA-256 hex
        assertThat(minter.hash(m.plaintext())).isEqualTo(m.hash());
    }

    @Test
    void mint_calledManyTimes_returnsUniquePlaintextAndHash() {
        Set<String> plaintexts = new HashSet<>();
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            Minted m = minter.mint();
            plaintexts.add(m.plaintext());
            hashes.add(m.hash());
        }
        assertThat(plaintexts).hasSize(1000);                   // no collisions
        assertThat(hashes).hasSize(1000);
    }
}
