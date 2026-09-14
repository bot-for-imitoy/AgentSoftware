package com.agent.software.app;

import com.agent.software.adapters.input.ConsoleInputAdapter;
import com.agent.software.adapters.web.ChatWebAdapter;
import com.agent.software.config.AppConfig;
import com.agent.software.config.AppPaths;
import com.agent.software.config.ConfigLoader;
import com.agent.software.ports.InputPort;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Simulation entry point.
 *
 * <p>Builds one {@link Application} from the unified configuration and runs the
 * shift clock for a number of simulated days. Input is either the console or,
 * with {@code --web}, the Web UI (which can also be switched in at runtime).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);

        AppPaths bootstrap = AppPaths.resolve(AppConfig.defaults().storage());
        Path configFile = bootstrap.configFile("config.json");
        AppConfig config = ConfigLoader.load(configFile).toAppConfig();

        InputPort input = options.web ? null : new ConsoleInputAdapter();
        Application app = Application.create(config, input);

        if (options.web) {
            ChatWebAdapter web = app.startWeb(config.web().host(), config.web().port());
            app.setInput(app.webInput());
            System.out.println("Web UI: " + web.url());
        } else {
            System.out.println("Console input mode (use --web for the browser UI).");
        }

        if (options.roleIds.isEmpty()) {
            app.hireDefaultRoles();
        } else {
            app.hireRoles(options.roleIds);
        }
        int restored = app.restoreState();
        System.out.println("Team: " + app.team().size() + " roles"
                + (restored > 0 ? " (" + restored + " restored from state.json)" : ""));

        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "shutdown"));
        app.start();
        System.out.println("Started: " + app.clock().describe());

        int startDay = app.clock().day();
        while (app.clock().day() < startDay + options.days) {
            Thread.sleep(1000);
            if (options.verbose) {
                System.out.println(app.clock().describe() + " | " + app.team().roster().size() + " roles");
            }
        }
        app.stop();
        System.out.println("Finished after " + options.days + " simulated day(s).");
    }

    /** Parsed command line. */
    record Options(boolean web, int days, List<String> roleIds, boolean verbose) {

        static Options parse(String[] args) {
            boolean web = false;
            boolean verbose = false;
            int days = 1;
            List<String> roleIds = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--web" -> web = true;
                    case "--verbose" -> verbose = true;
                    case "--days" -> {
                        if (i + 1 < args.length) {
                            days = Math.max(1, Integer.parseInt(args[++i].strip()));
                        }
                    }
                    case "--roles" -> {
                        if (i + 1 < args.length) {
                            roleIds.addAll(Arrays.stream(args[++i].split(","))
                                    .map(String::strip)
                                    .filter(s -> !s.isEmpty())
                                    .toList());
                        }
                    }
                    default -> System.out.println("Ignoring unknown argument: " + args[i]);
                }
            }
            return new Options(web, days, roleIds, verbose);
        }
    }
}
