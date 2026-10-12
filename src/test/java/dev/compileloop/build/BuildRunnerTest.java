package dev.compileloop.build;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildRunnerTest {

    @Test
    void shouldReturnCompileLoopName() {
        BuildRunner runner = new BuildRunner();

        assertEquals("CompileLoop", runner.getName());
    }
}
