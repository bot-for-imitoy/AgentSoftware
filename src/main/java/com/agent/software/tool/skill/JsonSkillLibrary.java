package com.agent.software.tool.skill;

import com.agent.software.infra.config.AppPaths;
import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.SkillId;

import java.util.List;

/**
 * 目录扫描形态的技能库：解析 SKILL.md 得到技能，并记录角色授权关系。
 */
public final class JsonSkillLibrary implements SkillLibrary {

    /** 绑定数据路径（技能目录与授权文件位置）。 */
    public JsonSkillLibrary(AppPaths paths) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Skill> available() {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Skill> search(String keyword) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public List<Skill> ownedBy(RoleId owner) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void grant(RoleId owner, SkillId id) {
        throw new UnsupportedOperationException("skeleton");
    }

    @Override
    public void revoke(RoleId owner, SkillId id) {
        throw new UnsupportedOperationException("skeleton");
    }
}
