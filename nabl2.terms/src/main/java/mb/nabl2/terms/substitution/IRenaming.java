package mb.nabl2.terms.substitution;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import io.usethesource.capsule.util.stream.CapsuleCollectors;
import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ITermVar;

public interface IRenaming {

    boolean isEmpty();

    Set<ITermVar> keySet();

    Set<ITermVar> valueSet();

    Set<? extends Map.Entry<ITermVar, ITermVar>> entrySet();

    ITermVar rename(ITermVar term);

    default List<ITermVar> rename(List<ITermVar> terms) {
        final ImmutableList.Builder<ITermVar> vars = ImmutableList.builderWithExpectedSize(terms.size());
        for(ITermVar term : terms) {
            vars.add(rename(term));
        }
        return vars.build();
    }

    default Set<ITermVar> rename(Set<ITermVar> terms) {
        return terms.stream().map(this::rename).collect(CapsuleCollectors.toSet());
    }

    ITerm apply(ITerm term);

    default List<ITerm> apply(List<ITerm> terms) {
        final ImmutableList.Builder<ITerm> newTerms = ImmutableList.builderWithExpectedSize(terms.size());
        for(ITerm term : terms) {
            newTerms.add(apply(term));
        }
        return newTerms.build();
    }

    ISubstitution.Immutable asSubstitution();

    Map<ITermVar, ITermVar> asMap();

}