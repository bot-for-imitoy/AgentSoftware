package com.agent.software.tools.toolkits.email;

import com.agent.software.role.Employee;
import com.agent.software.role.Role;
import com.agent.software.role.RolePool;
import com.agent.software.services.MailService;
import com.agent.software.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * send_email：给同事或客户发邮件。
 *
 * <p>收件人支持：role_id（COO）、显示名（Chen Zong）、姓名片段（Chen）、完整邮箱、client。
 * **解析不到就返回错误**——早期版本会把未知收件人拼成一个假地址（如 "chen zong@..."），
 * 邮件进了一个没人读的邮箱、收件人也不会被 NEW_MAIL 唤醒，而工具还报"发送成功"。
 */
public class SendEmail extends Tool {

    private final Role role;
    private final MailService mail;

    public SendEmail(Role role, MailService mail) {
        this.role = role;
        this.mail = mail;
    }

    @Override
    public String getToolName() {
        return "send_email";
    }

    @Override
    public Map<String, Object> getSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("to", "recipient role_id or display name (comma separated), e.g. \"COO\" or \"Chen Zong\"");
        schema.put("subject", "mail subject");
        schema.put("body", "mail body");
        schema.put("cc", "optional cc, comma separated");
        return schema;
    }

    @Override
    public String getDescription() {
        return "Send a company email to colleagues or the client (use role_id or display name).";
    }

    @Override
    public String handler(Map<String, Object> args) {
        if (role == null || mail == null) {
            return "send_email error: mail service unavailable";
        }
        List<String> unresolved = new ArrayList<>();
        List<String> to = resolve(args.get("to"), unresolved);
        List<String> cc = resolve(args.get("cc"), unresolved);
        if (!unresolved.isEmpty()) {
            return "send_email error: cannot resolve recipient(s) " + unresolved
                    + ". Use a role_id or display name; active colleagues: " + rosterHint();
        }
        if (to.isEmpty()) {
            return "send_email error: no recipients";
        }
        String subject = args.get("subject") == null ? "" : String.valueOf(args.get("subject"));
        String body = args.get("body") == null ? "" : String.valueOf(args.get("body"));
        String result = mail.send(mail.getAddress(role.roleId), to, cc, subject, body);
        role.journal("Sent mail: \"" + subject + "\" -> " + to);
        return result;
    }

    /** 支持 role_id / 显示名 / 姓名片段 / 邮箱 / client；无法解析的收集到 unresolved。 */
    private List<String> resolve(Object raw, List<String> unresolved) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : String.valueOf(raw).split(",")) {
            String v = part.trim();
            if (v.isEmpty()) {
                continue;
            }
            if (v.contains("@")) {
                out.add(v);
                continue;
            }
            if ("client".equalsIgnoreCase(v) || "client_a".equalsIgnoreCase(v)) {
                out.add(mail.getClientAddress());
                continue;
            }
            String addr = addressOf(v);
            if (addr != null) {
                out.add(addr);
            } else {
                unresolved.add(v);
            }
        }
        return out;
    }

    /** 先在大组里找，再在员工名单里找（假死员工也能收，只是不会被唤醒）。 */
    private String addressOf(String token) {
        if (role == null || role.getSystem() == null) {
            return null;
        }
        RolePool pool = role.getSystem().getRolePool();
        Role active = flexible(pool.all(), token);
        if (active != null) {
            return mail.getAddress(active.roleId);
        }
        List<Employee> roster = role.getSystem().getRoster().all();
        Employee emp = flexibleEmployee(roster, token);
        return emp == null ? null : mail.getAddress(emp.roleId);
    }

    private static Role flexible(List<Role> roles, String token) {
        for (Role r : roles) {
            if (token.equalsIgnoreCase(r.roleId)) {
                return r;
            }
        }
        for (Role r : roles) {
            if (token.equalsIgnoreCase(r.name)) {
                return r;
            }
        }
        List<Role> partial = new ArrayList<>();
        String needle = token.toLowerCase();
        for (Role r : roles) {
            String name = r.name == null ? "" : r.name.toLowerCase();
            if (name.startsWith(needle + " ") || name.endsWith(" " + needle)) {
                partial.add(r);
            }
        }
        return partial.size() == 1 ? partial.get(0) : null;
    }

    private static Employee flexibleEmployee(List<Employee> employees, String token) {
        for (Employee e : employees) {
            if (token.equalsIgnoreCase(e.roleId)) {
                return e;
            }
        }
        for (Employee e : employees) {
            if (token.equalsIgnoreCase(e.name)) {
                return e;
            }
        }
        List<Employee> partial = new ArrayList<>();
        String needle = token.toLowerCase();
        for (Employee e : employees) {
            String name = e.name == null ? "" : e.name.toLowerCase();
            if (name.startsWith(needle + " ") || name.endsWith(" " + needle)) {
                partial.add(e);
            }
        }
        return partial.size() == 1 ? partial.get(0) : null;
    }

    private String rosterHint() {
        if (role == null || role.getSystem() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Role r : role.getSystem().getRolePool().all()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(r.roleId).append(" (").append(r.name).append(')');
        }
        return sb.toString();
    }
}
