package mb.p_raffrayi.impl.diff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.metaborg.util.RefBool;
import org.metaborg.util.collection.CapsuleUtil;
import org.metaborg.util.functions.Action1;
import org.metaborg.util.functions.Function1;
import org.metaborg.util.future.AggregateFuture;
import org.metaborg.util.future.CompletableFuture;
import org.metaborg.util.future.Futures;
import org.metaborg.util.future.ICompletable;
import org.metaborg.util.future.ICompletableFuture;
import org.metaborg.util.future.IFuture;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.tuple.Tuple2;
import org.metaborg.util.unit.Unit;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.Streams;

import mb.p_raffrayi.TypeCheckingFailedException;
import mb.scopegraph.oopsla20.IScopeGraph;
import mb.scopegraph.oopsla20.diff.Edge;
import mb.scopegraph.oopsla20.diff.ScopeGraphDiff;
import mb.scopegraph.oopsla20.reference.EdgeOrData;
import mb.scopegraph.patching.IPatchCollection;

public class ScopeGraphDiffer<S, L, D> implements IScopeGraphDiffer<S, L, D> {

    private static final ILogger logger = LoggerUtils.logger(ScopeGraphDiffer.class);

    private final IDifferContext<S, L, D> currentContext;
    private final IDifferContext<S, L, D> previousContext;
    private final IDifferOps<S, L, D> differOps;
    private final Set<L> edgeLabels;

    private final CompletableFuture<ScopeGraphDiff<S, L, D>> result = new CompletableFuture<>();
    private Throwable failure;

    // Intermediate match results

    private final mb.scopegraph.oopsla20.diff.BiMap.Transient<S> matchedScopes =
            mb.scopegraph.oopsla20.diff.BiMap.Transient.of();
    private final mb.scopegraph.oopsla20.diff.BiMap.Transient<Edge<S, L>> matchedEdges =
            mb.scopegraph.oopsla20.diff.BiMap.Transient.of();

    private final Multimap<Tuple2<S, L>, Edge<S, L>> addedEdges = HashMultimap.create();
    private final Multimap<Tuple2<S, L>, Edge<S, L>> matchedOutgoingEdges = HashMultimap.create();
    private final Multimap<Tuple2<S, L>, Edge<S, L>> removedEdges = HashMultimap.create();

    private final Map<S, Optional<D>> currentScopeData = new HashMap<>();
    private final Map<S, Optional<D>> previousScopeData = new HashMap<>();

    // Final match results

    private final Set<S> addedScopes = new HashSet<>();
    private final Set<S> removedScopes = new HashSet<>();

    // Observations

    private final Set<S> seenCurrentScopes = new HashSet<>();
    private final Set<S> openCurrentScopes = new HashSet<>();

    private final Set<S> seenPreviousScopes = new HashSet<>();
    private final Set<S> openPreviousScopes = new HashSet<>();

    private final Multimap<S, L> completedPreviousEdges = HashMultimap.create();

    // Delays

    /**
     * Delays to be fired when the previous scope key is matched (or marked as removed).
     */
    private final Multimap<S, ICompletable<Optional<S>>> previousScopeProcessedDelays = HashMultimap.create();

    /**
     * Delays to be fired when the previous scope key is completed (i.e. all outgoing edges are matched or removed).
     */
    private final Multimap<Tuple2<S, L>, ICompletable<Unit>> previousScopeCompletedDelays = HashMultimap.create();

    /**
     * Delays to be fired when edge in current scope graph is matched/added.
     */
    private final Multimap<Edge<S, L>, ICompletable<Unit>> currentEdgeCompleteDelays = HashMultimap.create();

    // private final Set<IToken<S, L>> waitFors = new HashSet<>();

    // Internal state maintenance

    private final AtomicInteger pendingResults = new AtomicInteger(0);
    private final AtomicBoolean inFixedPoint = new AtomicBoolean(false);
    private final AtomicBoolean typeCheckerFinished = new AtomicBoolean(false);

    private final Queue<EdgeMatch> edgeMatches = new PriorityQueue<>();

    public ScopeGraphDiffer(IDifferContext<S, L, D> context, IDifferContext<S, L, D> previousContext,
            IDifferOps<S, L, D> differOps, Set<L> edgeLabels) {
        this.currentContext = context;
        this.previousContext = previousContext;
        this.differOps = differOps;
        this.edgeLabels = edgeLabels;
    }

    ///////////////////////////////////////////////////////////////////////////
    // External API
    // * Can be accessed to start or finish the differ, or to read its state.
    ///////////////////////////////////////////////////////////////////////////

    @Override public IFuture<ScopeGraphDiff<S, L, D>> diff(List<S> currentRootScopes, List<S> previousRootScopes) {
        try {
            logger.debug("Start scope graph differ");
            if(currentRootScopes.size() != previousRootScopes.size()) {
                logger.error("Current and previous root scope number differ.");
                return CompletableFuture.completedExceptionally(
                        new IllegalStateException("Current and previous root scope number differ."));
            }

            final BiMap<S, S> rootMatches = HashBiMap.create();
            for(int i = 0; i < currentRootScopes.size(); i++) {
                rootMatches.put(currentRootScopes.get(i), previousRootScopes.get(i));
            }

            // Calculate matches caused by root scope matches
            BiMap<S, S> initialMatches;
            if((initialMatches = consistent(rootMatches).orElse(null)) == null) {
                logger.error("Current and previous root scope number differ.");
                return CompletableFuture
                        .completedExceptionally(new IllegalStateException("Provided root scopes cannot be matched."));
            }

            List<IFuture<Unit>> futures = new ArrayList<>();
            initialMatches.entrySet().forEach(e -> {
                S current = e.getKey();
                S previous = e.getValue();
                scheduleCurrentData(current);
                schedulePreviousData(previous);

                match(current, previous);
                ICompletableFuture<Unit> future = new CompletableFuture<>();
                consequences(current, previous).whenComplete((cOpt, ex) -> {
                    if(ex != null) {
                        failure(ex);
                        return;
                    }
                    if(!cOpt.isPresent()) {
                        logger.error("Root match internally inconsistent: {} ~ {}.", current, previous);
                        future.completeExceptionally(new TypeCheckingFailedException(
                                "Root match internally inconsistent: " + current + " ~ " + previous));
                        return;
                    }
                    Optional<BiMap<S, S>> matchesOpt = consistent(cOpt.get());
                    if(!matchesOpt.isPresent()) {
                        logger.error("Root match inconsistent: {} ~ {}.", current, previous);
                        future.completeExceptionally(new TypeCheckingFailedException(
                                "Root match inconsistent: " + current + " ~ " + previous));
                    }
                    matchesOpt.get().forEach(this::match);
                    future.complete(Unit.unit);
                });
                futures.add(future);
            });

            AggregateFuture.of(futures).whenComplete((__, ex) -> {
                if(ex != null) {
                    failure(ex);
                }

                logger.debug("Scheduled initial matches");
                fixedpoint();
            });
        } catch(Throwable ex) {
            logger.error("Differ initialization failed.", ex);
            failure(ex);
        }
        return result;
    }

