package com.luxera.companion.digitalhuman.conversation;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OutputValidationChain: 输出验证链(Chain of Responsibility)。
 *
 * 组合多个 ConversationOutputValidator, 按注册顺序执行;
 * 任一验证器失败即返回失败(短路), 保证输出通过所有质量闸门。
 * @Primary: 作为系统默认的输出验证入口(内部再按顺序跑各规则验证器)。
 */
@Primary
@Component
public class OutputValidationChain implements ConversationOutputValidator {

    private final List<ConversationOutputValidator> validators;

    public OutputValidationChain(List<ConversationOutputValidator> validators) {
        this.validators = List.copyOf(validators);
    }

    @Override
    public String validate(ChatMessageDraft draft) {
        for (ConversationOutputValidator validator : validators) {
            String reason = validator.validate(draft);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    public int size() {
        return validators.size();
    }
}
