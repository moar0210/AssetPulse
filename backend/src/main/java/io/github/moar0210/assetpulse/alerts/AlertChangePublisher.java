package io.github.moar0210.assetpulse.alerts;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class AlertChangePublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertChangePublisher.class);

    private final AlertStreamService streamService;

    public AlertChangePublisher(AlertStreamService streamService) {
        this.streamService = streamService;
    }

    public void publishAfterCommit(UUID organisationId, UUID alertId, AlertChangeType changeType) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        AlertChangeEvent change = new AlertChangeEvent(alertId, changeType);
        try {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                streamService.publish(organisationId, change);
                            } catch (RuntimeException notificationFailure) {
                                LOGGER.warn(
                                        "Committed alert change could not be published to subscribers ({})",
                                        notificationFailure.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (RuntimeException registrationFailure) {
            LOGGER.warn(
                    "Committed alert change could not be scheduled for subscriber publication ({})",
                    registrationFailure.getClass().getSimpleName());
        }
    }
}
