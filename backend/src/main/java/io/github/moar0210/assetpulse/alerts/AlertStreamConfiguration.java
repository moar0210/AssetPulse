package io.github.moar0210.assetpulse.alerts;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AlertStreamConfiguration {

    public static final String NOTIFICATION_EXECUTOR = "alertStreamNotificationExecutor";

    @Bean(name = NOTIFICATION_EXECUTOR, destroyMethod = "shutdownNow")
    ExecutorService alertStreamNotificationExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
