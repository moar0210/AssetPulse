package io.github.moar0210.assetpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AssetPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(AssetPulseApplication.class, args);
    }
}
