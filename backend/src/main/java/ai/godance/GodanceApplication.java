package ai.godance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // system-wide jobs (provider-health fold). Per-user Quartz wiring deferred to S13.
public class GodanceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GodanceApplication.class, args);
    }
}
