package mb.statix.solver.persistent;

import static mb.nabl2.terms.build.TermBuild.B;
import static mb.nabl2.terms.matching.TermMatch.M;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.metaborg.util.functions.Predicate2;
import org.metaborg.util.log.Level;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import mb.nabl2.terms.ITerm;
import mb.nabl2.terms.ITermVar;
import mb.nabl2.terms.stratego.TermIndex;
import mb.nabl2.terms.stratego.TermOrigin;
import mb.nabl2.terms.substitution.ISubstitution;
import mb.nabl2.terms.substitution.PersistentSubstitution;
import mb.nabl2.terms.unification.IUnifier;
import mb.nabl2.terms.unification.IUnifier.Immutable.Result;
import mb.nabl2.terms.unification.OccursException;
import mb.nabl2.util.ImmutableTuple2;
import mb.nabl2.util.Tuple2;
import mb.statix.constraints.CAstId;
import mb.statix.constraints.CAstProperty;
import mb.statix.constraints.CConj;
import mb.statix.constraints.CEqual;
import mb.statix.constraints.CExists;
import mb.statix.constraints.CFalse;
import mb.statix.constraints.CInequal;
import mb.statix.constraints.CNew;
import mb.statix.constraints.CResolveQuery;
import mb.statix.constraints.CTellEdge;
import mb.statix.constraints.CTellRel;
import mb.statix.constraints.CTrue;
import mb.statix.constraints.CUser;
import mb.statix.scopegraph.INameResolution;
import mb.statix.scopegraph.IScopeGraph;
import mb.statix.scopegraph.path.IResolutionPath;
import mb.statix.scopegraph.reference.CriticalEdge;
import mb.statix.scopegraph.reference.IncompleteDataException;
import mb.statix.scopegraph.reference.IncompleteEdgeException;
import mb.statix.scopegraph.reference.ResolutionException;
import mb.statix.scopegraph.terms.AScope;
import mb.statix.scopegraph.terms.Scope;
import mb.statix.solver.ConstraintContext;
import mb.statix.solver.Delay;
import mb.statix.solver.IConstraint;
import mb.statix.solver.IConstraintStore;
import mb.statix.solver.SolverException;
import mb.statix.solver.SolverException.SolverInterrupted;
import mb.statix.solver.completeness.ICompleteness;
import mb.statix.solver.completeness.IncrementalCompleteness;
import mb.statix.solver.completeness.IsComplete;
import mb.statix.solver.log.IDebugContext;
import mb.statix.solver.log.LazyDebugContext;
import mb.statix.solver.log.Log;
import mb.statix.solver.log.NullDebugContext;
import mb.statix.solver.persistent.query.ConstraintQueries;
import mb.statix.solver.query.IQueryFilter;
import mb.statix.solver.query.IQueryMin;
import mb.statix.solver.query.ResolutionDelayException;
import mb.statix.solver.store.BaseConstraintStore;
import mb.statix.spec.Rule;
import mb.statix.spoofax.StatixTerms;

public class StepSolver implements IConstraint.CheckedCases<Optional<StepResult>, SolverException> {

    private State state;
    private Map<ITermVar, ITermVar> existentials;
    private final IsComplete isComplete;
    private final ICompleteness completeness;
    private final ConstraintContext params;

    private final IDebugContext debug;
    private final LazyDebugContext proxyDebug;
    private final IDebugContext subDebug;

    public StepSolver(State state, IsComplete _isComplete, IDebugContext debug) {
        this.state = state;
        this.existentials = null;
        this.completeness = new IncrementalCompleteness(state.spec());
        this.isComplete = (s, l, st) -> completeness.isComplete(s, l, st.unifier()) && _isComplete.test(s, l, st);
        this.debug = debug;
        this.proxyDebug = new LazyDebugContext(debug);
        this.subDebug = proxyDebug.subContext();
        this.params = new ConstraintContext(this.isComplete, subDebug);
    }

