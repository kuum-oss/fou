package com.notifyhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class NotifyHubApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotifyHubApplication.class, args);
    }
}
