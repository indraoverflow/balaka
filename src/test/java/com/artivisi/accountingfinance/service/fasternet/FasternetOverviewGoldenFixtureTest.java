package com.artivisi.accountingfinance.service.fasternet;

import com.artivisi.accountingfinance.TestcontainersConfiguration;
import com.artivisi.accountingfinance.dto.fasternet.FasternetOverviewResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the committed golden fixture honest: it must be the live output of the read layer
 * for the seeded ledger, not a hand-written sample.
 *
 * <p>Regenerate after an intentional contract change with
 * {@code -Dfasternet.fixture.write=true}. {@code generatedAt} is the only ignored field
 * because it is a server clock value.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@DisplayName("FASTERNET overview golden fixture")
class FasternetOverviewGoldenFixtureTest {

    private static final LocalDate AS_OF = LocalDate.of(2024, 6, 30);
    private static final Path FIXTURE =
            Path.of("docs/integration/fixtures/fasternet-overview-2024-06-30.json");

    @Autowired
    private FasternetDashboardReadService readService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("matches the live contract output for the seeded ledger")
    void fixtureMatchesLiveOutput() throws IOException {
        FasternetOverviewResponse overview = readService.buildOverview(AS_OF);
        ObjectNode actual = objectMapper.valueToTree(overview);

        if (Boolean.getBoolean("fasternet.fixture.write")) {
            Files.writeString(FIXTURE,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(actual) + "\n");
        }

        JsonNode expected = objectMapper.readTree(FIXTURE.toFile());
        assertThat(expected.get("contractVersion").asText()).isEqualTo(FasternetDashboardReadService.CONTRACT_VERSION);
        assertThat(expected.get("asOf").asText()).isEqualTo(AS_OF.toString());
        assertThat(expected.get("series").get("status").asText()).isEqualTo(actual.get("series").get("status").asText());

        // generatedAt is the only non-deterministic field.
        actual.put("generatedAt", expected.get("generatedAt").asText());

        assertThat(actual).isEqualTo(expected);
    }
}
