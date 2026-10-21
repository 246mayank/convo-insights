package dev.mayank.convoinsights;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class ConvoInsightsApplication {

	public static void main(String[] args) {
		var ctx = SpringApplication.run(ConvoInsightsApplication.class, args);
		// Replay mode is a one-shot producer: close the context once the runner has finished.
		if (Arrays.asList(ctx.getEnvironment().getActiveProfiles()).contains("replay")) {
			System.exit(SpringApplication.exit(ctx));
		}
	}

}
