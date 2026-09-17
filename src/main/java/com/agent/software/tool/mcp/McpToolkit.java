package com.agent.software.tool.mcp;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.JsonSchema;
import com.agent.software.kernel.Payload;
import com.agent.software.tool.mcp.McpBridge.McpToolInfo;
import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.ToolResult;
import com.agent.software.tool.spi.ToolSpec;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * MCP 管理工具包（id {@code "mcp_manager"}），暴露工具：mcp_search / mcp_list / mcp_add / mcp_remove / mcp_my_tools。
 *
 * <p>与 master 的 {@code toolkits/mcp} 对应，但安装方向反过来了：master 的 {@code McpSearch}
 * 在"角色电脑上已连接的 MCP server"里搜，新架构里"有哪些可装"由
 * {@link McpBridge#search(String)} 从 {@code mcp_group_rules.json} 目录给出，
 * "装了什么"由 {@link McpBridge#installed(RoleId)} 按角色落盘记录。
 * 调用者身份直接取 {@link Tool#invoke(RoleId, Payload)} 传入的 roleId，工具包自身不含角色状态。
 */
public final class McpToolkit implements Toolkit {

    private final McpBridge mcp;

    public McpToolkit(McpBridge mcp) {
        this.mcp = mcp;
    }

    @Override
    public String id() {
        return "mcp_manager";
    }

    @Override
    public List<Tool> instantiate() {
        return List.of(new McpSearch(), new McpList(), new McpAdd(), new McpRemove(), new McpMyTools());
    }

    private ToolResult noBridge() {
        return ToolResult.error("未注入 MCP 桥（McpBridge），无法管理 MCP 工具。");
    }

    private static String format(List<McpToolInfo> tools) {
        StringBuilder sb = new StringBuilder();
        for (McpToolInfo tool : tools) {
            sb.append("- ").append(tool.name()).append(": ").append(tool.description()).append('\n');
        }
        return sb.toString().strip();
    }

    private static boolean missingAgent(RoleId agent) {
        return agent == null || agent.value().isBlank();
    }

    /** mcp_search：按关键词搜索可安装的 MCP 工具。 */
    private final class McpSearch implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("mcp_search",
                    "按关键词搜索可安装的 MCP 工具（在名称或描述中匹配），找到后用 mcp_add 安装。",
                    JsonSchema.object()
                            .string("keyword", "搜索关键词，例如 file / git / issue / read。")
                            .required("keyword"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (mcp == null) {
                return noBridge();
            }
            String keyword = arguments == null ? "" : arguments.stringOr("keyword", "").strip();
            if (keyword.isEmpty()) {
                return ToolResult.error("mcp_search 需要 keyword 参数。");
            }
            List<McpToolInfo> hits = mcp.search(keyword);
            if (hits.isEmpty()) {
                return ToolResult.ok("mcp_search: 没有匹配 '" + keyword + "' 的 MCP 工具。可用 mcp_list 查看全部。");
            }
            return ToolResult.ok("mcp_search: 匹配 '" + keyword + "' 的工具 " + hits.size() + " 个：\n" + format(hits));
        }
    }

    /** mcp_list：列出可安装的 MCP 工具全量。 */
    private final class McpList implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("mcp_list",
                    "列出当前可用于安装的全部 MCP 工具（名称 + 描述）。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (mcp == null) {
                return noBridge();
            }
            List<McpToolInfo> all = mcp.search("");
            if (all.isEmpty()) {
                return ToolResult.ok("mcp_list: 暂无可用 MCP 工具（mcp_group_rules.json 未加载或为空）。");
            }
            return ToolResult.ok("mcp_list: 共 " + all.size() + " 个可安装的 MCP 工具：\n" + format(all));
        }
    }

    /** mcp_add：把一个 MCP 工具安装到当前调用者的电脑。 */
    private final class McpAdd implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("mcp_add",
                    "把一个 MCP 工具安装到自己的电脑上；安装后可在后续任务中直接调用。",
                    JsonSchema.object()
                            .string("tool_name", "要安装的 MCP 工具名，例如 read_file。")
                            .required("tool_name"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (mcp == null) {
                return noBridge();
            }
            if (missingAgent(agent)) {
                return ToolResult.error("mcp_add 缺少调用者角色。");
            }
            String name = arguments == null ? "" : arguments.stringOr("tool_name", "").strip();
            if (name.isEmpty()) {
                return ToolResult.error("mcp_add 需要 tool_name 参数。");
            }
            mcp.install(agent, name);
            return ToolResult.ok("mcp_add: 已把工具 '" + name + "' 记录为安装到 " + agent.value()
                    + "（真正的工具挂载由电脑侧后续扩展完成）。");
        }
    }

    /** mcp_remove：从当前调用者的电脑卸载一个 MCP 工具。 */
    private final class McpRemove implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("mcp_remove",
                    "从自己的电脑上卸载一个已安装的 MCP 工具。",
                    JsonSchema.object()
                            .string("tool_name", "要卸载的 MCP 工具名。")
                            .required("tool_name"));
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (mcp == null) {
                return noBridge();
            }
            if (missingAgent(agent)) {
                return ToolResult.error("mcp_remove 缺少调用者角色。");
            }
            String name = arguments == null ? "" : arguments.stringOr("tool_name", "").strip();
            if (name.isEmpty()) {
                return ToolResult.error("mcp_remove 需要 tool_name 参数。");
            }
            mcp.uninstall(agent, name);
            return ToolResult.ok("mcp_remove: 已从 " + agent.value() + " 卸载工具 '" + name + "'。");
        }
    }

    /** mcp_my_tools：查看当前调用者已安装的 MCP 工具。 */
    private final class McpMyTools implements Tool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("mcp_my_tools",
                    "查看自己已经安装的 MCP 工具清单。",
                    JsonSchema.object());
        }

        @Override
        public ToolResult invoke(RoleId agent, Payload arguments) {
            if (mcp == null) {
                return noBridge();
            }
            if (missingAgent(agent)) {
                return ToolResult.error("mcp_my_tools 缺少调用者角色。");
            }
            List<McpToolInfo> mine = mcp.installed(agent);
            if (mine.isEmpty()) {
                return ToolResult.ok("mcp_my_tools: 你还没有安装任何 MCP 工具。可用 mcp_search / mcp_list 查找，再用 mcp_add 安装。");
            }
            return ToolResult.ok("mcp_my_tools: 你已安装 " + mine.size() + " 个 MCP 工具：\n" + format(mine));
        }
    }
}
