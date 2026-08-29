package com.maf.scheduler.tools;

import com.maf.scheduler.core.AgentRole;
import com.maf.scheduler.core.TimeEventBus;
import com.maf.scheduler.core.ToolRegistry.ToolKit;
import com.maf.scheduler.core.ToolRegistry.ToolHandler;
import com.maf.scheduler.core.Types;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 时间工具类 (Time ToolKit) — Python 版 time_toolkit.py.
 *
 * 包含: get_time / take_rest.
 */
public final class TimeToolkit {

    private static final Logger logger = LoggerFactory.getLogger(TimeToolkit.class);

    private TimeToolkit() {
    }

    /** 创建时间工具类. */
    public static ToolKit createTimeToolkit() {
        ToolKit tk = new ToolKit("time", "时间与作息工具类");

        ToolHandler getTime = args -> {
            TimeEventBus manager = (TimeEventBus) tk.require("manager", "时间管理器");
            int tick = manager.currentTick();
            return manager.describe() + "\n当前 Tick 数: " + tick;
        };

        ToolHandler takeRest = args -> {
            TimeEventBus manager = (TimeEventBus) tk.require("manager", "时间管理器");
            AgentRole role = (AgentRole) tk.require("role", "角色");
            if (role != null && role.state != Types.AgentState.ON_DUTY_IDLE) {
                role.setState(Types.AgentState.ON_DUTY_IDLE);
                logger.info("[{}] 开始休息 (状态 ON_DUTY_IDLE, 等待事件唤醒)", role.roleId);
            }
            return "已开始休息 (状态 ON_DUTY_IDLE). 有任务或事件到来时会自动唤醒.";
        };

        tk.addPythonTool("get_time",
                "查看当前作息时间. 返回当前 Tick 数和作息状态. "
                        + "时间规则: 1 Tick = 10 分钟, 系统启动 = Tick 0, 每天第 60 Tick 下班. "
                        + "用于判断现在是上班时间还是下班时间, 或距离下班还有多久.",
                TalkToolkit.emptySchema(), getTime);
        tk.addPythonTool("take_rest",
                "休息工具. 调用后立即进入休息状态 (ON_DUTY_IDLE), 不需要指定时长. "
                        + "休息期间保持空闲, 不会自动唤醒; 当有任务或事件 (定时提醒/他人消息/上班) "
                        + "到来时会自动唤醒你. 适合在没有任务时使用.",
                TalkToolkit.emptySchema(), takeRest);
        return tk;
    }

    /** 将 TimeEventBus 绑定到时间工具类. */
    public static void bindTimeToToolkit(ToolKit toolkit, TimeEventBus manager, AgentRole role) {
        toolkit.bind("manager", manager);
        toolkit.bind("role", role);
    }
}