    public SolverResult solve(final IConstraint initialConstraint) throws InterruptedException {
        debug.info("Solving constraints");

        // set-up
        completeness.add(initialConstraint, state.unifier());
        final IConstraintStore constraints = new BaseConstraintStore(debug);
        constraints.add(initialConstraint);

        // time log
        final Map<Class<? extends IConstraint>, Long> successCount = Maps.newHashMap();
        final Map<Class<? extends IConstraint>, Long> delayCount = Maps.newHashMap();

        // fixed point
        final List<IConstraint> failed = new ArrayList<>();
        final Log delayedLog = new Log();
        boolean progress = true;
        int reductions = 0;
        int delays = 0;
        outer: while(progress) {
            progress = false;
            delayedLog.clear();
            IConstraint constraint;
            while((constraint = constraints.remove()) != null) {
                if(Thread.interrupted()) {
                    throw new InterruptedException();
                }
                if(proxyDebug.isEnabled(Level.Info)) {
                    proxyDebug.info("Solving {}", constraint.toString(Solver.shallowTermFormatter(state.unifier())));
                }
                try {
                    final Optional<StepResult> maybeResult;
                    maybeResult = step(constraint);
                    addTime(constraint, 1, successCount, debug);
                    progress = true;
                    reductions += 1;
                    if(maybeResult.isPresent()) {
                        final StepResult result = maybeResult.get();
                        state = result.state();
                        if(existentials == null) {
                            existentials = result.existentials();
                        }
                        final IUnifier.Immutable unifier = state.unifier();

                        // updates from unified variables
                        completeness.updateAll(result.updatedVars(), state.unifier());
                        constraints.activateFromVars(result.updatedVars(), subDebug);

                        // add new constraints
                        constraints.addAll(result.newConstraints());
                        completeness.addAll(result.newConstraints(), unifier); // must come before ICompleteness::remove
                        if(!result.newConstraints().isEmpty()) {
                            subDebug.info("Simplified to:");
                            for(IConstraint newConstraint : result.newConstraints()) {
                                subDebug.info(" * {}", Solver.toString(newConstraint, unifier));
                            }
                        }

                        // add delayed constraints
                        result.delayedConstraints().forEach((d, c) -> constraints.delay(c, d));
                        completeness.addAll(result.delayedConstraints().values(), unifier); // must come before ICompleteness::remove
                        if(!result.delayedConstraints().isEmpty()) {
                            subDebug.info("Delayed:");
                            for(IConstraint delayedConstraint : result.delayedConstraints().values()) {
                                if(subDebug.isEnabled(Level.Info)) {
                                    subDebug.info(" * {}", Solver.toString(delayedConstraint, state.unifier()));
                                }
                            }
                        }
                    } else {
                        subDebug.error("Failed");
                        failed.add(constraint);
                        if(proxyDebug.isRoot()) {
                            Solver.printTrace(constraint, state.unifier(), subDebug);
                        } else {
                            proxyDebug.info("Break early because of errors.");
                            break outer;
                        }
                    }
                    // remove current constraint
                    final Set<CriticalEdge> removedEdges = completeness.remove(constraint, state.unifier());
                    constraints.activateFromEdges(removedEdges, subDebug);
                    proxyDebug.commit();
                } catch(Delay d) {
                    addTime(constraint, 1, delayCount, debug);
                    subDebug.info("Delayed");
                    delayedLog.absorb(proxyDebug.clear());
                    constraints.delay(constraint, d);
                    delays += 1;
                }
            }
        }

        // invariant: there should be no remaining active constraints
        if(constraints.activeSize() > 0) {
            debug.warn("Expected no remaining active constraints, but got ", constraints.activeSize());
        }

        final Map<IConstraint, Delay> delayed = constraints.delayed();
        delayedLog.flush(debug);
        debug.info("Solved {} constraints ({} delays) with {} failed, and {} remaining constraint(s).", reductions,
                delays, failed.size(), constraints.delayedSize());
        logTimes("success", successCount, debug);
        logTimes("delay", delayCount, debug);

        final Map<ITermVar, ITermVar> existentials = Optional.ofNullable(this.existentials).orElse(ImmutableMap.of());
        return SolverResult.of(state, failed, delayed, existentials);
    }

    private static void addTime(IConstraint c, long dt, Map<Class<? extends IConstraint>, Long> times,
            IDebugContext debug) {
        if(!debug.isEnabled(Level.Info)) {
            return;
        }
        final Class<? extends IConstraint> key = c.getClass();
        final long t = times.getOrDefault(key, 0L).longValue() + dt;
        times.put(key, t);
    }

    private static void logTimes(String name, Map<Class<? extends IConstraint>, Long> times, IDebugContext debug) {
        debug.info("# ----- {} -----", name);
        for(Map.Entry<Class<? extends IConstraint>, Long> entry : times.entrySet()) {
            debug.info("{} : {}x", entry.getKey().getSimpleName(), entry.getValue());
        }
        debug.info("# ----- {} -----", "-");
    }

