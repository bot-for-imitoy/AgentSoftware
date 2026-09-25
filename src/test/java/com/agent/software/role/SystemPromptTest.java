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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System Prompt 组装回归：refactor3 首版把 master 的提示词正文丢了（只剩人设三行），
 * 尤其是模板里的 {@code system_prompt_extra} 从未注入，角色不知道自己该干什么、该找谁。
 */
class SystemPromptTest {

    @Test
    void promptCarriesPersonaClockDriveGitMailAndTalkRules(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role architect = system.getStaffing().draftIn("architect");
            String prompt = architect.getLlm().getSystemPrompt();
            assertNotNull(prompt);

            // 人设
            assertTrue(prompt.contains("Wang Jianguo"), prompt);
            assertTrue(prompt.contains("System Architect"), prompt);
            assertTrue(prompt.contains("System architecture design"), prompt);
            // 时钟：角色必须知道今天几号、班次、当前模拟时间
            assertTrue(prompt.contains("Today is "), prompt);
            assertTrue(prompt.contains("company shift 08:00–18:00"), prompt);
            assertTrue(prompt.contains("Current simulated time: "), prompt);
            // 云盘 / Git / 邮件 / talk 范围
            assertTrue(prompt.contains("/mnt/drive/Public"), prompt);
            assertTrue(prompt.contains("/mnt/drive/wangjianguo"), prompt);
            assertTrue(prompt.contains("The company uses Git to manage project code"), prompt);
            assertTrue(prompt.contains("send_email to send / read_mail to receive"), prompt);
            assertTrue(prompt.contains("the talk tool can only message members of your own group"), prompt);
            assertTrue(prompt.contains("architect@"), prompt);
            // 空闲纪律：没事就休息、干完回报
            assertTrue(prompt.contains("If you currently have no task, you may directly rest."), prompt);
        } finally {
            system.stop();
        }
    }

    /** 52/54 个模板带 system_prompt_extra，管理组的工作流定义全在里面，必须进提示词。 */
    @Test
    void promptInjectsTemplateSystemPromptExtra(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            String architect = system.getStaffing().draftIn("architect").getLlm().getSystemPrompt();
            assertTrue(architect.contains("at most 3 sentences"), architect);

            String ceo = system.getRolePool().find("CEO").getLlm().getSystemPrompt();
            assertTrue(ceo.contains("Xu Ruonan"), ceo);          // 需求交给业务分析师
            assertTrue(ceo.contains("hand them to the COO"), ceo);

            String coo = system.getRolePool().find("COO").getLlm().getSystemPrompt();
            assertTrue(coo.contains("hiring request to HR"), coo);

            String hr = system.getRolePool().find("HR").getLlm().getSystemPrompt();
            assertTrue(hr.contains("recruitment tool"), hr);
        } finally {
            system.stop();
        }
    }

    /**
     * COO 的模板文案（抄自 master，那边工程团队是预加载的）说"不要直接指挥一线员工、派工由管理层协调"，
     * 但 refactor3 只准入管理组、且只有 COO 有 draft_in。不把这条说清，COO 会把派工推给 CTO（没有该工具），
     * 结果全公司没人被拉进组、没人干活。
     */
    @Test
    void cooPromptTellsItToDraftTheTeamInAndOthersDoNot(@TempDir Path dir) {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            String coo = system.getRolePool().find("COO").getLlm().getSystemPrompt();
            assertTrue(coo.contains("[Staffing]"), coo);
            assertTrue(coo.contains("draft_in"), coo);
            assertTrue(coo.contains("OUT_OF_GROUP"), coo);
            assertTrue(coo.contains("no other role has draft_in"), coo);
            // 工作流本身写在模板里：拆解 → 用 draft_in 拉人进组 → 派活
            assertTrue(coo.contains("draft the matching employees into the current cohort"), coo);
            // master 那句会造成矛盾的话必须已经改掉
            assertFalse(coo.contains("do not directly command frontline staff"), coo);

            for (String other : List.of("CEO", "CTO", "HR", "business_analyst")) {
                String prompt = system.getRolePool().find(other).getLlm().getSystemPrompt();
                assertFalse(prompt.contains("[Staffing]"),
                        other + " 不该有 COO 专属的调度说明");
            }
        } finally {
            system.stop();
        }
    }

    /**
     * 提示词里的"今天是几号/第几天"必须跟着模拟日走，而且**一天只换一次**。
     *
     * <p>提示词只在 setup 组装一次，而 clockLine 带着日期 —— 实测跑了一周，角色到第 7 天
     * 还在按"今天是第 2 天"做计划，自己发现矛盾后反复调 get_time 校时。
     */
    @Test
    void promptClockFollowsTheSimulatedDay(@TempDir Path dir) throws Exception {
        AgentSystem system = new AgentSystem(dir, new WebInput());
        try {
            Role ceo = system.getRolePool().find("CEO");
            String day1 = ceo.getLlm().getSystemPrompt();
            assertTrue(day1.contains("(day 1)"), day1);

            // 换成立即返回的假 LLM（真实场景里它手上的提示词就是刚组装的那份）
            ScriptedLlm llm = new ScriptedLlm();
            llm.setSystemPrompt(day1);
            ceo.setLlm(llm);

            system.getTimeBus().setNow(600);                 // 同一天 · 08:10
            runOneTask(ceo, "same day work");
            assertEquals(day1, llm.getSystemPrompt(), "同一天之内不该重建提示词");

            system.getTimeBus().setNow(system.getTimeBus().ticksPerDay() * 3 + 600);   // 第 4 天
            String newDate = system.getTimeBus().currentDate().toString();
            runOneTask(ceo, "next day work");
            String newer = llm.getSystemPrompt();
            assertNotEquals(day1, newer, "跨天了提示词必须刷新");
            assertTrue(newer.contains("Today is " + newDate + " (day 4)"), newer);
            assertTrue(newer.contains("Current simulated time: " + newDate), newer);
        } finally {
            system.stop();
        }
    }

    private static void runOneTask(Role role, String content) throws Exception {
        int before = role.taskHistory(100).size();
        role.enqueue(new Task("system", role.roleId, 0, content, Priority.NORMAL));
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && role.taskHistory(100).size() == before) {
            Thread.sleep(20);
        }
        assertTrue(role.taskHistory(100).size() > before, "任务没跑完: " + role.readJournal());
    }

    /** 立即返回的假 LLM：不调工具、直接给答复，任务一轮就结束。 */
    private static final class ScriptedLlm extends LLM {
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
            return new Response("done", "", List.of(), 0);
        }
    }
}
