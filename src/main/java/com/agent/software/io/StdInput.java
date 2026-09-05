package com.agent.software.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Console input channel: reads one line from the standard stream ({@code System.in}).
 *
 * <p>The {@code target} parameter is meaningless here (there is a single console stream, no need to
 * distinguish input boxes) and is ignored.
 *
 * <p>Returns {@code null} on EOF (stream closed / non-interactive), and an {@link Input#error(String)}
 * string when the stream cannot be read.
 */
public class StdInput extends Input {

    @Override
    public String read(String target) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            return Input.error("cannot get user input (non-interactive environment).");
        }
    }

}
