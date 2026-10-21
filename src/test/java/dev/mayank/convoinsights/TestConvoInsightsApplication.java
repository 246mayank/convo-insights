package dev.mayank.convoinsights;

import org.springframework.boot.SpringApplication;

public class TestConvoInsightsApplication {

	public static void main(String[] args) {
		SpringApplication.from(ConvoInsightsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
