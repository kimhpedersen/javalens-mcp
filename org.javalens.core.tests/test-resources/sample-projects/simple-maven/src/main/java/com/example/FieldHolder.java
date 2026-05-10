package com.example;

public class FieldHolder {

    Animal pet;

    public FieldHolder() {
        this.pet = new Animal();
    }

    public FieldHolder(Animal pet) {
        this.pet = pet;
    }

    public Animal getPet() {
        return pet;
    }
}