    private Optional<StepResult> step(final IConstraint constraint) throws Delay, InterruptedException {
        try {
            return constraint.matchOrThrow(this);
        } catch(SolverException ex) {
            ex.rethrow();
            throw new IllegalStateException("something should have been thrown");
        }
    }

    @Override public Optional<StepResult> caseConj(CConj c) throws SolverException {
        // @formatter:off
        final List<IConstraint> newConstraints = ImmutableList.of(
                c.left().withCause(c.cause().orElse(null)),
                c.right().withCause(c.cause().orElse(null)));
        // @formatter:on
        return Optional.of(StepResult.ofNew(state, newConstraints));
    }

    @Override public Optional<StepResult> caseEqual(CEqual c) throws SolverException {
        final ITerm term1 = c.term1();
        final ITerm term2 = c.term2();
        IDebugContext debug = params.debug();
        IUnifier.Immutable unifier = state.unifier();
        try {
            final Result<Tuple2<IUnifier.Immutable, Collection<Tuple2<ITermVar, ITerm>>>> result;
            if((result = unifier.unify(term1, term2, v -> params.isRigid(v, state)).orElse(null)) != null) {
                if(debug.isEnabled(Level.Info)) {
                    debug.info("Unification succeeded: {}", result.result());
                }
                final State newState = state.withUnifier(result.unifier());
                final Set<ITermVar> updatedVars = result.result()._1().varSet();
                final Map<Delay, IConstraint> delayedConstraints =
                        Solver.rigidsToDelays(result.result()._2(), c.cause());
                return Optional.of(StepResult.of(newState, updatedVars, ImmutableList.of(), delayedConstraints,
                        ImmutableMap.of()));
            } else {
                if(debug.isEnabled(Level.Info)) {
                    debug.info("Unification failed: {} != {}", unifier.toString(term1), unifier.toString(term2));
                }
                return Optional.empty();
            }
        } catch(OccursException e) {
            if(debug.isEnabled(Level.Info)) {
                debug.info("Unification failed: {} != {}", unifier.toString(term1), unifier.toString(term2));
            }
            return Optional.empty();
        }
    }

    @Override public Optional<StepResult> caseExists(CExists c) throws SolverException {
        final ImmutableMap.Builder<ITermVar, ITermVar> existentialsBuilder = ImmutableMap.builder();
        State newState = state;
        for(ITermVar var : c.vars()) {
            final Tuple2<ITermVar, State> varAndState = newState.freshVar(var.getName());
            final ITermVar freshVar = varAndState._1();
            newState = varAndState._2();
            existentialsBuilder.put(var, freshVar);
        }
        final Map<ITermVar, ITermVar> existentials = existentialsBuilder.build();
        final ISubstitution.Immutable subst = PersistentSubstitution.Immutable.of(existentials);
        final IConstraint newConstraint = c.constraint().apply(subst).withCause(c.cause().orElse(null));
        return Optional.of(StepResult.of(newState, ImmutableSet.of(), ImmutableList.of(newConstraint),
                ImmutableMap.of(), existentials));
    }

    @Override public Optional<StepResult> caseFalse(CFalse c) throws SolverException {
        return Optional.empty();
    }