    @Override public IFuture<ScopeGraphDiff<S, L, D>> diff(IScopeGraph.Immutable<S, L, D> scopeGraph,
            Collection<S> scopes, Collection<S> sharedScopes, IPatchCollection.Immutable<S> patches,
            Collection<S> openScopes, Multimap<S, EdgeOrData<L>> openEdges) {
        logger.debug("Initializing differ from initial graph.");
        logger.trace("* scopes:      {}.", scopes);
        logger.trace("* scopeGraph:  {}.", scopeGraph);
        logger.trace("* patches:     {}.", patches);
        logger.trace("* open scopes: {}.", openScopes);
        logger.trace("* open edges:  {}.", openEdges);

        try {
            for(S oldScope : scopes) {
                S newScope = patches.patch(oldScope);
                seenCurrentScopes.add(newScope);
                seenPreviousScopes.add(oldScope);
                matchedScopes.put(newScope, oldScope);
                logger.trace("Initial scope match: {] ~ {}.", newScope, oldScope);
            }

            for(S oldScope : scopes) {
                logger.trace("Matching initially matched edges for {}.", oldScope);
                if(differOps.ownOrSharedScope(oldScope)) {
                    final S newScope = patches.patch(oldScope);

                    for(L lbl : edgeLabels) {
                        final Set<S> oldTargets = ImmutableSet.copyOf(scopeGraph.getEdges(oldScope, lbl));
                        final Set<S> newTargets = oldTargets.stream().map(patches::patch).collect(Collectors.toSet());

                        for(S oldTarget : oldTargets) {
                            final S newTarget = patches.patch(oldTarget);
                            final Edge<S, L> oldEdge = new Edge<>(oldScope, lbl, oldTarget);
                            final Edge<S, L> newEdge = new Edge<>(newScope, lbl, newTarget);
                            matchedEdges.put(newEdge, oldEdge);
                            matchedOutgoingEdges.put(Tuple2.of(oldScope, lbl), oldEdge);
                            logger.trace("Initial edge match: {} ~ {}.", newEdge, oldEdge);
                        }

                        if(openScopes.contains(oldScope) || openEdges.containsEntry(oldScope, EdgeOrData.edge(lbl))
                                || sharedScopes.contains(oldScope)) {
                            logger.trace("Edge {}/{} open, scheduling residual matches.", oldScope, lbl);
                            IFuture<Iterable<S>> currentResidualTargetsFuture =
                                    currentContext.getEdges(newScope, lbl).thenApply(targets -> {
                                        return CapsuleUtil.toSet(targets).__removeAll(newTargets);
                                    });

                            IFuture<Iterable<S>> previousResidualTargetsFuture =
                                    previousContext.getEdges(oldScope, lbl).thenApply(targets -> {
                                        return CapsuleUtil.toSet(targets).__removeAll(oldTargets);
                                    });

                            future(finishEdgeMatches(newScope, oldScope, lbl, currentResidualTargetsFuture,
                                    previousResidualTargetsFuture));
                        } else {
                            logger.trace("Edge {}/{} not open, mark as completed.", oldScope, lbl);
                            completedPreviousEdges.put(oldScope, lbl);
                        }
                    }
                }
            }

            fixedpoint();
        } catch(Throwable ex) {
            logger.error("Differ initialization failed.", ex);
            failure(ex);
        }
        return result;
    }

    @Override public boolean matchScopes(mb.scopegraph.oopsla20.diff.BiMap.Immutable<S> scopes) {
        return matchScopes(scopes.asMap());
    }

    private boolean matchScopes(Map<S, S> scopes) {
        if(scopes.isEmpty()) {
            return true;
        }
        logger.debug("Matching scopes {}.", scopes);
        scopes.keySet().forEach(this::scheduleCurrentData);
        scopes.values().forEach(this::schedulePreviousData);

        final BiMap<S, S> newMatches;
        if((newMatches = consistent(scopes).orElse(null)) == null) {
            logger.trace("Scopes cannot match.");
            return false;
        }

        logger.trace("Matching {} succeeded.", scopes);
        for(Map.Entry<S, S> entry : newMatches.entrySet()) {
            match(entry.getKey(), entry.getValue());
        }
        return true;
    }

    @Override public void typeCheckerFinished() {
        typeCheckerFinished.set(true);
        fixedpoint();
    }

    ///////////////////////////////////////////////////////////////////////////
    // Driver
    ///////////////////////////////////////////////////////////////////////////

    private void fixedpoint() {
        logger.trace("Calculating fixpoint");
        // Due to synchronously completing futures, we can have nested fixpoint calls
        // We only complete outer calls (either when finished directly from a call to diff(),
        // or after an asynchronous future completion via diffK.
        if(inFixedPoint.getAndSet(true)) {
            return;
        }

        try {
            try {
                do {
                    while(!edgeMatches.isEmpty()) {
                        final EdgeMatch m = edgeMatches.remove();
                        matchEdge(m.currentEdge, m.previousEdges);
                    }

                    logger.trace("Reached fixpoint. Pending: {}", pendingResults.get());
                    tryFinalizeDiff();
                } while(!edgeMatches.isEmpty());
            } finally {
                inFixedPoint.set(false);
            }
        } catch(Throwable ex) {
            logger.error("Error computing fixedpoint.", ex);
            failure(ex);
        }
    }

