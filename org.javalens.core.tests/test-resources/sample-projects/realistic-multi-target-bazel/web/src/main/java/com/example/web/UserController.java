package com.example.web;

import com.example.model.User;
import com.example.service.UserService;

public class UserController {

    private final UserService service = new UserService();

    public String handleGreeting(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        return service.formatGreeting(user);
    }

    public boolean canVote(User user) {
        return service.isAdult(user);
    }
}
