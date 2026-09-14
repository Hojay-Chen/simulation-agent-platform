package com.luxera.companion.digitalhuman.expression;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * V10 §5 Human-likeness 配置开关(每项独立可开关)。
 */
@Data
@Component
@ConfigurationProperties("app.v10.human-likeness")
public class HumanLikenessProperties {

    private boolean typingRhythm = true;
    private boolean typos = true;
    private boolean hesitation = true;
    private boolean memoryDrift = true;
    private boolean physioFilter = true;
    private boolean personaVoice = true;
    private boolean personaFingerprint = true;
}