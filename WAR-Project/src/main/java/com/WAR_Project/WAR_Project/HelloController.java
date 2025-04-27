package com.WAR_Project.WAR_Project;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @GetMapping("/")
    public String home() {
        return "Welcome to Faisal's War Spring Boot API!"; // Tumhara pehle wala message
    }

    @GetMapping("/hello")
    public String hello() {
        return "Hello from WAR project!"; // Tumhara new message
    }
}