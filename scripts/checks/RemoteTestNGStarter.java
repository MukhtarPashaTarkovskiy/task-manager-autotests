package com.intellij.rt.testng;
/** Launcher-name stub only; does not load or execute TestNG. */
public class RemoteTestNGStarter {
    public static void main(String[] args) throws Exception {
        Class.forName("AgentStartupProbe").getMethod("main", String[].class).invoke(null, (Object)args);
    }
}
