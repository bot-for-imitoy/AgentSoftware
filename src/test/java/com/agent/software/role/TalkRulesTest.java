package com.agent.software.role;

import com.agent.software.AgentSystem;
import com.agent.software.event.Priority;
import com.agent.software.io.WebInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** talk 规则：同部门 ∧ 在组；管理组豁免部门限制；等待有超时。 */
class TalkRulesTest {

    @Test
    void managementIsExemptButEngineersAreDepartmentScoped(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role architect = system.getStaffing().draftIn("architect");          // Architecture & Release
            Role fullstack = system.getStaffing().draftIn("fullstack_dev");      // Full-Stack Development
            Role ceo = system.getRolePool().find("CEO");                          // Leadership

            assertFalse(architect.canTalkTo(fullstack), "跨部门工程师不能互聊");
            assertFalse(fullstack.canTalkTo(architect));
            assertTrue(ceo.canTalkTo(architect), "管理组豁免部门限制");
            assertTrue(architect.canTalkTo(architect) == false);

            String blocked = architect.talkTo("fullstack_dev", "hi", Priority.NORMAL);
            assertTrue(blocked.startsWith("talk failed:"), blocked);

            String allowed = ceo.talkTo("architect", "please review", Priority.HIGH);
            assertTrue(allowed.startsWith("talk: message sent"), allowed);
        } finally {
            system.stop();
        }
    }

    @Test
    void talkingToUnknownRoleFails() {
        // 用独立 Role 无法判定系统，这里直接用带系统的池验证文案
        AgentSystem system = new AgentSystem(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                "talk-rules-" + System.nanoTime()), new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            assertTrue(ceo.talkTo("nobody", "hi", Priority.NORMAL).startsWith("talk failed:"));
        } finally {
            system.stop();
        }
    }

    @Test
    void waitForReplyTimesOut(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            long start = System.currentTimeMillis();
            String result = ceo.waitForReply("architect", 80);
            assertTrue(result.contains("timed out"), result);
            assertTrue(System.currentTimeMillis() - start >= 50);
            assertFalse(ceo.isWaiting());
        } finally {
            system.stop();
        }
    }
}
