package mb.statix.spoofax;

import static mb.nabl2.terms.build.TermBuild.B;
import static mb.nabl2.terms.matching.TermMatch.M;

import java.util.List;
import java.util.Optional;

import org.metaborg.util.functions.Function1;
import org.spoofax.interpreter.core.IContext;
import org.spoofax.interpreter.core.InterpreterException;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.matching.TermMatch.IMatcher;
import mb.statix.constraints.CExists;
import mb.statix.solver.IConstraint;
import mb.statix.solver.log.IDebugContext;
import mb.statix.solver.persistent.Solver;
import mb.statix.solver.persistent.SolverResult;
import mb.statix.solver.persistent.State;
import mb.statix.spec.Spec;
import mb.statix.taico.util.TDebug;
import mb.statix.taico.util.TOverrides;
import mb.statix.taico.util.TTimings;

public class STX_solve_constraint extends StatixPrimitive {

    @Inject public STX_solve_constraint() {
        super(STX_solve_constraint.class.getSimpleName(), 2);
    }
    
    @Override
    protected Optional<? extends ITerm> _call(IContext env, ITerm term, List<ITerm> terms) throws InterpreterException {
        //TODO Temporary override for convenience
        if (TOverrides.MODULES_OVERRIDE) {
            System.err.println("Running modularized solver!");
            ITerm newTerm = M.tuple2(M.term(), M.term(), (a, t1, t2) -> B.newTuple(B.newString("?"), t1, t2)).match(term).get();
            return new MSTX_solve_constraint()._call(env, newTerm, terms);
        }
        
        TTimings.startNewRun();
        TTimings.addDetails("STX_solve_constraint Settings: <%s>, Debug: <%s>, Input: <%s>", TOverrides.print(), TDebug.print(), term);
        TTimings.startPhase("STX_solve_constraint");
        
        try {
            return super._call(env, term, terms);
        } finally {
            TTimings.endPhase("STX_solve_constraint");
        }
    }

    @Override protected Optional<? extends ITerm> call(IContext env, ITerm term, List<ITerm> terms)
            throws InterpreterException {
        TTimings.startPhase("init");
        final Spec spec =
                StatixTerms.spec().match(terms.get(0)).orElseThrow(() -> new InterpreterException("Expected spec."));
        reportOverlappingRules(spec);

        final IDebugContext debug = getDebugContext(terms.get(1));

        final IMatcher<IConstraint> constraintMatcher = M.tuple2(M.listElems(StatixTerms.varTerm()),
                StatixTerms.constraint(), (t, vs, c) -> new CExists(vs, c));
        final Function1<IConstraint, ITerm> solveConstraint = constraint -> solveConstraint(spec, constraint, debug);
        TTimings.endPhase("init");
        // @formatter:off
        return M.cases(
            constraintMatcher.map(solveConstraint::apply),
            M.listElems(constraintMatcher).map(vars_constraints -> {
                return B.newList(vars_constraints.stream().parallel().map(solveConstraint::apply).collect(ImmutableList.toImmutableList()));
            })
        ).match(term);
        // @formatter:on
    }

    private ITerm solveConstraint(Spec spec, IConstraint constraint, IDebugContext debug) {
        final State state = State.of(spec);

        final SolverResult resultConfig;
        try {
            resultConfig = Solver.solve(state, constraint, debug);
        } catch(InterruptedException e) {
            throw new RuntimeException(e);
        }

        final ITerm substTerm =
                StatixTerms.explicateMapEntries(resultConfig.existentials().entrySet(), resultConfig.state().unifier());
        final ITerm solverTerm = B.newBlob(resultConfig);
        final ITerm resultTerm = B.newAppl("Solution", substTerm, solverTerm);

        return resultTerm;
    }

}
