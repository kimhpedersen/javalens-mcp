package com.example;

/**
 * Subclass that invokes label() inherited from SuperInlineTarget. Inlining
 * label() here would substitute `super.toString()` from the parent's body
 * into the subclass's scope — where `super` refers to SuperInlineTarget,
 * not Object. The inline_method tool must detect the super-reference and
 * refuse or warn rather than silently produce semantically-wrong code.
 */
public class SuperInlineConsumer extends SuperInlineTarget {

    public String useIt() {
        return this.label() + "!";
    }
}
