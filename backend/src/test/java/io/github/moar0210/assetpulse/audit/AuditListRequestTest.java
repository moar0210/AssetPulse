package io.github.moar0210.assetpulse.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuditListRequestTest {

    @Test
    void missingAndBoundedValuesProduceExpectedLimits() {
        assertThat(AuditListRequest.fromQuery(null).limit()).isEqualTo(50);
        assertThat(AuditListRequest.fromQuery("1").limit()).isOne();
        assertThat(AuditListRequest.fromQuery("100").limit()).isEqualTo(100);
    }

    @Test
    void invalidValuesUseOneStableException() {
        for (String value : List.of("", "0", "-1", "101", "many", "01", "+1", " 1 ")) {
            assertThatThrownBy(() -> AuditListRequest.fromQuery(value))
                    .isInstanceOf(InvalidAuditQueryException.class);
        }
        assertThatThrownBy(() -> new AuditListRequest(0))
                .isInstanceOf(InvalidAuditQueryException.class);
        assertThatThrownBy(() -> new AuditListRequest(101))
                .isInstanceOf(InvalidAuditQueryException.class);
    }
}
