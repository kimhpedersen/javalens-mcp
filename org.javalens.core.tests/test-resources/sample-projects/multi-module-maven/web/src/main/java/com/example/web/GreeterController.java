package com.example.web;

import com.example.impl.GreeterImpl;
import org.springframework.util.StringUtils;

public class GreeterController {

    private final GreeterImpl greeter = new GreeterImpl();

    public String handle(String name) {
        if (!StringUtils.hasText(name)) {
            return greeter.sayHello("anonymous");
        }
        return greeter.sayHello(name);
    }
}
