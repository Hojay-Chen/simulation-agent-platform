package com.luxera.companion.runtime.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 技能加载器(§50): 从 classpath skills 目录下的 SKILL.md 文件加载技能。
 * id 为相对路径, 如 emotion.appraisal。
 */
@Slf4j
@Component
public class SkillLoader {

    /** 加载全部技能 */
    public List<Skill> loadAll() {
        List<Skill> skills = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:skills/**/SKILL.md");
            for (Resource r : resources) {
                String path = r.getURL().getPath();
                String id = toId(path);
                String name = id.substring(id.lastIndexOf('.') + 1);
                String content = read(r);
                skills.add(new Skill(id, name, content));
            }
        } catch (Exception e) {
            log.warn("[SkillLoader] 技能加载失败: {}", e.getMessage());
        }
        return skills;
    }

    private static String toId(String path) {
        // .../skills/core/identity/SKILL.md → core.identity
        int idx = path.lastIndexOf("/skills/");
        String rel = path.substring(idx + "/skills/".length());
        rel = rel.replace("/SKILL.md", "").replace("SKILL.md", "").replace('/', '.');
        rel = rel.endsWith(".") ? rel.substring(0, rel.length() - 1) : rel;
        return rel;
    }

    private static String read(Resource r) {
        try (InputStream is = r.getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
