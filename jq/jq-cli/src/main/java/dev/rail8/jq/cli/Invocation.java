package dev.rail8.jq.cli;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * One command-line run as the in-process entry receives it: the arguments after the program name, the
 * environment, the working directory and the three standard streams. Not exported API.
 */
public record Invocation(
    List<String> args, Map<String, String> env, Path cwd, InputStream in, OutputStream out, OutputStream err) {

  public Invocation {
    args = List.copyOf(args);
    env = Map.copyOf(env);
  }
}
