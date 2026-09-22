package com.agent.software.tools.toolkits.staffing;

import com.agent.software.role.Role;
import com.agent.software.tools.Toolkit;

/** COO 专用调度工具包：draft_in / draft_out / list_employees / list_active。 */
public class StaffingToolkit extends Toolkit {

    private final Role role;

    public StaffingToolkit(Role role) {
        this.role = role;
        addTool(new DraftIn(role));
        addTool(new DraftOut(role));
        addTool(new ListEmployees(role));
        addTool(new ListActive(role));
    }

    @Override
    public String getDescription() {
        return "Staffing (COO only): draft employees into the cohort or remove them, list roster / active team";
    }
}
