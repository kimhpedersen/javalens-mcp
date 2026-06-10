package com.example.ipo;

/// Target for introduce_parameter_object: send(...) takes three parameters
/// worth bundling; the caller below must be rewritten to construct the bundle.
public class ParamBundleTarget {

    public String send(String host, int port, boolean secure) {
        return host + ":" + port + (secure ? "!" : "");
    }

    public String sendDefault() {
        return send("localhost", 8080, true);
    }
}
