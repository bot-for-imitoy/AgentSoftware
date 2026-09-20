package com.agent.software.llm;

import java.io.IOException;
import java.util.List;

/** Converts text into a numeric vector suitable for similarity comparisons. */
@FunctionalInterface
public interface EmbeddingModel {
    List<Double> embed(String input) throws IOException, InterruptedException;
}
