package com.example;

public class AnonymousShapeUser {

    public IShape anonymousShape() {
        return new IShape() {
            @Override
            public void draw() {
                System.out.println("anonymous draw");
            }
        };
    }

    public IFillable anonymousFillable() {
        return new IFillable() {
            @Override
            public void draw() {
                System.out.println("anonymous filled draw");
            }

            @Override
            public void fill() {
                System.out.println("anonymous fill");
            }
        };
    }

    public static class InnerShape implements IShape {
        @Override
        public void draw() {
            System.out.println("inner shape draw");
        }
    }

    public class NonStaticInnerShape implements IShape {
        @Override
        public void draw() {
            System.out.println("non-static inner shape draw");
        }
    }
}
