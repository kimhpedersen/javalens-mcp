package com.example;

public sealed interface Vehicle permits Car, Truck {

    int wheels();
}
