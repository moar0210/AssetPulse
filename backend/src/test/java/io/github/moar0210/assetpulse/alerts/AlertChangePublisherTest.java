package io.github.moar0210.assetpulse.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AlertChangePublisherTest {

    private static final UUID ORGANISATION_ID = UUID.randomUUID();
    private static final UUID ALERT_ID = UUID.randomUUID();

    @AfterEach
    void clearTransactionState() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void publishesOnlyWhenTheTransactionHasCommitted() {
        AlertStreamService streamService = mock(AlertStreamService.class);
        AlertChangePublisher publisher = new AlertChangePublisher(streamService);
        beginTransactionSynchronization();

        publisher.publishAfterCommit(
                ORGANISATION_ID, ALERT_ID, AlertChangeType.OCCURRENCE_RECORDED);

        verifyNoInteractions(streamService);
        List<TransactionSynchronization> synchronizations =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(synchronizations).hasSize(1);

        synchronizations.getFirst().afterCommit();

        verify(streamService)
                .publish(
                        ORGANISATION_ID,
                        new AlertChangeEvent(ALERT_ID, AlertChangeType.OCCURRENCE_RECORDED));
    }

    @Test
    void aRolledBackTransactionPublishesNothing() {
        AlertStreamService streamService = mock(AlertStreamService.class);
        AlertChangePublisher publisher = new AlertChangePublisher(streamService);
        beginTransactionSynchronization();

        publisher.publishAfterCommit(ORGANISATION_ID, ALERT_ID, AlertChangeType.STATUS_CHANGED);
        TransactionSynchronizationManager.getSynchronizations()
                .getFirst()
                .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(streamService);
    }

    @Test
    void workWithoutARealTransactionCannotPublishAnInvalidation() {
        AlertStreamService streamService = mock(AlertStreamService.class);
        AlertChangePublisher publisher = new AlertChangePublisher(streamService);

        publisher.publishAfterCommit(ORGANISATION_ID, ALERT_ID, AlertChangeType.STATUS_CHANGED);

        verifyNoInteractions(streamService);
    }

    @Test
    void notificationFailureCannotEscapeAfterACommittedTransaction() {
        AlertStreamService streamService = mock(AlertStreamService.class);
        AlertChangePublisher publisher = new AlertChangePublisher(streamService);
        AlertChangeEvent change = new AlertChangeEvent(ALERT_ID, AlertChangeType.STATUS_CHANGED);
        doThrow(new IllegalStateException("subscriber failure"))
                .when(streamService)
                .publish(ORGANISATION_ID, change);
        beginTransactionSynchronization();
        publisher.publishAfterCommit(ORGANISATION_ID, ALERT_ID, AlertChangeType.STATUS_CHANGED);
        TransactionSynchronization synchronization =
                TransactionSynchronizationManager.getSynchronizations().getFirst();

        assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();
    }

    private void beginTransactionSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
