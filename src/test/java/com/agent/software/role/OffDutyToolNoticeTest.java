package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.event.Priority;
import com.agent.software.event.Task;
import com.agent.software.io.WebInput;
import com.agent.software.llm.LLM;
import com.agent.software.llm.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：下班时段模型真的调 send_email / talk，走完整个工具循环后，
 * 回到模型上下文里的工具结果必须带"对方收不到，该收工了"的提醒。
 */
class OffDutyToolNoticeTest {

    @Test
    void offDutyMailAndTalkReachTheModelWithTheWrapUpReminder(@TempDir Path dir) throws Exception {
        writeToolkitConfig(dir);   // talk 默认关着，这个用例要显式打开
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            system.getTimeBus().setNow(system.getTimeBus().getShiftEndTick() + 500);

            Role ceo = system.getRolePool().find("CEO");
            Scripted llm = new Scripted(ceo);
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "routine work", Priority.NORMAL));

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && ceo.taskHistory(1).isEmpty()) {
                Thread.sleep(20);
            }
            assertFalse(ceo.taskHistory(1).isEmpty(), "任务没跑完: " + ceo.readJournal());

            String mail = llm.toolResults.get(0);
            assertTrue(mail.contains("mail sent"), mail);
            assertTrue(mail.contains("[off duty]"), mail);
            assertTrue(mail.contains("will NOT see"), mail);
            assertTrue(mail.contains("wrap-up"), mail);

            String talk = llm.toolResults.get(1);
            assertTrue(talk.contains("talk: message sent"), talk);
            assertTrue(talk.contains("[off duty]"), talk);
        } finally {
            system.stop();
        }
    }

    /** 上班时间同样的调用不该多出这段提醒。 */
    @Test
    void workingHoursCallsStayClean(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            Scripted llm = new Scripted(ceo);
            ceo.setLlm(llm);
            ceo.enqueue(new Task("system", "CEO", 0, "routine work", Priority.NORMAL));

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && ceo.taskHistory(1).isEmpty()) {
                Thread.sleep(20);
            }
            assertFalse(ceo.taskHistory(1).isEmpty(), "任务没跑完: " + ceo.readJournal());
            assertFalse(llm.toolResults.get(0).contains("[off duty]"), llm.toolResults.get(0));
        } finally {
            system.stop();
        }
    }

    /** 两轮工具调用（send_email、talk），第三轮收工。 */
    private static void writeToolkitConfig(Path dir) throws Exception {
        java.nio.file.Files.writeString(dir.resolve("toolkits.default.json"),
                "{\"default_toolkits\": [\"time\", \"task\", \"note\", \"todo\", \"memory\", \"pc\", "
                        + "\"mcp_manager\", \"skill\", \"email\", \"talk\"]}");
    }

    private static final class Scripted extends LLM {

        private final Role role;
        private int requests = 0;
        final List<String> toolResults = new ArrayList<>();

        Scripted(Role role) {
            this.role = role;
        }

        @Override
        public String getModel() {
            return "scripted";
        }

        @Override
        public String getEndpoint() {
            return "scripted://";
        }

        @Override
        public Response request() {
            requests++;
            if (requests == 1) {
                return new Response("working", "",
                        List.of(call("call_1", "send_email", Map.of(
                                "to", "COO", "subject", "wrap up", "body", "please tidy up"))), 0);
            }
            if (requests == 2) {
                return new Response("working", "",
                        List.of(call("call_2", "talk", Map.of(
                                "target", "COO", "message", "are you still there?"))), 0);
            }
            return new Response("done", "", List.of(), 0);
        }

        @Override
        public void appendToolResult(String toolCallId, String name, String result) {
            toolResults.add(result);
            super.appendToolResult(toolCallId, name, result);
        }

        private static Map<String, Object> call(String id, String name, Map<String, Object> args) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", name);
            function.put("arguments", args);
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("id", id);
            call.put("type", "function");
            call.put("function", function);
            return call;
        }
    }
}
