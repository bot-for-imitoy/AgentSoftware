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

    /** 所有标识的公共只读视图。 */
    public interface Id {
        String value();
    }

    /** 角色标识（对应 master 的 role_id，也是容器/目录/uid 的派生源）。 */
    public record RoleId(String value) implements Id {
    }

    /** 任务标识。 */
    public record TaskId(String value) implements Id {
        /** 生成一个新的随机任务标识。 */
        public static TaskId generate() {
            throw new UnsupportedOperationException("skeleton");
        }
    }

    /** 事件标识。 */
    public record EventId(String value) implements Id {
        public static EventId generate() {
            throw new UnsupportedOperationException("skeleton");
        }
    }

    /** 定时表条目标识。 */
    public record ScheduleId(String value) implements Id {
        public static ScheduleId generate() {
            throw new UnsupportedOperationException("skeleton");
        }
    }

    /** 邮件标识。 */
    public record MailId(String value) implements Id {
        public static MailId generate() {
            throw new UnsupportedOperationException("skeleton");
        }
    }

    /** 待办标识。 */
    public record TodoId(String value) implements Id {
    }

    /** 技能标识。 */
    public record SkillId(String value) implements Id {
    }

    /** 笔记标识（标题即业务主键，id 仅用于持久化）。 */
    public record NoteId(String value) implements Id {
    }
}
