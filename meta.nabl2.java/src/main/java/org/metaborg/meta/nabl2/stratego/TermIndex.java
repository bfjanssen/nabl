package org.metaborg.meta.nabl2.stratego;

import static org.metaborg.meta.nabl2.terms.build.TermBuild.B;
import static org.metaborg.meta.nabl2.terms.matching.TermMatch.M;

import java.util.List;
import java.util.Optional;

import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.metaborg.meta.nabl2.terms.IApplTerm;
import org.metaborg.meta.nabl2.terms.ITerm;
import org.metaborg.meta.nabl2.terms.build.AbstractApplTerm;
import org.metaborg.meta.nabl2.terms.matching.TermMatch.IMatcher;

import com.google.common.collect.ClassToInstanceMap;
import com.google.common.collect.ImmutableList;

@Value.Immutable
@Serial.Version(value = 42L)
public abstract class TermIndex extends AbstractApplTerm implements ITermIndex, IApplTerm {

    private static final String OP = "TermIndex";

    // ITermIndex implementation

    @Value.Parameter public abstract String getResource();

    @Value.Parameter public abstract int getId();

    // IApplTerm implementation

    @Override public String getOp() {
        return OP;
    }

    @Value.Lazy @Override public List<ITerm> getArgs() {
        return ImmutableList.of(B.newString(getResource()), B.newInt(getId()));
    }

    public static IMatcher<TermIndex> matcher() {
        return M.preserveAttachments(M.appl2(OP, M.stringValue(), M.integerValue(),
                (t, resource, id) -> ImmutableTermIndex.of(resource, id)));
    }

    @Override protected TermIndex check() {
        return this;
    }

    // Object implementation

    @Override public boolean equals(Object other) {
        return super.equals(other);
    }

    @Override public int hashCode() {
        return super.hashCode();
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("@");
        sb.append(getResource());
        sb.append(":");
        sb.append(getId());
        return sb.toString();
    }

    // static

    public static Optional<TermIndex> get(ITerm term) {
        return get(term.getAttachments());
    }

    public static Optional<TermIndex> get(ClassToInstanceMap<Object> attachments) {
        return Optional.ofNullable(attachments.getInstance(TermIndex.class));
    }

}