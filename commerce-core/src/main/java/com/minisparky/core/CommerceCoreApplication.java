package com.minisparky.core;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CommerceCoreApplication {

	public static void main(String[] args) {
		// Windows JVMs on Indian locale report the legacy name "Asia/Calcutta",
		// which the Postgres container rejects. Use the canonical name.
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication.run(CommerceCoreApplication.class, args);
	}
}