    private <R> void diffK(K<R> k, R r, Throwable ex) {
        logger.trace("Continuing");
        try {
            k.k(r, ex);
            fixedpoint();
        } catch(Throwable e) {
            logger.error("Continuation terminated unexpectedly.", e);
            failure(e);
        }
        logger.trace("Finished continuation");
    }

    ///////////////////////////////////////////////////////////////////////////
    // Persistent Matching
    // * State-sensitive, synchronous part of the algorithm
    ///////////////////////////////////////////////////////////////////////////

    private Unit matchEdge(Edge<S, L> currentEdge, ImmutableMap<Edge<S, L>, BiMap<S, S>> previousEdges) {
        logger.debug("{}: matching with candidates {}", currentEdge, previousEdges);

        for(Map.Entry<Edge<S, L>, BiMap<S, S>> previousEdge : previousEdges.entrySet()) {
            if(matchScopes(previousEdge.getValue())) {
                logger.trace("{}: matched with {}.", currentEdge, previousEdge);
                return match(currentEdge, previousEdge.getKey());
            } else {
                logger.trace("{}: matching with {} failed.", currentEdge, previousEdge);
            }
        }
        return added(currentEdge);
    }

    /**
     * Indicates whether a set of scopes can be matched consistently with the current state, but without taking scope
     * data into account.
     *
     * Used when scheduling edge matches, and providing external matches.
     */
    private Optional<BiMap<S, S>> consistent(Map<S, S> scopes) {
        final BiMap<S, S> newMatches = HashBiMap.create();

        for(Map.Entry<S, S> entry : scopes.entrySet()) {
            final S currentScope = entry.getKey();
            final S previousScope = entry.getValue();
            if(!differOps.isMatchAllowed(currentScope, previousScope)) {
                logger.trace("{} ~ {}: matching not allowed by context.", currentScope, previousScope);
                return Optional.empty();
            } else if(!matchedScopes.canPut(currentScope, previousScope)) {
                logger.trace("{} ~ {}: matching not allowed: one or both is already matched.", currentScope,
                        previousScope);
                return Optional.empty();
            } else if(matchedScopes.containsEntry(currentScope, previousScope)) {
                // skip this pair as it was already matched
            } else {
                newMatches.put(currentScope, previousScope);
            }
        }
        logger.trace("Scopes {} match.", scopes);
        return Optional.of(newMatches);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Transient Matching
    // * State-agnostic, asynchronous part of the algorithm
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Compute the patch required to match two scopes and their data.
     */
    private IFuture<Optional<BiMap<S, S>>> consequences(S currentScope, S previousScope) {
        final BiMap<S, S> matches = HashBiMap.create();
        return consequences(currentScope, previousScope, matches)
                .thenApply(b -> b ? Optional.of(matches) : Optional.empty());
    }

    /**
     * Computes matchability of two scopes, without taking the current state into account. Additional implied matches
     * are collected in the {@code req} argument.
     */
    private IFuture<Boolean> consequences(S currentScope, S previousScope, BiMap<S, S> req) {
        if(!differOps.isMatchAllowed(currentScope, previousScope)) {
            return CompletableFuture.completedFuture(false);
        }
        if(previousScope.equals(req.get(currentScope))) {
            return CompletableFuture.completedFuture(true);
        }
        if(req.containsKey(currentScope) || req.containsValue(previousScope)) {
            return CompletableFuture.completedFuture(false);
        }
        req.put(currentScope, previousScope);

        if(differOps.ownScope(currentScope)) {
            return requiredScopeMatches(currentScope, previousScope, req);
        } else {
            if(removedScopes.contains(previousScope)) {
                return CompletableFuture.completedFuture(false);
            }
            if(matchedScopes.containsValue(previousScope)) {
                return CompletableFuture.completedFuture(matchedScopes.getValue(previousScope).equals(currentScope));
            }
            // We do not own the scope, hence ask owner to which current scope it is matched.
            return waitFor(differOps.externalMatch(previousScope).thenApply(match -> {
                if(!match.isPresent()) {
                    removed(previousScope);
                    return false;
                }

                final S target = match.get();
                logger.trace("{} ~ {}: rec external match.", target, previousScope);
                // Insert new remote match
                match(target, previousScope);
                return target.equals(currentScope);
            }));
        }
    }

    private IFuture<Boolean> requiredScopeMatches(S currentScope, S previousScope, final BiMap<S, S> req) {
        // Match data of own scope
        final IFuture<Optional<D>> currentDatumFuture =
                waitFor(currentContext.datum(currentScope)).whenComplete((d, ex) -> {
                    logger.trace("{} (C): rec datum: {}.", currentScope, d);
                });
        final IFuture<Optional<D>> previousDatumFuture =
                waitFor(previousContext.datum(previousScope)).whenComplete((d, ex) -> {
                    logger.trace("{} (P): rec datum: {}.", previousScope, d);
                });

        return AggregateFuture.apply(currentDatumFuture, previousDatumFuture).thenCompose(r -> {
            logger.trace("{} ~ {}: decide match with data: {} ~ {}.", currentScope, previousScope, r._1(), r._2());
            final Optional<D> currentData = r._1();
            final Optional<D> previousData = r._2();
            if(currentData.isPresent() != previousData.isPresent()) {
                logger.trace("{} ~ {}: cannot match: different data availability.", currentScope, previousScope);
                return CompletableFuture.completedFuture(false);
            } else if(currentData.isPresent() && previousData.isPresent()) {
                // Scopes with data can only match if data match
                // Calculate immediate consequences of data match
                final BiMap<S, S> newMatches = HashBiMap.create();
                final boolean dataMatch =
                        differOps.matchDatums(currentData.get(), previousData.get()).map(scopeMatches -> {
                            for(Map.Entry<S, S> match : scopeMatches.asMap().entrySet()) {
                                final S current = match.getKey();
                                final S previous = match.getValue();
                                if(previous.equals(req.get(current))) {
                                    continue;
                                }
                                if(req.containsKey(current) || req.containsValue(previous)) {
                                    return false;
                                }
                                req.put(current, previous);
                                newMatches.put(current, previous);
                            }
                            return true;
                        }).orElse(false);
                if(!dataMatch) {
                    logger.trace("{} ~ {}: cannot match: data matches inconsistent with accumulated scope matches.",
                            currentScope, previousScope);
                    return CompletableFuture.completedFuture(false);
                }
                logger.trace("{} ~ {}: data match, calculating consequent matches.", currentScope, previousScope);
                // @formatter:off
                // Calculate transitive closure of consequences.
                return Futures.reduce(
                    // Use current set of verified requirement as initial value.
                    true,
                    // calculate consequences for each match in data match
                    newMatches.entrySet(),
                    // for each match, calculate consequences, consistent with aggregates set of matches
                    (aggMatches, match) -> aggMatches ?
                            consequences(match.getKey(), match.getValue(), req) : CompletableFuture.completedFuture(false)
                );
                // @formatter:on
            }
            // Both scopes don't have data
            return CompletableFuture.completedFuture(true);
        });
    }

    /**
     * Schedule edge matches from the given source scopes.
     */
    private IFuture<Unit> scheduleEdgeMatches(S currentSource, S previousSource, L label) {
        // Result that is completed when all edges (both current and previous) are processed.
        logger.debug("{} ~ {}/{}: scheduling edge matches.", currentSource, previousSource, label);

        return finishEdgeMatches(currentSource, previousSource, label, currentContext.getEdges(currentSource, label),
                previousContext.getEdges(previousSource, label));
    }

    private IFuture<Unit> finishEdgeMatches(S currentSource, S previousSource, L label,
            final IFuture<Iterable<S>> currentTargetsFuture, final IFuture<Iterable<S>> previousTargetsFuture) {
        final ICompletableFuture<Unit> result = new CompletableFuture<>();

        // Get current edges for label
        final IFuture<Set<Edge<S, L>>> currentEdgesFuture = currentTargetsFuture.whenComplete((tgts, ex) -> {
            logger.trace("{}/{} (C): rec targets: {}.", currentSource, label, tgts);
        }).thenApply(currentTargetScopes -> Streams.stream(currentTargetScopes)
                .map(currentTarget -> new Edge<>(currentSource, label, currentTarget)).collect(Collectors.toSet()));

        // Get previous edges for label
        final IFuture<Set<Edge<S, L>>> previousEdgesFuture = previousTargetsFuture.whenComplete((tgts, ex) -> {
            logger.trace("{}/{} (P): rec targets: {}.", previousSource, label, tgts);
        }).thenApply(previousTargetScopes -> Streams.stream(previousTargetScopes)
                .map(previousTarget -> new Edge<>(previousSource, label, previousTarget)).collect(Collectors.toSet()));

        // Combine results
        final IFuture<Tuple2<Set<Edge<S, L>>, Set<Edge<S, L>>>> edgesFuture =
                AggregateFuture.apply(currentEdgesFuture, previousEdgesFuture);

        final K<Tuple2<Set<Edge<S, L>>, Set<Edge<S, L>>>> k = (res, ex) -> {
            if(ex != null) {
                result.completeExceptionally(ex);
                return Unit.unit;
            }
            Set<Edge<S, L>> currentEdges = res._1();
            Set<Edge<S, L>> previousEdges = res._2();

            // When all current edges are processed (matched/added), mark remaining previous edges as removed.
            scheduleRemovedEdges(previousSource, currentEdges, previousEdges).whenComplete((u, ex2) -> {
                logger.debug("{} ~ {}/{}: edge matches finished.", currentSource, previousSource, label);
                previousScopeComplete(previousSource, label);
                result.complete(u, ex2);
            });

            return processEdgeMatches(currentEdges, previousEdges);
        };

        future(edgesFuture, k);
        return result;
    }

    private Unit processEdgeMatches(Set<Edge<S, L>> currentEdges, Set<Edge<S, L>> previousEdges) {
        for(final Edge<S, L> edge : currentEdges) {
            final Edge<S, L> currentEdge = edge; // FIXME indirection needed for capture?
            // For each candidate edge, compute which scopes must be matched in order to make the edge match
            // This involves the target scopes, but also the scopes in the data of these target scopes.
            IFuture<List<Tuple2<Edge<S, L>, Optional<BiMap<S, S>>>>> matchesFuture =
                    aggregateAll(previousEdges, previousEdge -> {
                        ICompletableFuture<Tuple2<Edge<S, L>, Optional<BiMap<S, S>>>> result =
                                new CompletableFuture<>();
                        consequences(currentEdge.target, previousEdge.target).whenComplete((matchedScopes, ex) -> {
                            if(ex != null) {
                                logger.debug("Error computing consequences for {} ~ {}. Treat as not matchable.",
                                        currentEdge.target, previousEdge.target);
                                logger.debug("* Error.", ex);
                                result.complete(Tuple2.of(previousEdge, Optional.empty()));
                            } else {
                                result.complete(Tuple2.of(previousEdge, matchedScopes));
                            }
                        });
                        return result;
                    });

            // When match computation is complete, schedule edge matches for processing.
            K<List<Tuple2<Edge<S, L>, Optional<BiMap<S, S>>>>> k2 = (r, ex2) -> {
                if(ex2 != null) {
                    failure(ex2);
                    return Unit.unit;
                }
                logger.trace("{}: rec candidates: {}.", currentEdge, r);
                final ImmutableMap.Builder<Edge<S, L>, BiMap<S, S>> _matchingPreviousEdges = ImmutableMap.builder();
                // @formatter:off
                // filter out all previous edges that cannot be matched (indicated by empty optional)
                r.stream().filter(x -> x._2().isPresent())
                    // unwrap required matches
                    .map(x -> Tuple2.of(x._1(), x._2().get()))
                    // Check if required matches are consistent with current state and each other
                    .map(x -> Tuple2.of(x._1(), consistent(x._2())))
                    // Filter for consistent matches
                    .filter(x -> x._2().isPresent())
                    // Add all remaining candidates to set.
                    .forEach(x -> _matchingPreviousEdges.put(x._1(), x._2().get()));
                // @formatter:on
                final ImmutableMap<Edge<S, L>, BiMap<S, S>> matchingPreviousEdges = _matchingPreviousEdges.build();

                if(logger.traceEnabled()) {
                    logger.trace("{}: possible candidates: {}.", currentEdge, matchingPreviousEdges);
                }
                return queue(new EdgeMatch(currentEdge, matchingPreviousEdges));
            };

            final ICompletableFuture<List<Tuple2<Edge<S, L>, Optional<BiMap<S, S>>>>> matchesResult =
                    new CompletableFuture<>();
            matchesFuture.whenComplete((u, ex2) -> {
                if(ex2 != null) {
                    logger.debug("Error matching edge " + currentEdge + " - treat it as added.", ex2);
                    matchesResult.complete(Collections.emptyList(), null);
                } else {
                    matchesResult.complete(u);
                }
            });

            future(matchesResult, k2);
        }

        return Unit.unit;
    }

    /**
     * Ensures that, when all edges in {@code currentEdges} are matches/added, all unmatched edges in
     * {@code previousEdges} are marked as removed.
     */
    private IFuture<Unit> scheduleRemovedEdges(S previousScope, Set<Edge<S, L>> currentEdges,
            Set<Edge<S, L>> previousEdges) {
        ICompletableFuture<Unit> result = new CompletableFuture<>();

        // Invariant: for all previousEdges, the source should be previousScope
        IFuture<List<Unit>> allCurrentEdgesProcessed = aggregateAll(currentEdges, edge -> {
            ICompletableFuture<Unit> future = new CompletableFuture<>();
            if(!completeIfFailure(future)) {
                // waitFors.add(EdgeCompleted.of(edge));
                currentEdgeCompleteDelays.put(edge, future);
            }
            return future;
        });
        K<List<Unit>> processPreviousEdges = (__, ex) -> {
            previousEdges.forEach(edge -> {
                if(isPreviousEdgeOpen(edge)) {
                    removed(edge);
                }
                // TODO: assert previous edge closed.
            });
            logger.trace("{} (P): edge matches processed.", previousScope);
            result.complete(Unit.unit, ex);
            return Unit.unit;
        };
        future(allCurrentEdgesProcessed, processPreviousEdges);

        return result;
    }

    private void scheduleCurrentData(S currentScope) {
        if(differOps.ownScope(currentScope) && seenCurrentScopes.add(currentScope)) {
            logger.trace("{} (C): scheduling data.", currentScope);
            openCurrentScopes.add(currentScope);
            IFuture<Optional<D>> cd = currentContext.datum(currentScope);
            K<Optional<D>> insertCS = (d, ex) -> {
                if(ex != null) {
                    logger.debug("Error retrieving current data.", ex);
                    d = currentContext.rawDatum(currentScope);
                }
                logger.trace("{} (C): data complete: {}.", currentScope, d);
                currentScopeData.put(currentScope, d);

                Collection<S> dataScopes = d.map(differOps::getScopes).orElse(CapsuleUtil.immutableSet());
                logger.trace("{} (C): scopes observed in datum: {}", currentScope, dataScopes);

                dataScopes.forEach(this::scheduleCurrentData);
                return Unit.unit;
            };
            future(cd, insertCS);
        }
    }

    private void schedulePreviousData(S previousScope) {
        if(differOps.ownScope(previousScope) && seenPreviousScopes.add(previousScope)) {
            logger.trace("{} (P): scheduling data.", previousScope);
            openPreviousScopes.add(previousScope);
            IFuture<Optional<D>> pd = previousContext.datum(previousScope);
            K<Optional<D>> insertPS = (d, ex) -> {
                if(ex != null) {
                    logger.debug("Error retrieving previous data.", ex);
                    d = previousContext.rawDatum(previousScope);
                }
                logger.trace("{} (P): data complete: {}.", previousScope, d);
                previousScopeData.put(previousScope, d);

                Collection<S> dataScopes = d.map(differOps::getScopes).orElse(CapsuleUtil.immutableSet());
                logger.trace("{} (P): scopes observed in datum: {}", previousScope, dataScopes);

                dataScopes.forEach(this::schedulePreviousData);
                return Unit.unit;
            };
            future(pd, insertPS);
        }
    }

    private IFuture<Unit> visitAllEdges(IDifferContext<S, L, D> context, S scope, Action1<Edge<S, L>> visit) {
        final IFuture<Unit> future = aggregateAll(edgeLabels, label -> {
            final IFuture<Iterable<S>> edgesFuture = context.getEdges(scope, label);
            K<Iterable<S>> addEdges = (targets, ex) -> {
                targets.forEach(target -> {
                    visit.apply(new Edge<>(scope, label, target));
                });
                return Unit.unit;
            };
            future(edgesFuture, addEdges);
            return edgesFuture.thenApply(__ -> Unit.unit);
        }).thenApply(__ -> Unit.unit);
        future(future);
        return future;
    }

    private IFuture<Unit> addAllEdges(S currentScope) {
        return visitAllEdges(currentContext, currentScope, this::added);
    }

    private IFuture<Unit> removeAllEdges(S previousScope) {
        return visitAllEdges(previousContext, previousScope, this::removed);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Result processing
    ///////////////////////////////////////////////////////////////////////////

    private void closeCurrentScope(S currentScope) {
        if(!differOps.ownScope(currentScope)) {
            return;
        }

        if(!seenCurrentScopes.contains(currentScope)) {
            throw new IllegalStateException("Closing unobserved scope: " + currentScope);
        }

        if(!matchedScopes.containsKey(currentScope) && !addedScopes.contains(currentScope)) {
            throw new IllegalStateException("Closing scope that is neither matched nor added: " + currentScope);
        }

        logger.trace("{} (C): closed for matching.", currentScope);
        if(!openCurrentScopes.remove(currentScope)) {
            throw new IllegalStateException("Closing scope that is already closed: " + currentScope);
        }
    }

    private void closePreviousScope(S previousScope) {
        if(!differOps.ownScope(previousScope)) {
            return;
        }

        if(!seenPreviousScopes.contains(previousScope)) {
            new IllegalStateException("Closing unobserved scope: " + previousScope);
        }

        if(!matchedScopes.containsValue(previousScope) && !removedScopes.contains(previousScope)) {
            throw new IllegalStateException("Closing scope that is neither matched nor removed: " + previousScope);
        }

        logger.trace("{} (P): closed for matching.", previousScope);
        if(!openPreviousScopes.remove(previousScope)) {
            throw new IllegalStateException("Closing scope that is already closed: " + previousScope);
        }
    }

    private Unit match(S currentScope, S previousScope) {
        if(matchedScopes.containsEntry(currentScope, previousScope)) {
            return Unit.unit;
        }
        assertCurrentScopeOpen(currentScope);
        assertPreviousScopeOpen(previousScope);

        logger.debug("{} ~ {}: matched.", currentScope, previousScope);
        matchedScopes.put(currentScope, previousScope);
        closeCurrentScope(currentScope);
        closePreviousScope(previousScope);

        if(differOps.ownOrSharedScope(currentScope)) {
            // We can only own edges from scopes that we own, or that are shared with us.
            logger.trace("{} ~ {}: scheduling edge matches", currentScope, previousScope);
            for(L lbl : edgeLabels) {
                scheduleEdgeMatches(currentScope, previousScope, lbl);
            }
        }

        logger.trace("{} ~ {}: scheduling scope observations.", currentScope, previousScope);

        // Collect all scopes in data term of current scope
        scheduleCurrentData(currentScope);

        // Collect all scopes in data term of previous scope
        schedulePreviousData(previousScope);
        previousScopeProcessed(previousScope, Optional.of(currentScope));

        return Unit.unit;
    }

    private Unit match(Edge<S, L> current, Edge<S, L> previous) {
        assertCurrentEdgeOpen(current);
        assertPreviousEdgeOpen(previous);

        logger.debug("{} ~ {}: matched.", current, previous);
        matchedEdges.put(current, previous);
        matchedOutgoingEdges.put(Tuple2.of(previous.source, previous.label), previous);

        scheduleCurrentData(current.target);
        schedulePreviousData(previous.target);

        currentEdgeComplete(current);

        return Unit.unit;
    }

    private Unit added(Edge<S, L> edge) {
        assertCurrentEdgeOpen(edge);

        logger.trace("{}: added.", edge);
        addedEdges.put(Tuple2.of(edge.source, edge.label), edge);

        currentEdgeComplete(edge);
        scheduleCurrentData(edge.target);

        return Unit.unit;
    }

    private Unit removed(Edge<S, L> edge) {
        assertPreviousEdgeOpen(edge);

        logger.trace("{}: removed.", edge);
        removedEdges.put(Tuple2.of(edge.source, edge.label), edge);

        schedulePreviousData(edge.target);

        return Unit.unit;
    }

    private Unit added(S currentScope) {
        assertCurrentScopeOpen(currentScope);
        scheduleCurrentData(currentScope);

        logger.trace("{} (C): added.", currentScope);
        addedScopes.add(currentScope);
        closeCurrentScope(currentScope);

        addAllEdges(currentScope);

        return Unit.unit;
    }

    private Unit removed(S previousScope) {
        if(removedScopes.contains(previousScope)) {
            // External scopes might be marked as removed multiple times.
            // Asserting they are open leads to IllegalStateExceptions.
            return Unit.unit;
        }
        assertPreviousScopeOpen(previousScope);
        schedulePreviousData(previousScope);

        logger.trace("{} (P): removed.", previousScope);
        removedScopes.add(previousScope);
        closePreviousScope(previousScope);

        previousScopeProcessed(previousScope, Optional.empty());
        removeAllEdges(previousScope).whenComplete((__, ex) -> {
            if(ex != null) {
                failure(ex);
            }
            for(L lbl : edgeLabels) {
                previousScopeComplete(previousScope, lbl);
            }
        });

        return Unit.unit;
    }

    private Unit queue(EdgeMatch match) {
        logger.trace("Queuing delayed match {}.", match);
        edgeMatches.add(match);

        return Unit.unit;
    }

    private <R> Unit future(IFuture<R> future, K<R> k) {
        RefBool executed = new RefBool(false);
        waitFor(future).handle((r, ex) -> {
            if(executed.get()) {
                throw new IllegalStateException("Continuation cannot be executed multiple times.");
            }
            executed.set(true);
            diffK(k, r, ex);
            return Unit.unit;
        });
        return Unit.unit;
    }

    private Unit future(IFuture<Unit> future) {
        final K<Unit> k = (u, ex) -> {
            if(ex != null) {
                failure(ex);
            }
            return u;
        };
        return future(future, k);
    }

    private void tryFinalizeDiff() {
        // When an edge is added/removed, parts of a scope graph may become disconnected, and delays on those will never fire.
        // Therefore, when there is no opportunity for progress, we should conservatively mark these scopes as added/removed.
        do {
            // External information can provide opportunities for progress.
            if(pendingResults.get() != 0 || !typeCheckerFinished.get()) {
                return;
            }
            ImmutableSet.copyOf(openCurrentScopes).forEach(this::added);
            ImmutableSet.copyOf(openPreviousScopes).forEach(this::removed);
        } while(edgeMatches.isEmpty() && (!openCurrentScopes.isEmpty() || !openPreviousScopes.isEmpty()));
        logger.debug("Marked all open scopes as added/removed.");

        if(!edgeMatches.isEmpty()) {
            return;
        }

        if(openCurrentScopes.size() == 0 && openPreviousScopes.size() == 0 && pendingResults.get() == 0
                && typeCheckerFinished.get() && !result.isDone() && edgeMatches.isEmpty()) {
            logger.debug("Finalizing diff.");
            io.usethesource.capsule.Map.Transient<S, D> addedScopes = CapsuleUtil.transientMap();
            currentScopeData.keySet().retainAll(this.addedScopes);
            currentScopeData.forEach((s, d) -> addedScopes.__put(s, d.orElse(differOps.embed(s))));

            io.usethesource.capsule.Map.Transient<S, D> removedScopes = CapsuleUtil.transientMap();
            previousScopeData.keySet().retainAll(this.removedScopes);
            previousScopeData.forEach((s, d) -> removedScopes.__put(s, d.orElse(differOps.embed(s))));

            io.usethesource.capsule.Set.Transient<Edge<S, L>> addedEdges = CapsuleUtil.transientSet();
            this.addedEdges.asMap().values().forEach(x -> x.forEach(addedEdges::__insert));

            io.usethesource.capsule.Set.Transient<Edge<S, L>> removedEdges = CapsuleUtil.transientSet();
            this.removedEdges.asMap().values().forEach(x -> x.forEach(removedEdges::__insert));

            // Clean up pending delays
            previousScopeProcessedDelays.asMap().forEach((s, delays) -> {
                // logger.error("Pending previous scope processed delays for {}.", s);
                // throw new IllegalStateException("Pending previous scope processed delays for " + s + ".");
                delays.forEach(c -> c.complete(Optional.empty()));
            });
            previousScopeProcessedDelays.clear();

            previousScopeCompletedDelays.asMap().forEach((s, delays) -> {
                // logger.error("Pending previous scope completed delays for {}.", s);
                // throw new IllegalStateException("Pending previous scope completed delays for " + s + ".");
                delays.forEach(c -> c.complete(Unit.unit));
            });
            previousScopeCompletedDelays.clear();

            currentEdgeCompleteDelays.asMap().forEach((edge, delays) -> {
                // logger.error("Pending current edge processed delays for {}.", edge);
                // throw new IllegalStateException("Pending current edge processed delays for " + edge + ".");
                delays.forEach(c -> c.complete(Unit.unit));
            });
            currentEdgeCompleteDelays.clear();

            // @formatter:off
            final ScopeGraphDiff<S, L, D> result = new ScopeGraphDiff<S, L, D>(
                matchedScopes.freeze(),
                matchedEdges.freeze(),
                addedScopes.freeze(),
                addedEdges.freeze(),
                removedScopes.freeze(),
                removedEdges.freeze()
            );
            // @formatter:on
            this.result.complete(result);
        }
    }

    // Queries

    @Override public IFuture<Optional<S>> match(S previousScope) {
        if(!previousContext.available(previousScope)) {
            logger.error("Scope {} is not available in previous context.", previousScope);
            return CompletableFuture.completedExceptionally(
                    new IllegalStateException("Scope " + previousScope + " is not available in previous context."));
        }

        if(matchedScopes.containsValue(previousScope)) {
            final S currentScope = matchedScopes.getValue(previousScope);
            logger.trace("Scope {} match present: {}. Return eagerly.", previousScope, currentScope);
            return CompletableFuture.completedFuture(Optional.of(currentScope));
        }

        if(removedScopes.contains(previousScope)) {
            logger.trace("Scope {} removed. Return eagerly.", previousScope);
            return CompletableFuture.completedFuture(Optional.empty());
        }

        final ICompletableFuture<Optional<S>> result = new CompletableFuture<>();
        if(!completeIfFailure(result)) {
            logger.trace("Scope {} not complete. Delaying return of its match.", previousScope);
            previousScopeProcessedDelays.put(previousScope, result);
            // waitFors.add(ScopeProcessed.of(previousScope));
        }
        return result;
    }

    @Override public IFuture<ScopeDiff<S, L, D>> scopeDiff(S previousScope, L label) {
        if(!previousContext.available(previousScope)) {
            logger.error("Scope {} is not available in previous context.", previousScope);
            return CompletableFuture.completedExceptionally(
                    new IllegalStateException("Scope " + previousScope + " is not available in previous context."));
        }

        if(completedPreviousEdges.containsEntry(previousScope, label)) {
            logger.debug("{}/{} complete, returning scope diff.", previousScope, label);
            return CompletableFuture.completedFuture(buildScopeDiff(previousScope, label));
        }

        final ICompletableFuture<Unit> result = new CompletableFuture<>();
        if(!completeIfFailure(result)) {
            logger.debug("{}/{} not complete, wait before returning scope diff.", previousScope, label);
            // waitFors.add(ScopeCompleted.of(previousScope, label));
            previousScopeCompletedDelays.put(Tuple2.of(previousScope, label), result);
        }

        return result.thenApply(__ -> buildScopeDiff(previousScope, label));
    }

    private ScopeDiff<S, L, D> buildScopeDiff(S previousScope, L label) {
        logger.debug("Building scope diff {}/{}.", previousScope, label);
        final S currentScope = matchedScopes.getValue(previousScope);
        if(currentScope != null) {
            // @formatter:off
            final ScopeDiff<S, L, D> diff = ScopeDiff.of(
                addedEdges.get(Tuple2.of(currentScope, label)),
                matchedOutgoingEdges.get(Tuple2.of(previousScope, label)),
                removedEdges.get(Tuple2.of(previousScope, label))
            );
            logger.trace("Scope diff for {}/{}: {}", previousScope, label, diff);
            return diff;
            // @formatter:on
        }
        return ScopeDiff.<S, L, D>builder().build();
    }

    // Events

    private void previousScopeProcessed(S previousScope, Optional<S> match) {
        logger.trace("{}: PS complete.", previousScope);
        // waitFors.remove(ScopeProcessed.of(previousScope));
        previousScopeProcessedDelays.removeAll(previousScope).forEach(c -> c.complete(match));
        logger.trace("{}: PS completion finished.", previousScope);
    }

    private void previousScopeComplete(S previousScope, L label) {
        logger.trace("{}: PSC complete.", previousScope);
        completedPreviousEdges.put(previousScope, label);
        // waitFors.remove(ScopeCompleted.of(previousScope, label));
        previousScopeCompletedDelays.removeAll(Tuple2.of(previousScope, label)).forEach(c -> c.complete(Unit.unit));
        logger.trace("{}: PSC completion finished.", previousScope);
    }

    private void currentEdgeComplete(Edge<S, L> current) {
        logger.trace("{}: CE complete.", current);
        // waitFors.remove(EdgeCompleted.of(current));
        currentEdgeCompleteDelays.removeAll(current).forEach(c -> c.complete(Unit.unit));
        logger.trace("{}: CE completion finished.", current);
    }

    private void failure(Throwable ex) {
        failure = ex;
        result.completeExceptionally(ex);
        previousScopeProcessedDelays.asMap().forEach((s, delays) -> delays.forEach(d -> {
            d.completeExceptionally(ex);
        }));
        previousScopeCompletedDelays.asMap().forEach((s, delays) -> delays.forEach(d -> {
            d.completeExceptionally(ex);
        }));
        currentEdgeCompleteDelays.asMap().forEach((s, delays) -> delays.forEach(d -> {
            d.completeExceptionally(ex);
        }));
    }

    private boolean completeIfFailure(ICompletableFuture<?> future) {
        if(failure != null) {
            future.completeExceptionally(failure);
            return true;
        }
        return false;
    }

    // Helper methods and classes

    private static <T, R> IFuture<List<R>> aggregateAll(Collection<T> items, Function1<T, IFuture<R>> mapper) {
        final ArrayList<IFuture<R>> futures = new ArrayList<>(items.size());
        for(T item : items) {
            futures.add(mapper.apply(item));
        }
        return AggregateFuture.of(futures);
    }

    private boolean successfullyCompleted() {
        return result.isDone() && failure == null;
    }

    private boolean isCurrentScopeOpen(S scope) {
        return !successfullyCompleted() && !matchedScopes.containsKey(scope) && !addedScopes.contains(scope);
    }

    private boolean isPreviousScopeOpen(S scope) {
        return !successfullyCompleted() && !matchedScopes.containsValue(scope) && !removedScopes.contains(scope);
    }

    private boolean isCurrentEdgeOpen(Edge<S, L> edge) {
        return !successfullyCompleted() && !matchedEdges.containsKey(edge) && !addedEdges.containsValue(edge);
    }

    private boolean isPreviousEdgeOpen(Edge<S, L> edge) {
        return !successfullyCompleted() && !matchedEdges.containsValue(edge) && !removedEdges.containsValue(edge);
    }

    private void assertCurrentScopeOpen(S scope) {
        if(!isCurrentScopeOpen(scope)) {
            final String reason = successfullyCompleted() ? "closed because differ completed"
                    : addedScopes.contains(scope) ? "marked as added" : "matched to " + matchedScopes.getKey(scope);
            throw new IllegalStateException("Scope " + scope + " is already " + reason + ".");
        }
    }

    private void assertPreviousScopeOpen(S scope) {
        if(!isPreviousScopeOpen(scope)) {
            final String reason =
                    successfullyCompleted() ? "closed because differ completed" : removedScopes.contains(scope)
                            ? "marked as removed" : "matched to " + matchedScopes.getValue(scope);
            throw new IllegalStateException("Scope " + scope + " is already " + reason + ".");
        }
    }

    private void assertCurrentEdgeOpen(Edge<S, L> edge) {
        if(!isCurrentEdgeOpen(edge)) {
            final String reason = successfullyCompleted() ? "closed because differ completed"
                    : addedEdges.containsValue(edge) ? "marked as added" : "matched to " + matchedEdges.getKey(edge);
            throw new IllegalStateException("Edge " + edge + " is already " + reason + ".");
        }
    }

    private void assertPreviousEdgeOpen(Edge<S, L> edge) {
        if(!isPreviousEdgeOpen(edge)) {
            final String reason =
                    successfullyCompleted() ? "closed because differ completed" : removedEdges.containsValue(edge)
                            ? "marked as removed" : "matched to " + matchedEdges.getValue(edge);
            throw new IllegalStateException("Edge " + edge + " is already " + reason + ".");
        }
    }

    private class EdgeMatch implements Comparable<EdgeMatch> {

        public final Edge<S, L> currentEdge;
        public final ImmutableMap<Edge<S, L>, BiMap<S, S>> previousEdges;

        public EdgeMatch(Edge<S, L> currentEdge, ImmutableMap<Edge<S, L>, BiMap<S, S>> previousEdges) {
            this.currentEdge = currentEdge;
            this.previousEdges = previousEdges;
        }

        @Override public int compareTo(EdgeMatch that) {
            return this.previousEdges.size() - that.previousEdges.size();
        }

        @Override public String toString() {
            return currentEdge + " ~ " + previousEdges;
        }

    }

    private <U> IFuture<U> waitFor(IFuture<U> future) {
        pendingResults.incrementAndGet();
        return future.whenComplete((__, ex) -> pendingResults.decrementAndGet());
    }

    ///////////////////////////////////////////////////////////////////////////
    // K
    ///////////////////////////////////////////////////////////////////////////

    @FunctionalInterface
    private interface K<R> {
        Unit k(R result, Throwable ex);
    }

    ///////////////////////////////////////////////////////////////////////////
    // Tokens
    ///////////////////////////////////////////////////////////////////////////

    private interface IToken<S, L> {

    }

    private static class ScopeProcessed<S, L> implements IToken<S, L> {

        private final S previousScope;

        private ScopeProcessed(S previousScope) {
            this.previousScope = previousScope;
        }

        @SuppressWarnings("unused") public static <S, L> ScopeProcessed<S, L> of(S previousScope) {
            return new ScopeProcessed<>(previousScope);
        }

        @Override public String toString() {
            return "ScopeProcessed{" + previousScope + "}";
        }

        @Override public boolean equals(Object obj) {
            if(obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            @SuppressWarnings("unchecked") final ScopeProcessed<S, L> other = (ScopeProcessed<S, L>) obj;
            return other.previousScope.equals(previousScope);
        }

        @Override public int hashCode() {
            return previousScope.hashCode();
        }

    }

    private static class ScopeCompleted<S, L> implements IToken<S, L> {

        private final S previousScope;
        private final L label;

        private ScopeCompleted(S previousScope, L label) {
            this.previousScope = previousScope;
            this.label = label;
        }

        @SuppressWarnings("unused") public static <S, L> ScopeCompleted<S, L> of(S previousScope, L label) {
            return new ScopeCompleted<>(previousScope, label);
        }

        @Override public String toString() {
            return "ScopeCompleted{" + previousScope + "/" + label + "}";
        }

        @Override public boolean equals(Object obj) {
            if(obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            @SuppressWarnings("unchecked") final ScopeCompleted<S, L> other = (ScopeCompleted<S, L>) obj;
            return other.previousScope.equals(previousScope) && other.label.equals(label);
        }

        @Override public int hashCode() {
            return previousScope.hashCode();
        }

    }

    private static class EdgeCompleted<S, L> implements IToken<S, L> {

        private final Edge<S, L> currentEdge;

        private EdgeCompleted(Edge<S, L> currentEdge) {
            this.currentEdge = currentEdge;
        }

        @SuppressWarnings("unused") public static <S, L> EdgeCompleted<S, L> of(Edge<S, L> currentEdge) {
            return new EdgeCompleted<>(currentEdge);
        }

        @Override public String toString() {
            return "EdgeCompleted{" + currentEdge + "}";
        }

        @Override public boolean equals(Object obj) {
            if(obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            @SuppressWarnings("unchecked") final EdgeCompleted<S, L> other = (EdgeCompleted<S, L>) obj;
            return other.currentEdge.equals(currentEdge);
        }

        @Override public int hashCode() {
            return currentEdge.hashCode();
        }

    }

}
