package org.metaborg.meta.nabl2.constraints;

import static org.metaborg.meta.nabl2.terms.build.TermBuild.B;
import static org.metaborg.meta.nabl2.terms.matching.TermMatch.M;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.metaborg.meta.nabl2.constraints.ast.AstConstraints;
import org.metaborg.meta.nabl2.constraints.base.BaseConstraints;
import org.metaborg.meta.nabl2.constraints.base.CConj;
import org.metaborg.meta.nabl2.constraints.controlflow.ControlFlowConstraints;
import org.metaborg.meta.nabl2.constraints.equality.EqualityConstraints;
import org.metaborg.meta.nabl2.constraints.nameresolution.NameResolutionConstraints;
import org.metaborg.meta.nabl2.constraints.poly.PolyConstraints;
import org.metaborg.meta.nabl2.constraints.relations.RelationConstraints;
import org.metaborg.meta.nabl2.constraints.scopegraph.ScopeGraphConstraints;
import org.metaborg.meta.nabl2.constraints.sets.SetConstraints;
import org.metaborg.meta.nabl2.constraints.sym.SymbolicConstraints;
import org.metaborg.meta.nabl2.terms.ITerm;
import org.metaborg.meta.nabl2.terms.matching.TermMatch.IMatcher;
import org.metaborg.meta.nabl2.terms.unification.IUnifier;

public class Constraints {

    // TODO Remove after bootstrapping
    public static IMatcher<IConstraint> matchConstraintOrList() {
        return M.cases(
            M.listElems(Constraints.matcher(), (l, cs) -> CConj.of(cs)),
            Constraints.matcher()
        );
    }

    public static IMatcher<IConstraint> matcher() {
        return M.req("Not a constraint", M.<IConstraint>cases(
            // @formatter:off
            AstConstraints.matcher(),
            BaseConstraints.matcher(),
            EqualityConstraints.matcher(),
            ScopeGraphConstraints.matcher(),
            NameResolutionConstraints.matcher(),
            RelationConstraints.matcher(),
            SetConstraints.matcher(),
            SymbolicConstraints.matcher(),
            PolyConstraints.matcher(),
            ControlFlowConstraints.matcher()
            // @formatter:on
        ));
    }

    public static IMatcher<Integer> priorityMatcher() {
        return M.string(s -> s.getValue().length());
    }

    public static ITerm build(IConstraint constraint) {
        return constraint.match(IConstraint.Cases.<ITerm>of(
        // @formatter:off
            AstConstraints::build,
            BaseConstraints::build,
            EqualityConstraints::build,
            ScopeGraphConstraints::build,
            NameResolutionConstraints::build,
            RelationConstraints::build,
            SetConstraints::build,
            SymbolicConstraints::build,
            PolyConstraints::build,
            ControlFlowConstraints::build
            // @formatter:on
        ));
    }

    public static ITerm build(Collection<IConstraint> constraints) {
        List<ITerm> constraintTerms = constraints.stream().map(Constraints::build).collect(Collectors.toList());
        return B.newAppl("Constraints", (ITerm) B.newList(constraintTerms));
    }

    public static ITerm buildPriority(int prio) {
        return B.newString(String.join("", Collections.nCopies(prio, "!")));
    }

    public static IConstraint substitute(IConstraint constraint, IUnifier.Immutable subst) {
        return subst.isEmpty() ? constraint : constraint.match(IConstraint.Cases.<IConstraint>of(
        // @formatter:off
            c -> AstConstraints.substitute(c, subst),
            c -> BaseConstraints.substitute(c, subst),
            c -> EqualityConstraints.substitute(c, subst),
            c -> ScopeGraphConstraints.substitute(c, subst),
            c -> NameResolutionConstraints.substitute(c, subst),
            c -> RelationConstraints.substitute(c, subst),
            c -> SetConstraints.substitute(c, subst),
            c -> SymbolicConstraints.substitute(c, subst),
            c -> PolyConstraints.substitute(c, subst),
            c -> ControlFlowConstraints.substitute(c, subst)
            // @formatter:on
        ));
    }

}