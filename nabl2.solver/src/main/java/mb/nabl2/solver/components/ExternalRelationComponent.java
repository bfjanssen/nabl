package mb.nabl2.solver.components;

import mb.nabl2.constraints.equality.ImmutableCEqual;
import mb.nabl2.constraints.relations.CBuildRelation;
import mb.nabl2.constraints.relations.CCheckRelation;
import mb.nabl2.constraints.relations.CEvalFunction;
import mb.nabl2.constraints.relations.IRelationConstraint;
import mb.nabl2.relations.IFunctionName;
import mb.nabl2.solver.ASolver;
import mb.nabl2.solver.ISolver.SolveResult;
import mb.nabl2.solver.exceptions.DelayException;
import mb.nabl2.solver.exceptions.UnconditionalDelayExpection;
import mb.nabl2.solver.exceptions.VariableDelayException;
import mb.nabl2.solver.SolverCore;
import mb.nabl2.terms.ITerm;


public class ExternalRelationComponent extends ASolver {

    public ExternalRelationComponent(SolverCore core) {
        super(core);
    }

    public SolveResult solve(IRelationConstraint constraint) throws DelayException {
        return constraint.matchOrThrow(IRelationConstraint.CheckedCases.of(this::solve, this::solve, this::solve));
    }

    // ------------------------------------------------------------------------------------------------------//

    public SolveResult solve(CBuildRelation c) throws DelayException {
        throw new UnconditionalDelayExpection();
    }

    public SolveResult solve(CCheckRelation c) throws DelayException {
        throw new UnconditionalDelayExpection();
    }

    public SolveResult solve(CEvalFunction c) throws DelayException {
        if(!unifier().isGround(c.getTerm())) {
            throw new VariableDelayException(unifier().getVars(c.getTerm()));
        }
        final ITerm term = unifier().findRecursive(c.getTerm());
        return c.getFunction().matchOrThrow(IFunctionName.CheckedCases.of(
        // @formatter:off
            name -> {
                throw new UnconditionalDelayExpection();
            },
            extName -> {
                return callExternal(extName, term).map(ret -> {
                    return SolveResult.constraints(ImmutableCEqual.of(c.getResult(), ret, c.getMessageInfo()));
                }).orElse(SolveResult.empty());
            }
            // @formatter:on
        ));
    }

}