package dev.mayank.convoinsights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Arrays;

@SpringBootApplication
@EnableScheduling
public class ConvoInsightsApplication {

	public static void main(String[] args) {
		var ctx = SpringApplication.run(ConvoInsightsApplication.class, args);
		// Replay and re-drive are one-shot tools: close the context once their runner has finished.
		var profiles = Arrays.asList(ctx.getEnvironment().getActiveProfiles());
		if (profiles.contains("replay") || profiles.contains("redrive")) {
			System.exit(SpringApplication.exit(ctx));
		}
	}

}
