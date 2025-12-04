package cn.navwise.mage.mydevops.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloWorldController {

    @GetMapping("/hello")
    public String helloWorld() {
        System.out.println("Hello, World!");
        System.out.println("Hello, World!");
        return "Hello, World!";
    }
}
