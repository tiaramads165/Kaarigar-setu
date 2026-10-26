package com.kaarigarsetu.console;

/** Plain main class: a fat JAR cannot start an Application subclass directly on the classpath. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        ModeratorConsoleApp.main(args);
    }
}
