package com.agent.software;

import com.agent.software.io.StdInput;
import com.agent.software.tools.toolkits.hr.Hr;
import com.agent.software.role.AgentRole;
import com.agent.software.role.RoleLoader;
import com.agent.software.store.NoteStore;
import com.agent.software.store.StateStore;
import com.agent.software.event.TimeEventBus;
import com.agent.software.core.Types;
import com.agent.software.web.ChatWebServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Main entry point (the Java counterpart of the Python main.py).
 *
 * Starts a complete multi-role AI team simulation on a simulated calendar clock: every work day
 * starts at 08:00 of a real calendar date (day 1 = the day the run starts) and ends at 18:00;
 * after the end-of-day summaries the clock rolls over to the next day at 08:00 and loops.
 */
public class Main {

    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    // ── Terminal colors ─────────────────────────────────────────
    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String YELLOW = "\033[33m";
    private static final String RED = "\033[31m";
    private static final String CYAN = "\033[36m";
    private static final String MAGENTA = "\033[35m";
    private static final String RESET = "\033[0m";

    // Time semantics (see TimeEventBus): 1 Tick = 10 simulated minutes; shift 08:00 (tick 0) → 18:00 (tick 60).
    private static final int SHIFT_END_TICK = TimeEventBus.SHIFT_END_TICK;
    private static final int TICKS_PER_DAY = TimeEventBus.TICKS_PER_DAY;

    private static final SortedSet<String> ROLE_IDS = new TreeSet<>(RoleLoader.DEFAULT_ROLES);

    /** Web UI URL (filled at startup, used for runOneDay hints). */
    static volatile String webUrl = "";

    // ── Terminal UI ─────────────────────────────────────────────

    static void header(String text) {
        consolePrint("\n" + BOLD + CYAN + "═".repeat(62) + RESET);
        consolePrint(BOLD + CYAN + "  " + text + RESET);
        consolePrint(BOLD + CYAN + "═".repeat(62) + RESET + "\n");
    }

    static void step(String text) {
        consolePrint(MAGENTA + "▶ " + text + RESET);
    }

    static void info(String text) {
        consolePrint("  " + text);
    }

    static void ok(String text) {
        consolePrint("  " + GREEN + "✓ " + text + RESET);
    }

    static void warn(String text) {
        consolePrint("  " + YELLOW + "⚠ " + text + RESET);
    }

    private static void consolePrint(String msg) {
        System.out.println(msg);
    }

