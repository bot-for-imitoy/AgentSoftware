package com.agent.software.client;

import com.agent.software.utils.Data;
import com.agent.software.utils.UUIDObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 甲方客户：真人，不是员工。
 *
 * <p>它只有身份 + 一个邮箱 + 一条当前会话（见 {@link ClientChannel}），
 * 不需要 Context/LLM。
 */
public final class Client extends UUIDObject implements Data {

    public String clientId;
    public String name;
    public String email;

    public Client(String clientId, String name, String email) {
        super();
        this.clientId = clientId == null ? "CLIENT" : clientId;
        this.name = name == null ? "Client" : name;
        this.email = email == null ? "" : email;
    }

    public Client(String uuid, String clientId, String name, String email) {
        super(uuid);
        this.clientId = clientId == null ? "CLIENT" : clientId;
        this.name = name == null ? "Client" : name;
        this.email = email == null ? "" : email;
    }

    @Override
    public Map<String, String> getData() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("uuid", uuid);
        d.put("client_id", clientId);
        d.put("name", name);
        d.put("email", email);
        return d;
    }

    @Override
    public void loadData(Map<String, String> data) {
        if (data == null) {
            return;
        }
        if (data.containsKey("uuid")) {
            this.uuid = data.get("uuid");
        }
        this.clientId = data.getOrDefault("client_id", this.clientId);
        this.name = data.getOrDefault("name", this.name);
        this.email = data.getOrDefault("email", this.email);
    }

    @Override
    public String toString() {
        return "Client(" + clientId + ", " + name + ", " + email + ")";
    }
}
