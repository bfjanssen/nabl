package org.metaborg.meta.nabl2.spoofax.analysis;

import java.util.Optional;
import java.util.Set;

import org.immutables.serial.Serial;
import org.immutables.value.Value;
import org.metaborg.meta.nabl2.constraints.Constraints;
import org.metaborg.meta.nabl2.constraints.IConstraint;
import org.metaborg.meta.nabl2.stratego.ConstraintTerms;
import org.metaborg.meta.nabl2.terms.ITerm;
import org.metaborg.meta.nabl2.terms.matching.Match.IMatcher;
import org.metaborg.meta.nabl2.terms.matching.Match.M;

import com.google.common.collect.ImmutableSet;

@Value.Immutable
@Serial.Version(value = 42L)
public abstract class UnitResult {

    @Value.Parameter public abstract ITerm getAST();

    @Value.Parameter public abstract Set<IConstraint> getConstraints();

    @Value.Auxiliary public abstract Optional<ITerm> getCustomResult();

    public abstract UnitResult withCustomResult(Optional<? extends ITerm> customResult);

    public static IMatcher<UnitResult> matcher() {
        return M.appl2("UnitResult", M.term(), ConstraintTerms.specialize(Constraints.matchConstraintOrList()),
                (t, ast, constraint) -> {
                    return ImmutableUnitResult.of(ast, ImmutableSet.of(constraint));
                });
    }

}