    /** Polls until the condition is satisfied (real time). Returns true = satisfied, false = timed out. */
    static boolean waitUntil(String desc, java.util.function.Supplier<Boolean> predicate,
                             long timeoutSeconds) {
        info("Waiting: " + desc + " (up to " + (timeoutSeconds / 60) + " minutes)...");
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(predicate.get())) {
                    ok(desc + " ✓");
                    return true;
                }
            } catch (Exception ignored) {
            }
            sleep(5000);
        }
        warn("Wait timed out: " + desc);
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs one full work day (the simulated clock advances inside TimeEventBus). */
    static void runOneDay(AgentSystem system, int day, boolean withClientTask) {
        header("Day " + day + " — " + system.timeManager.currentDateString());

        // New day: wait for the day boundary (next day's 08:00 → SHIFT_START fires automatically after the wrap-up)
        if (day > 1) {
            int targetDay = day;
            waitUntil("Day " + day + " begins (" + system.timeManager.currentDateString()
                            + " 08:00, SHIFT_START fires automatically after the previous day's wrap-up)",
                    () -> system.day() >= targetDay,
                    24 * 3600L);
        }
        ok("Current: " + system.describe());

        // Day 1: the CEO talks to the client (only once)
        if (withClientTask) {
            step("CEO registers the opening note reminder: Tick 1 (08:10) to discuss project requirements with the user...");
            AgentRole ceo = system.getRole("CEO");
            Path note = ceo.noteStore().writeNote("Day1-collect-project-requirements",
                    "Talk to the user about the project requirements and gather what needs to be built today", 1, day);
            ok("Note + reminder registered: " + note + " (Day " + day + " Tick 1 = 08:10 → CEO)");
            step("Waiting for Tick 1 to fire (CEO task → talk to the user)...");
            int fireTick = (day - 1) * TICKS_PER_DAY + 1;
            waitUntil("Tick " + fireTick + " reached (CEO task fired)",
                    () -> system.timeManager.currentTick() >= fireTick,
                    15 * 60L);
            info("Please enter the project requirements at the [CEO] prompt above (e.g. build a payment system for me)");
            if (!webUrl.isEmpty()) {
                info("The Web UI " + webUrl + " mirrors the conversation in real time; this run reads your reply from the console prompt above (StdInput). "
                        + "Use demo/WebDemo for a Web-page input run (WebInput).");
            }
            sleep(10_000);
        } else {
            step("No client communication task today; moving straight to the daily routine...");
            sleep(5_000);
        }

        // Daytime work events
        step("Delivering a LOW event (small talk; should be filtered by salience, 0 tokens)...");
        Map<String, Object> spamPayload = new java.util.LinkedHashMap<>();
        spamPayload.put("text", "What's for lunch?");
        spamPayload.put("channel", "#random");
        Types.Event spam = new Types.Event("slack", "chat", Types.Priority.LOW, spamPayload, null);
        Map<String, Map<String, Object>> results = system.trigger(spam);
        Map<String, Boolean> acceptedMap = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : results.entrySet()) {
            acceptedMap.put(e.getKey(), (Boolean) e.getValue().get("accepted"));
        }
        info("LOW filter result: " + acceptedMap);

        // Wait for shift end (18:00; SHIFT_END fires automatically — the clock may advance while
        // roles work, and fast-forwards once the whole team is idle)
        step("Waiting for shift end... (" + system.timeManager.shiftEndTime()
                + " shift end, SHIFT_END fires automatically)");
        waitUntil("Shift end reached (SHIFT_END fired)",
                () -> system.timeManager.tickOfDay() >= SHIFT_END_TICK,
                11 * 3600L);
        sleep(5_000);

        step("Waiting for roles to call the summary tool (concurrent, up to 600 seconds)...");
        long deadline = System.currentTimeMillis() + 600_000;
        while (System.currentTimeMillis() < deadline) {
            boolean allOff = true;
            for (String rid : ROLE_IDS) {
                if (system.getRole(rid).state != Types.AgentState.OFF_DUTY) {
                    allOff = false;
                    break;
                }
            }
            if (allOff) {
                break;
            }
            sleep(5_000);
        }

        // Check shift-end status and summaries
        step("Checking shift-end status...");
        List<String> offDuty = new ArrayList<>();
        for (String rid : ROLE_IDS) {
            if (system.getRole(rid).state == Types.AgentState.OFF_DUTY) {
                offDuty.add(rid);
            }
        }
        if (!offDuty.isEmpty()) {
            List<String> head = offDuty.size() > 6 ? offDuty.subList(0, 6) : offDuty;
            ok("OFF_DUTY roles: " + offDuty.size() + "/" + ROLE_IDS.size()
                    + " (" + String.join(", ", head) + (offDuty.size() > 6 ? "..." : "") + ")");
        } else {
            warn("Not all roles are OFF_DUTY yet");
        }

        for (String rid : ROLE_IDS) {
            AgentRole role = system.getRole(rid);
            String summary = role.noteStore().getSummary(day);
            if (summary == null) {
                // The summary tool shuts the computer down right after saving; read the host-mounted dir directly
                String hostDir = role.computerIfCreated() != null ? role.computerIfCreated().hostDir() : "";
                if (!hostDir.isEmpty()) {
                    Path hostSummary = Paths.get(hostDir, "summaries", NoteStore.summaryFilename(day));
                    if (Files.exists(hostSummary)) {
                        try {
                            summary = Files.readString(hostSummary);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (summary != null && !summary.isEmpty()) {
                ok("[" + rid + "] Day " + day + " summary saved: "
                        + (summary.length() > 50 ? summary.substring(0, 50) : summary) + "...");
            } else {
                info("[" + rid + "] no summary yet");
            }
        }
    }

    public static void main(String[] args) {
        header("Agent software company demo — simulated calendar clock (1 Tick = 10 minutes; shift 08:00 → 18:00)");

        // 1. Kickoff: default team (management + engineering team)
        List<String> roleIds = new ArrayList<>(ROLE_IDS);
        step("Creating AgentSystem, adding " + roleIds.size() + " default roles...");
        AgentSystem system = new AgentSystem(null, roleIds, 30.0, true, new StdInput());
        system.getRole("HR").addToolkit(new Hr(system.getRole("HR"), null));
        ok("Roles ready: " + system.pool.listRoles().size() + " people (CEO/COO/HR + engineering team)");
        ok("Leadership group equipped with talk_to_client (real-time chat with the client; only one person can talk to the client at a time)");
        ok("HR equipped with hiring tools (post_job_posting / list_candidates)");

        // 1.5 Web UI: group chat monitor (this run uses the console StdInput channel; Web-page input is demoed by WebDemo)
        ChatWebServer web = null;
        try {
            web = new ChatWebServer(system);
            web.start();
            webUrl = "http://127.0.0.1:" + web.port() + "/";
            ok("Web UI started: " + webUrl);
            ok("  ├─ Left: group selector (Leadership Group / Frontend Development Group / Backend Development Group / ... )");
            ok("  └─ Right: chat window — live monitor of the group chats and the Client A conversation (this run's input channel is the console prompt; WebInput-based runs enable the page input box)");
        } catch (Exception e) {
            warn("Web UI failed to start (does not affect the main flow): " + e.getMessage());
        }

        // 0. Restore the previous progress (StateStore)
        StateStore store = new StateStore();
        int restored = store.exists() ? store.restore(system) : 0;
        if (restored > 0) {
            ok("Restored " + restored + " roles from the archive → " + system.describe());
        } else {
            ok("No archive; starting fresh on Day 1 at " + system.timeManager.currentDateTime()
                    + " (day 1 = today, shift starts at 08:00)");
        }

        // 2. Start the system
        system.start();
        ok("System started: " + system.describe());
        ok("Time rules: 1 Tick = " + TimeEventBus.MINUTES_PER_TICK + " simulated minutes; the clock advances while the team works"
                + " (default 1 simulated minute per real second, " + system.timeManager.simMinutesPerRealSecond + "×) and fast-forwards when"
                + " everyone is idle; shift " + system.timeManager.shiftStartTime() + " → " + system.timeManager.shiftEndTime()
                + "; after the daily wrap-up the sim rolls over to the next day at 08:00");
        sleep(3_000);  // wait for SHIFT_START (08:00) to fire
        info("Role states: " + states(system));

        // 3. Multi-day loop
        int day = system.day();
        try {
            while (true) {
                runOneDay(system, day, day == 1);
                // Day over: automatically move to the next day
                int nextDay = day + 1;
                consolePrint("\n" + BOLD + GREEN + "═".repeat(62) + RESET);
                consolePrint(BOLD + GREEN + "  🎉 Day " + day + " complete!" + RESET);
                consolePrint(BOLD + GREEN + "  Automatically entering Day " + nextDay + " (" + system.timeManager.currentDateString()
                        + "): after all roles finished their summaries the clock rolled to the next 08:00 shift start" + RESET);
                consolePrint(BOLD + GREEN + "═".repeat(62) + RESET + "\n");
                day = nextDay;
            }
        } catch (Exception e) {
            warn("\nInterrupted; saving progress and exiting...");
        } finally {
            // Auto-save on exit
            try {
                store.save(system);
            } catch (Exception e) {
                logger.error("Failed to save state", e);
            }
            system.stop();
            if (web != null) {
                try {
                    web.stop();
                } catch (Exception ignored) {
                }
            }
            consolePrint("\n" + BOLD + GREEN + "Demo finished ✓ (ran through Day " + day + "; progress saved)" + RESET + "\n");
        }
    }

    private static Map<String, String> states(AgentSystem system) {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        for (String rid : ROLE_IDS) {
            m.put(rid, system.getRole(rid).state.value);
        }
        return m;
    }
}
