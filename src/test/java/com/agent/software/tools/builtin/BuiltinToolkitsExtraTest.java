package com.agent.software.tools.builtin;

import com.agent.software.domain.Payload;
import com.agent.software.domain.RoleSpec;
import com.agent.software.kernel.RoleId;
import com.agent.software.ports.ComputerPort;
import com.agent.software.ports.MailPort;
import com.agent.software.ports.ToolCall;
import com.agent.software.ports.ToolResult;
import com.agent.software.tools.spi.ToolService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolkitsExtraTest {

    private static final RoleId CEO = RoleId.of("CEO");
    private static final RoleId COO = RoleId.of("COO");

    // ── fakes ──────────────────────────────────────────────────────────

    static final class FakeComputer implements ComputerPort {
        boolean on = true;

        @Override
        public String powerOn() {
            on = true;
            return "powered on";
        }

        @Override
        public String powerOff() {
            on = false;
            return "powered off";
        }

        @Override
        public boolean isOn() {
            return on;
        }

        @Override
        public ExecResult exec(String command, Duration timeout, int maxOutputChars) {
            return new ExecResult("ran: " + command, 0);
        }

        @Override
        public Optional<String> readFile(String path) {
            return Optional.empty();
        }

        @Override
        public String writeFile(String path, String content) {
            return path;
        }

        @Override
        public String listDir(String path) {
            return "";
        }

        @Override
        public String deleteFile(String path) {
            return "Deleted: " + path;
        }

        @Override
        public List<com.agent.software.ports.ToolSpec> mcpTools() {
            return List.of();
        }

        @Override
        public ToolResult callMcpTool(String name, Payload arguments) {
            return ToolResult.error("none");
        }

        @Override
        public String workdir() {
            return "/home/ceo";
        }

        @Override
        public String hostDir() {
            return "/host/ceo";
        }

        @Override
        public String driveRoot() {
            return "/mnt/drive";
        }

        @Override
        public String describe() {
            return "Computer [CEO]: status=" + (on ? "powered on" : "powered off");
        }
    }

    static final class FakeMail implements MailPort {
        final Map<String, List<MailMessage>> boxes = new LinkedHashMap<>();
        int seq = 0;

        @Override
        public String addressFor(RoleSpec role) {
            return role.username() + "@company.com";
        }

        @Override
        public String send(MailDraft draft) {
            MailMessage message = new MailMessage("m" + (++seq), draft.senderEmail(), draft.senderName(),
                    draft.subject(), draft.body(), draft.to(), draft.cc(), 0, false);
            for (String to : draft.to()) {
                boxes.computeIfAbsent(to.toLowerCase(), k -> new ArrayList<>()).add(message);
            }
            return "sent to " + String.join(", ", draft.to());
        }

        @Override
        public List<MailMessage> inbox(String address, int limit, boolean unreadOnly) {
            List<MailMessage> all = boxes.getOrDefault(address.toLowerCase(), List.of());
            List<MailMessage> out = new ArrayList<>();
            for (MailMessage m : all) {
                if (unreadOnly && m.read()) {
                    continue;
                }
                if (limit > 0 && out.size() >= limit) {
                    break;
                }
                out.add(m);
            }
            return out;
        }

        @Override
        public Optional<MailMessage> open(String address, String messageId) {
            for (MailMessage m : boxes.getOrDefault(address.toLowerCase(), List.of())) {
                if (m.id().equals(messageId)) {
                    return Optional.of(new MailMessage(m.id(), m.senderEmail(), m.senderName(), m.subject(),
                            m.body(), m.recipients(), m.cc(), m.timestamp(), true));
                }
            }
            return Optional.empty();
        }

        @Override
        public String mode() {
            return "virtual";
        }
    }

    private static RoleSpec spec(RoleId id, String name, String username, String group) {
        return new RoleSpec(id, name, username, 1101, "Title", "", "",
                List.of(), "", group, "", "local", Payload.empty(), List.of());
    }

    private static ToolResult call(ToolService service, RoleId role, String tool, Map<String, Object> args) {
        return service.invoke(role, new ToolCall("c", tool, Payload.of(args)));
    }

    // ── pc ─────────────────────────────────────────────────────────────

    @Test
    void pcToolkitRunsCommandsAndReboots() {
        Map<RoleId, ComputerPort> computers = new LinkedHashMap<>();
        computers.put(CEO, new FakeComputer());
        Function<RoleId, Optional<ComputerPort>> lookup = id -> Optional.ofNullable(computers.get(id));

        ToolService service = new ToolService();
        service.bind(CEO, List.of(PcToolkit.create(lookup, () -> List.of())));

        assertEquals("ran: ls", call(service, CEO, "run_command", Map.of("command", "ls")).text());
        assertTrue(call(service, CEO, "computer_status", Map.of()).text().contains("CEO"));
        assertTrue(call(service, CEO, "reboot", Map.of()).text().contains("powered on"));
        assertTrue(call(service, CEO, "lan_devices", Map.of()).text().contains("no computer devices"));
        assertFalse(call(service, CEO, "run_command", Map.of()).ok(), "missing command must fail validation");
    }

    // ── email ──────────────────────────────────────────────────────────

    @Test
    void emailToolkitSendsReadsAndListsAddresses() {
        FakeMail mail = new FakeMail();
        Map<RoleId, RoleSpec> specs = Map.of(
                CEO, spec(CEO, "Lin Zong", "linzong", "Leadership Group"),
                COO, spec(COO, "Chen Zong", "chenzong", "Leadership Group"));
        Function<RoleId, Optional<RoleSpec>> lookup = id -> Optional.ofNullable(specs.get(id));
        Supplier<List<RoleSpec>> roster = () -> List.copyOf(specs.values());

        ToolService service = new ToolService();
        service.bind(CEO, List.of(EmailToolkit.create(mail, lookup, roster)));

        ToolResult sent = call(service, CEO, "send_email",
                Map.of("to", "Chen Zong", "subject", "Plan", "body", "Please review"));
        assertTrue(sent.ok(), sent.text());
        assertEquals(1, mail.inbox("chenzong@company.com", 10, false).size());

        ToolService cooService = new ToolService();
        cooService.bind(COO, List.of(EmailToolkit.create(mail, lookup, roster)));
        ToolResult inbox = call(cooService, COO, "read_mail", Map.of());
        assertTrue(inbox.text().contains("Plan"));

        String id = mail.inbox("chenzong@company.com", 10, false).get(0).id();
        assertTrue(call(cooService, COO, "open_mail", Map.of("message_id", id)).text().contains("Please review"));
        assertFalse(call(cooService, COO, "open_mail", Map.of("message_id", "missing")).ok());

        assertTrue(call(service, CEO, "mail_address_book", Map.of()).text().contains("Chen Zong"));
    }

    @Test
    void emailToolkitRejectsUnresolvableRecipients() {
        FakeMail mail = new FakeMail();
        Map<RoleId, RoleSpec> specs = Map.of(CEO, spec(CEO, "Lin Zong", "linzong", "Leadership Group"));
        ToolService service = new ToolService();
        service.bind(CEO, List.of(EmailToolkit.create(mail,
                id -> Optional.ofNullable(specs.get(id)), () -> List.copyOf(specs.values()))));

        assertFalse(call(service, CEO, "send_email",
                Map.of("to", "Nobody", "subject", "x", "body", "y")).ok());
    }

    // ── memory ─────────────────────────────────────────────────────────

    @Test
    void memoryToolkitSavesSummaryAndReportsIt() {
        BuiltinToolkitsTest.InMemoryNoteRepository notes = new BuiltinToolkitsTest.InMemoryNoteRepository();
        AtomicReference<Integer> savedDay = new AtomicReference<>();
        ToolService service = new ToolService();
        service.bind(CEO, List.of(MemoryToolkit.create(notes, new BuiltinToolkitsTest.FakeClock(),
                (role, day) -> savedDay.set(day))));

        ToolResult result = call(service, CEO, "summary", Map.of("content", "shipped the refactor"));
        assertTrue(result.ok());
        assertEquals("shipped the refactor", notes.summary(CEO.value(), 1).orElseThrow());
        assertEquals(1, savedDay.get());

        assertFalse(call(service, CEO, "summary", Map.of()).ok(), "empty summary must fail validation");
    }
}
