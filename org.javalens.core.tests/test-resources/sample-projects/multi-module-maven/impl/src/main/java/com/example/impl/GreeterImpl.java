package com.example.impl;

import com.example.api.Greeter;
import org.apache.commons.lang3.StringUtils;

public class GreeterImpl implements Greeter {

    @Override
    public String sayHello(String name) {
        return "Hello, " + StringUtils.capitalize(name) + "!";
    }
}
