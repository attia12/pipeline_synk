package fr.tictak.dema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling

public class DemaApplication {

	public static void main(String[] args) {
		SpringApplication.run(DemaApplication.class, args);
	}


}
