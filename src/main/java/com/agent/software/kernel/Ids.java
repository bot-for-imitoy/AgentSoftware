package com.agent.software.kernel;

/**
 * 领域标识（Identity）集合。
 *
 * <p>规则：标识只包装一个非空字符串，全部为不可变 {@code record}（值语义由 record 自动提供）；
 * 跨层传递必须使用具体标识类型，禁止裸 {@code String} 冒充 id。
 *
 * <p>骨架说明：紧凑构造器中的非空校验、{@code generate()} 的随机生成逻辑留待实现。
 */
public final class Ids {

    private Ids() {
    }

    /** 12 位十六进制随机标识（对齐 master 的 {@code UUID.randomUUID().replace("-","").substring(0,12)}）。 */
    static String random() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    static String require(String value, String kind) {
        if (value == null || value.isBlank()) {
            throw new DomainError("id." + kind + ".blank", kind + " 标识不能为空");
        }
        return value;
    }

    /** 所有标识的公共只读视图。 */
    public interface Id {
        String value();
    }

    /** 角色标识（对应 master 的 role_id，也是容器/目录/uid 的派生源）。 */
    public record RoleId(String value) implements Id {
        public RoleId {
            value = require(value, "role");
        }
    }

    /** 任务标识。 */
    public record TaskId(String value) implements Id {
        public TaskId {
            value = require(value, "task");
        }

        /** 生成一个新的随机任务标识。 */
        public static TaskId generate() {
            return new TaskId(random());
        }
    }

    /** 事件标识。 */
    public record EventId(String value) implements Id {
        public EventId {
            value = require(value, "event");
        }

        public static EventId generate() {
            return new EventId(random());
        }
    }

    /** 定时表条目标识。 */
    public record ScheduleId(String value) implements Id {
        public ScheduleId {
            value = require(value, "schedule");
        }

        public static ScheduleId generate() {
            return new ScheduleId(random());
        }
    }

    /** 邮件标识。 */
    public record MailId(String value) implements Id {
        public MailId {
            value = require(value, "mail");
        }

        public static MailId generate() {
            return new MailId(random());
        }
    }

    /** 待办标识。 */
    public record TodoId(String value) implements Id {
        public TodoId {
            value = require(value, "todo");
        }
    }

    /** 技能标识。 */
    public record SkillId(String value) implements Id {
        public SkillId {
            value = require(value, "skill");
        }
    }

    /** 笔记标识（标题即业务主键，id 仅用于持久化）。 */
    public record NoteId(String value) implements Id {
        public NoteId {
            value = require(value, "note");
        }
    }
}
