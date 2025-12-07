package cn.navwise.mage.mydevops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RestController
public class HelloWorldController {

    @GetMapping("/hello")
    public String helloWorld() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String formattedDate = now.format(formatter);
        String message = "Hello, World! Current date and time is: " + formattedDate;
        System.out.println(message);
        return message;
    }
}
