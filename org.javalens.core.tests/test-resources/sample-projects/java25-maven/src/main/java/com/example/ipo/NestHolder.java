package com.example.ipo;

/// Holder of a static nested type that move_type_to_new_file extracts into
/// its own top-level file.
public class NestHolder {

    public static class NestedPayload {
        public int weight() {
            return 3;
        }
    }

    public int use() {
        return new NestedPayload().weight();
    }
}
