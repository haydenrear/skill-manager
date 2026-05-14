package dev.skillmanager.commands;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.skillmanager.util.Log;

import java.io.IOException;

final class JsonOutput {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonOutput() {}

    static boolean print(Object value) {
        try {
            System.out.println(JSON.writeValueAsString(value));
            return true;
        } catch (IOException io) {
            Log.error("could not write JSON: %s", io.getMessage());
            return false;
        }
    }
}