    @Override public Optional<StepResult> caseInequal(CInequal c) throws SolverException {
        final ITerm term1 = c.term1();
        final ITerm term2 = c.term2();

        final IUnifier unifier = state.unifier();
        return unifier.areEqual(term1, term2).matchOrThrow(result -> {
            if(result) {
                return Optional.empty();
            } else {
                return Optional.of(StepResult.of(state));
            }
        }, vars -> {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(vars), c));
        });
    }

    @Override public Optional<StepResult> caseNew(CNew c) throws SolverException {
        final List<ITerm> terms = c.terms();

        final List<IConstraint> newConstraints = Lists.newArrayList();
        State newState = state;
        for(ITerm t : terms) {
            final String base = M.var(ITermVar::getName).match(t).orElse("s");
            Tuple2<Scope, State> ss = newState.freshScope(base);
            newConstraints.add(new CEqual(t, ss._1(), c));
            newState = ss._2();
        }
        return Optional.of(StepResult.ofNew(newState, newConstraints));
    }

    @Override public Optional<StepResult> caseResolveQuery(CResolveQuery c) throws SolverException {
        final ITerm relation = c.relation();
        final IQueryFilter filter = c.filter();
        final IQueryMin min = c.min();
        final ITerm scopeTerm = c.scopeTerm();
        final ITerm resultTerm = c.resultTerm();

        final IUnifier unifier = state.unifier();
        if(!unifier.isGround(scopeTerm)) {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(unifier.getVars(scopeTerm)), c));
        }
        final Scope scope = AScope.matcher().match(scopeTerm, unifier)
                .orElseThrow(() -> new IllegalArgumentException("Expected scope, got " + unifier.toString(scopeTerm)));

        try {
            final IDebugContext subDebug = new NullDebugContext(params.debug().getDepth() + 1);
            final Predicate2<Scope, ITerm> isComplete = (s, l) -> {
                if(params.isComplete(s, l, state)) {
                    subDebug.info("{} complete in {}", s, l);
                    return true;
                } else {
                    subDebug.info("{} incomplete in {}", s, l);
                    return false;
                }
            };
            final ConstraintQueries cq = new ConstraintQueries(state, params);
            // @formatter:off
            final INameResolution<Scope, ITerm, ITerm> nameResolution = Solver.nameResolutionBuilder()
                        .withLabelWF(cq.getLabelWF(filter.getLabelWF()))
                        .withDataWF(cq.getDataWF(filter.getDataWF()))
                        .withLabelOrder(cq.getLabelOrder(min.getLabelOrder()))
                        .withDataEquiv(cq.getDataEquiv(min.getDataEquiv()))
                        .withEdgeComplete(isComplete)
                        .withDataComplete(isComplete)
                        .build(state.scopeGraph(), relation);
            // @formatter:on
            final Set<IResolutionPath<Scope, ITerm, ITerm>> paths = nameResolution.resolve(scope);
            final List<ITerm> pathTerms =
                    paths.stream().map(StatixTerms::explicate).collect(ImmutableList.toImmutableList());
            final IConstraint C = new CEqual(B.newList(pathTerms), resultTerm, c);
            return Optional.of(StepResult.ofNew(state, ImmutableList.of(C)));
        } catch(IncompleteDataException e) {
            params.debug().info("Query resolution delayed: {}", e.getMessage());
            return Optional.of(
                    StepResult.ofDelay(state, Delay.ofCriticalEdge(CriticalEdge.of(e.scope(), e.relation())), c));
        } catch(IncompleteEdgeException e) {
            params.debug().info("Query resolution delayed: {}", e.getMessage());
            return Optional.of(
                    StepResult.ofDelay(state, Delay.ofCriticalEdge(CriticalEdge.of(e.scope(), e.label())), c));
        } catch(ResolutionDelayException e) {
            params.debug().info("Query resolution delayed: {}", e.getMessage());
            return Optional.of(StepResult.ofDelay(state, e.getCause(), c));
        } catch(ResolutionException e) {
            params.debug().info("Query resolution failed: {}", e.getMessage());
            return Optional.empty();
        } catch(InterruptedException e) {
            throw new SolverInterrupted(e);
        }
    }

    @Override public Optional<StepResult> caseTellEdge(CTellEdge c) throws SolverException {
        final ITerm sourceTerm = c.sourceTerm();
        final ITerm label = c.label();
        final ITerm targetTerm = c.targetTerm();

        final IUnifier unifier = state.unifier();
        if(!unifier.isGround(sourceTerm)) {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(unifier.getVars(sourceTerm)), c));
        }
        if(!unifier.isGround(targetTerm)) {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(unifier.getVars(targetTerm)), c));
        }
        final Scope source = AScope.matcher().match(sourceTerm, unifier).orElseThrow(
                () -> new IllegalArgumentException("Expected source scope, got " + unifier.toString(sourceTerm)));
        if(params.isClosed(source, state)) {
            return Optional.empty();
        }
        final Scope target = AScope.matcher().match(targetTerm, unifier).orElseThrow(
                () -> new IllegalArgumentException("Expected target scope, got " + unifier.toString(targetTerm)));
        final IScopeGraph.Immutable<Scope, ITerm, ITerm> scopeGraph = state.scopeGraph().addEdge(source, label, target);
        return Optional.of(StepResult.of(state.withScopeGraph(scopeGraph)));
    }

    @Override public Optional<StepResult> caseTellRel(CTellRel c) throws SolverException {
        final ITerm scopeTerm = c.scopeTerm();
        final ITerm relation = c.relation();
        final ITerm datumTerm = c.datumTerm();

        final IUnifier unifier = state.unifier();
        if(!unifier.isGround(scopeTerm)) {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(unifier.getVars(scopeTerm)), c));
        }
        final Scope scope = AScope.matcher().match(scopeTerm, unifier)
                .orElseThrow(() -> new IllegalArgumentException("Expected scope, got " + unifier.toString(scopeTerm)));
        if(params.isClosed(scope, state)) {
            return Optional.empty();
        }

        final IScopeGraph.Immutable<Scope, ITerm, ITerm> scopeGraph =
                state.scopeGraph().addDatum(scope, relation, datumTerm);
        return Optional.of(StepResult.of(state.withScopeGraph(scopeGraph)));
    }

    @Override public Optional<StepResult> caseTermId(CAstId c) throws SolverException {
        final ITerm term = c.astTerm();
        final ITerm idTerm = c.idTerm();

        final IUnifier unifier = state.unifier();
        if(!(unifier.isGround(term))) {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(unifier.getVars(term)), c));
        }
        final CEqual eq;
        final Optional<Scope> maybeScope = AScope.matcher().match(term, unifier);
        if(maybeScope.isPresent()) {
            final AScope scope = maybeScope.get();
            eq = new CEqual(idTerm, scope);
            return Optional.of(StepResult.ofNew(state, ImmutableList.of(eq)));
        } else {
            final Optional<TermIndex> maybeIndex = TermIndex.get(unifier.findTerm(term));
            if(maybeIndex.isPresent()) {
                final ITerm indexTerm = TermOrigin.copy(term, maybeIndex.get());
                eq = new CEqual(idTerm, indexTerm);
                return Optional.of(StepResult.ofNew(state, ImmutableList.of(eq)));
            } else {
                return Optional.empty();
            }
        }
    }

    @Override public Optional<StepResult> caseTermProperty(CAstProperty c) throws SolverException {
        final ITerm idTerm = c.idTerm();
        final ITerm prop = c.property();
        final ITerm value = c.value();

        final IUnifier unifier = state.unifier();
        if(!(unifier.isGround(idTerm))) {
            return Optional.of(StepResult.ofDelay(state, Delay.ofVars(unifier.getVars(idTerm)), c));
        }
        final Optional<TermIndex> maybeIndex = TermIndex.matcher().match(idTerm, unifier);
        if(maybeIndex.isPresent()) {
            final TermIndex index = maybeIndex.get();
            final Tuple2<TermIndex, ITerm> key = ImmutableTuple2.of(index, prop);
            if(!state.termProperties().containsKey(key)) {
                final ImmutableMap.Builder<Tuple2<TermIndex, ITerm>, ITerm> props = ImmutableMap.builder();
                props.putAll(state.termProperties());
                props.put(key, value);
                final State newState = state.withTermProperties(props.build());
                return Optional.of(StepResult.of(newState));
            } else {
                return Optional.empty();
            }
        } else {
            return Optional.empty();
        }
    }

    @Override public Optional<StepResult> caseTrue(CTrue c) throws SolverException {
        return Optional.of(StepResult.of(state));
    }

    @Override public Optional<StepResult> caseUser(CUser c) throws SolverException {
        final String name = c.name();
        final List<ITerm> args = c.args();

        final IDebugContext debug = params.debug();
        final List<Rule> rules = Lists.newLinkedList(state.spec().rules().get(name));
        final Log unsuccessfulLog = new Log();
        final Iterator<Rule> it = rules.iterator();
        while(it.hasNext()) {
            if(Thread.interrupted()) {
                throw new SolverInterrupted(new InterruptedException());
            }
            final LazyDebugContext proxyDebug = new LazyDebugContext(debug);
            final Rule rawRule = it.next();
            if(proxyDebug.isEnabled(Level.Info)) {
                proxyDebug.info("Try rule {}", rawRule.toString());
            }
            final IConstraint instantiatedBody;
            try {
                if((instantiatedBody = rawRule.apply(args, state.unifier(), c).orElse(null)) == null) {
                    proxyDebug.info("Rule rejected (mismatching arguments)");
                    unsuccessfulLog.absorb(proxyDebug.clear());
                    continue;
                }
            } catch(Delay d) {
                proxyDebug.info("Rule delayed (unsolved guard constraint)");
                unsuccessfulLog.absorb(proxyDebug.clear());
                unsuccessfulLog.flush(debug);
                return Optional.of(StepResult.ofDelay(state, d, c));
            }
            proxyDebug.info("Rule accepted");
            proxyDebug.commit();
            return Optional.of(StepResult.ofNew(state, ImmutableList.of(instantiatedBody)));
        }
        debug.info("No rule applies");
        unsuccessfulLog.flush(debug);
        return Optional.empty();
    }

}