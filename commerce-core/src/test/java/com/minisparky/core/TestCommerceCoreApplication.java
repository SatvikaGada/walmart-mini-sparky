package com.minisparky.core;

import org.springframework.boot.SpringApplication;

public class TestCommerceCoreApplication {

	public static void main(String[] args) {
		SpringApplication.from(CommerceCoreApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
