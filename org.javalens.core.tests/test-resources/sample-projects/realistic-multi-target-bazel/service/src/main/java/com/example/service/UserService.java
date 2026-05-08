package com.example.service;

import com.example.model.User;

public class UserService {

    public String formatGreeting(User user) {
        if (user == null || user.getName() == null || user.getName().isBlank()) {
            return "Hello, anonymous!";
        }
        return "Hello, " + user.getName() + "!";
    }

    public boolean isAdult(User user) {
        return user != null && user.getAge() >= 18;
    }
}
