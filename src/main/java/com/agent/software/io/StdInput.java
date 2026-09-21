package com.agent.software.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class StdInput extends Input {

    @Override
    public String read(String target) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            return reader.readLine();
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

}
