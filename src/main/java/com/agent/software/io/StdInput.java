package com.agent.software.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class StdInput extends Input{

    public String read(String target){
        String reply;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            reply = reader.readLine();
        } catch (IOException e) {
            return "talk_to_client: Error: cannot get user input (non-interactive environment).";
        }
        return reply;
    }

}
