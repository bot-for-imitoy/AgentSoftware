package com.agent.software.ports;

import com.agent.software.kernel.Ids.RoleId;
import com.agent.software.kernel.Ids.SkillId;
import com.agent.software.model.Skill;

import java.util.List;

/** 技能库能力（扫描 SKILL.md 目录；技能按角色授权）。 */
public interface SkillLibrary {

    List<Skill> available();

    List<Skill> search(String keyword);

    List<Skill> ownedBy(RoleId owner);

    void grant(RoleId owner, SkillId id);

    void revoke(RoleId owner, SkillId id);
}
