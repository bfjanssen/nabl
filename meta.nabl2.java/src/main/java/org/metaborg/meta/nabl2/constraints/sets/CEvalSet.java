package org.metaborg.meta.nabl2.constraints.sets;

import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.metaborg.meta.nabl2.constraints.IConstraint;
import org.metaborg.meta.nabl2.constraints.messages.IMessageContent;
import org.metaborg.meta.nabl2.constraints.messages.IMessageInfo;
import org.metaborg.meta.nabl2.constraints.messages.MessageContent;
import org.metaborg.meta.nabl2.terms.ITerm;

@Value.Immutable
@Serial.Version(value = 42L)
public abstract class CEvalSet implements ISetConstraint {

    @Value.Parameter public abstract ITerm getResult();

    @Value.Parameter public abstract ITerm getSet();

    @Value.Parameter @Override public abstract IMessageInfo getMessageInfo();

    @Override public <T> T match(Cases<T> cases) {
        return cases.caseEval(this);
    }

    @Override public <T> T match(IConstraint.Cases<T> cases) {
        return cases.caseSet(this);
    }

    @Override public <T, E extends Throwable> T matchOrThrow(CheckedCases<T, E> cases) throws E {
        return cases.caseEval(this);
    }

    @Override public <T, E extends Throwable> T matchOrThrow(IConstraint.CheckedCases<T, E> cases) throws E {
        return cases.caseSet(this);
    }

    @Override public IMessageContent pp() {
        return MessageContent.builder().append(getResult()).append(" is set ").append(getSet()).build();
    }

    @Override public String toString() {
        return pp().toString();
    }

}