package com.example;

public class WidgetHelper {

    public String describe(FieldHolder holder) {
        return "Pet: " + holder.pet;
    }

    public void swap(FieldHolder holder, Animal newPet) {
        holder.pet = newPet;
    }

    public Animal extract(FieldHolder holder) {
        Animal current = holder.pet;
        holder.pet = null;
        return current;
    }
}
