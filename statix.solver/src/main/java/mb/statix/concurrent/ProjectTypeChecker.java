package mb.statix.concurrent;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.metaborg.util.future.AggregateFuture;
import org.metaborg.util.future.IFuture;
import org.metaborg.util.log.ILogger;
import org.metaborg.util.log.LoggerUtils;
import org.metaborg.util.unit.Unit;

import mb.nabl2.terms.ITerm;
import mb.p_raffrayi.IUnitResult;
import mb.statix.scopegraph.Scope;
import mb.statix.solver.log.IDebugContext;
import mb.statix.spec.Spec;
import mb.p_raffrayi.IIncrementalTypeCheckerContext;
import mb.p_raffrayi.impl.IInitialState;

public class ProjectTypeChecker extends AbstractTypeChecker<ProjectResult> {

    private static final ILogger logger = LoggerUtils.logger(ProjectTypeChecker.class);

    private final IStatixProject project;

    public ProjectTypeChecker(IStatixProject project, Spec spec, IDebugContext debug) {
        super(spec, debug);
        this.project = project;
    }

    @Override public IFuture<ProjectResult> run(IIncrementalTypeCheckerContext<Scope, ITerm, ITerm, ProjectResult> context,
            @SuppressWarnings("unused") List<Scope> rootScopes, IInitialState<Scope, ITerm, ITerm, ProjectResult> initialState) {
        final Scope projectScope = makeSharedScope(context, "s_prj");

        final IFuture<Map<String, IUnitResult<Scope, ITerm, ITerm, Unit>>> libraryResults =
            runLibraries(context, project.libraries(), projectScope);

        final IFuture<Map<String, IUnitResult<Scope, ITerm, ITerm, GroupResult>>> groupResults =
            runGroups(context, project.groups(), projectScope, initialState);

        final IFuture<Map<String, IUnitResult<Scope, ITerm, ITerm, UnitResult>>> unitResults =
            runUnits(context, project.units(), projectScope, initialState);

        context.closeScope(projectScope);

        // @formatter:off
        return context.runIncremental(
            restarted -> {
                return runSolver(context, project.rule(), Arrays.asList(projectScope));
            },
            ProjectResult::solveResult,
            this::patch,
            (result, ex) -> {
                return AggregateFuture.apply(libraryResults, groupResults, unitResults).thenApply(e -> {
                    return ProjectResult.of(project.resource(), e._1(), e._2(), e._3(), result, ex);
                });
            })
            .whenComplete((r, __) -> {
                logger.debug("project {}: returned.", context.id());
            });
        // @formatter:on
    }
}