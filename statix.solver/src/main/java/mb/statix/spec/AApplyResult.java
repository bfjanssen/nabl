package mb.statix.spec;

import java.util.Optional;

import org.immutables.serial.Serial;
import org.immutables.value.Value;

import mb.nabl2.terms.unification.u.IUnifier;
import mb.nabl2.terms.unification.ud.Diseq;
import mb.statix.solver.IConstraint;
import mb.statix.solver.IState;

@Value.Immutable
@Serial.Version(42L)
public abstract class AApplyResult {

    /**
     * The updated state.
     */
    @Value.Parameter public abstract IState.Immutable state();

    /**
     * All variables that were instantiated by this application.
     */
    @Value.Parameter public abstract IUnifier.Immutable diff();

    /**
     * Guard constraints necessary for this rule application. The domain of the guard are variables that pre-existed the
     * rule application. The guard is already applied in the state.
     */
    @Value.Parameter public abstract Optional<Diseq> guard();

    /**
     * The applied rule body.
     */
    @Value.Parameter public abstract IConstraint body();

}