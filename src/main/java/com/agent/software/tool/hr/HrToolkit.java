package com.agent.software.tool.hr;

import com.agent.software.tool.spi.Tool;
import com.agent.software.tool.spi.Toolkit;

import java.util.List;

/**
 * 招聘工具包（id {@code "hr"}），暴露工具：post_job_posting / list_candidates。
 */
public final class HrToolkit implements Toolkit {

    private final Recruiter recruiter;

    public HrToolkit(Recruiter recruiter) {
        this.recruiter = recruiter;
    }

    @Override
    public String id() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Tool> instantiate() {
        throw new UnsupportedOperationException("skeleton");
    }
}
