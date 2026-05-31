package com.example.IntelligentRobot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IntelligentRobotApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelligentRobotApplication.class, args);
    }

}
