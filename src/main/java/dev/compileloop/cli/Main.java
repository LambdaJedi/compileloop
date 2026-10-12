package dev.compileloop.cli;

import dev.compileloop.build.BuildRunner;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        BuildRunner runner = new BuildRunner();
        Cli cli = new Cli(runner::runMavenTests, System.out, System.err);
        System.exit(cli.run(args));
    }
}
