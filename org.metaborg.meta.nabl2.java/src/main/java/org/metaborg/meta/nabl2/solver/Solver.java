package org.metaborg.meta.nabl2.solver;

import java.time.Duration;

import org.metaborg.meta.nabl2.constraints.IConstraint;
import org.metaborg.meta.nabl2.constraints.IConstraint.CheckedCases;
import org.metaborg.meta.nabl2.scopegraph.terms.ResolutionParameters;
import org.metaborg.meta.nabl2.terms.ITerm;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;

public class Solver {

    private static final ILogger logger = LoggerUtils.logger(Solver.class);

    private final BaseSolver baseSolver;
    private final EqualitySolver equalitySolver;
    private final NamebindingSolver namebindingSolver;

    private final Multimap<ITerm,String> errors;
    private final Multimap<ITerm,String> warnings;
    private final Multimap<ITerm,String> notes;

    private Solver(ResolutionParameters resolutionParams) {
        this.baseSolver = new BaseSolver();
        this.equalitySolver = new EqualitySolver();
        this.namebindingSolver = new NamebindingSolver(resolutionParams, equalitySolver);

        this.errors = HashMultimap.create();
        this.warnings = HashMultimap.create();
        this.notes = HashMultimap.create();
    }

    private void add(Iterable<IConstraint> constraints) {
        for (IConstraint constraint : constraints) {
            try {
                constraint.matchOrThrow(CheckedCases.of(baseSolver::add, equalitySolver::add, namebindingSolver::add));
            } catch (UnsatisfiableException e) {
                addErrors(e);
            }
        }
    }

    private void iterate() {
        boolean progress;
        do {
            progress = false;
            progress |= baseSolver.iterate();
            try {
                progress |= equalitySolver.iterate();
                progress |= namebindingSolver.iterate();
            } catch (UnsatisfiableException e) {
                progress = true;
                addErrors(e);
            }
        } while (progress);
    }

    private void finish() {
        baseSolver.finish();
        try {
            equalitySolver.finish();
        } catch (UnsatisfiableException e) {
            addErrors(e);
        }
        try {
            namebindingSolver.finish();
        } catch (UnsatisfiableException e) {
            addErrors(e);
        }
    }

    private void addErrors(UnsatisfiableException e) {
        for (IConstraint c : e.getUnsatCore()) {
            c.getOriginatingTerm().ifPresent(t -> {
                errors.put(t, e.getMessage());
            });
        }
    }

    public static Solution solve(ResolutionParameters resolutionParams, Iterable<IConstraint> constraints)
            throws UnsatisfiableException {
        long t0 = System.nanoTime();
        logger.info(">>> Solving constraints <<<");
        Solver solver = new Solver(resolutionParams);
        solver.add(constraints);
        solver.iterate();
        solver.finish();
        long dt = System.nanoTime() - t0;
        logger.info(">>> Solved constraints ({} s) <<<", (Duration.ofNanos(dt).toMillis() / 1000.0));
        return ImmutableSolution.of(solver.namebindingSolver.getScopeGraph(), solver.namebindingSolver
                .getNameResolution(), solver.namebindingSolver.getProperties(), solver.errors, solver.warnings,
                solver.notes);
    }

}