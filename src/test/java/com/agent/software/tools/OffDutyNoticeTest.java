package com.agent.software.tools;

import com.agent.software.AgentSystem;
import com.agent.software.io.WebInput;
import com.agent.software.role.Role;
import com.agent.software.tools.toolkits.email.SendEmail;
import com.agent.software.tools.toolkits.talk.TalkTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下班时段发出去的邮件 / talk 要等到次日上班才会被对方看到，
 * 所以两种工具的返回结果里都要再提醒一次"该收工了"。
 */
class OffDutyNoticeTest {

    private static Map<String, Object> mail() {
        return Map.of("to", "COO", "subject", "s", "body", "b");
    }

    private static Map<String, Object> talk() {
        return Map.of("target", "COO", "message", "hi");
    }

    @Test
    void offDutyMailAndTalkRemindYouToWrapUp(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            system.getTimeBus().setNow(system.getTimeBus().getShiftEndTick() + 500);
            Role ceo = system.getRolePool().find("CEO");

            String mail = new SendEmail(ceo, system.getMailService()).handler(mail());
            assertTrue(mail.contains("off duty"), mail);
            assertTrue(mail.contains("will NOT see"), mail);
            assertTrue(mail.contains("wrap-up"), mail);

            String talk = new TalkTo(ceo).handler(talk());
            assertTrue(talk.contains("off duty"), talk);
            assertTrue(talk.contains("will NOT see"), talk);
        } finally {
            system.stop();
        }
    }

    @Test
    void workingHoursStayQuiet(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            assertTrue(system.getTimeBus().isWorkingHours(), "开局应是上班时间");
            Role ceo = system.getRolePool().find("CEO");

            String mail = new SendEmail(ceo, system.getMailService()).handler(mail());
            assertFalse(mail.contains("off duty"), mail);

            String talk = new TalkTo(ceo).handler(talk());
            assertFalse(talk.contains("off duty"), talk);
        } finally {
            system.stop();
        }
    }

    /** 发送本身失败时不要附下班提醒，否则真正的错因会被这段文字盖住。 */
    @Test
    void failedSendDoesNotCarryTheReminder(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            system.getTimeBus().setNow(system.getTimeBus().getShiftEndTick() + 500);
            Role ceo = system.getRolePool().find("CEO");

            String talk = new TalkTo(ceo).handler(Map.of("target", "no_such_role", "message", "hi"));
            assertTrue(talk.startsWith("talk failed"), talk);
            assertFalse(talk.contains("off duty"), talk);

            String mail = new SendEmail(ceo, system.getMailService())
                    .handler(Map.of("to", "no_such_person", "subject", "s", "body", "b"));
            assertTrue(mail.startsWith("send_email error"), mail);
            assertFalse(mail.contains("off duty"), mail);
        } finally {
            system.stop();
        }
    }
}
