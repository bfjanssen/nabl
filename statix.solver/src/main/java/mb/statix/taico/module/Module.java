package mb.statix.taico.module;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import mb.nabl2.terms.ITerm;
import mb.statix.solver.IConstraint;
import mb.statix.solver.constraint.CResolveQuery;
import mb.statix.spec.Spec;
import mb.statix.taico.scopegraph.IMInternalScopeGraph;
import mb.statix.taico.scopegraph.IOwnableScope;
import mb.statix.taico.scopegraph.IOwnableTerm;
import mb.statix.taico.scopegraph.ModuleScopeGraph;
import mb.statix.taico.solver.MState;
import mb.statix.taico.solver.SolverContext;
import mb.statix.taico.solver.context.AContextAware;
import mb.statix.taico.solver.query.QueryDetails;

/**
 * Basic implementation of {@link IModule}. The identifiers are not automatically generated.
 */
//TODO This would be a StatixModule or SGModule
public class Module extends AContextAware implements IModule {
    private static final long serialVersionUID = 1L;
    
    private final String name;
    private String parentId;
    private IMInternalScopeGraph<IOwnableTerm, ITerm, ITerm, ITerm> scopeGraph;
    private Map<CResolveQuery, QueryDetails<IOwnableTerm, ITerm, ITerm>> queries = new HashMap<>();
    private Map<IModule, CResolveQuery> dependants = new HashMap<>();
    private ModuleCleanliness cleanliness = ModuleCleanliness.NEW;
    private IConstraint initialization;
    
    /**
     * Creates a new top level module.
     * 
     * @param context
     *      the solver context
     * @param name
     *      the name of the module
     * @param labels
     *      the labels on edges of the scope graph
     * @param endOfPath
     *      the label that indicates the end of a path
     * @param relations
     *      the labels on data edges of the scope graph
     */
    public Module(SolverContext context, String name, Iterable<ITerm> labels, ITerm endOfPath, Iterable<ITerm> relations) {
        super(context);
        this.name = name;
        this.scopeGraph = new ModuleScopeGraph(this, labels, endOfPath, relations, Collections.emptyList());
        context.addModule(this);
    }
    
    /**
     * Creates a new top level module.
     * 
     * @param context
     *      the solver context
     * @param name
     *      the name of the module
     * @param spec
     *      the spec
     */
    public Module(SolverContext context, String name, Spec spec) {
        super(context);
        this.name = name;
        this.scopeGraph = new ModuleScopeGraph(this, spec.labels(), spec.endOfPath(), spec.relations().keySet(), Collections.emptyList());
        context.addModule(this);
    }
    
    /**
     * Constructor for creating child modules.
     * 
     * @param context
     *      the solver context
     * @param name
     *      the name of the child
     * @param parent
     *      the parent module
     */
    private Module(SolverContext context, String name, IModule parent) {
        super(context);
        this.name = name;
        this.parentId = parent == null ? null : parent.getId();
        context.addModule(this);
    }

    @Override
    public String getName() {
        return name;
    }
    
    @Override
    public String getId() {
        return parentId == null ? name : ModulePaths.build(parentId, name);
    }
    
    @Override
    public IModule getParent() {
        System.err.println("Getting parent on module " + this);
        return parentId == null ? null : context.getModuleUnchecked(parentId);
    }
    
    @Override
    public void setParent(IModule module) {
        //TODO Because of how the parent system currently works, we cannot hang modules under different parents.
        //     This is because we currently do not transitively update the module ids in our children as well.
        this.parentId = module == null ? null : module.getId();
    }

    @Override
    public IMInternalScopeGraph<IOwnableTerm,  ITerm, ITerm, ITerm> getScopeGraph() {
        return scopeGraph;
    }

    @Override
    public synchronized Module createChild(String name, List<IOwnableScope> canExtend, IConstraint constraint) {
        Module child = new Module(context, name, this);
        child.setInitialization(constraint);
        child.scopeGraph = scopeGraph.createChild(child, canExtend);
        return child;
    }
    
    @Override
    public IConstraint getInitialization() {
        return initialization;
    }
    
    @Override
    public void setInitialization(IConstraint constraint) {
        this.initialization = constraint;
    }
    
    
//    @Override
//    public Module copy(ModuleManager newManager, IModule newParent, List<IOwnableScope> newScopes) {
//        //TODO This needs to be changed. We might need to record the old version, to do comparisons against.
//        //TODO We also cannot instantiate our children yet. The mechanism needs to be different, based around
//        //TODO lookups OR creations.
//        Module copy = new Module(newManager, id, newParent);
//        copy.flag(ModuleCleanliness.CLIRTY);
//        copy.scopeGraph = scopeGraph.recreate(newScopes);
//        
//        //TODO We cannot really copy the children properly
//        for (IModule child : children) {
//            IModule childCopy = child.copy(newManager, newParent);
//        }
//        return copy;
//    }
    
    @Override
    public Set<IModule> getDependencies() {
        return queries.values().stream().flatMap(d -> d.getReachedModules().stream()).collect(Collectors.toSet());
    }
    
    @Override
    public void addQuery(CResolveQuery query, QueryDetails<IOwnableTerm, ITerm, ITerm> details) {
        queries.put(query, details);
    }
    
    @Override
    public void addDependant(IModule module, CResolveQuery query) {
        dependants.put(module, query);
    }
    
    @Override
    public Map<IModule, CResolveQuery> getDependants() {
        return dependants;
    }
    
    @Override
    public void flag(ModuleCleanliness cleanliness) {
        this.cleanliness = cleanliness;
    }
    
    @Override
    public ModuleCleanliness getFlag() {
        return cleanliness;
    }
    
    @Override
    public void reset(Spec spec) {
        this.scopeGraph = new ModuleScopeGraph(this, scopeGraph.getLabels(), scopeGraph.getEndOfPath(), scopeGraph.getRelations(), scopeGraph.getParentScopes());
        this.queries = new HashMap<>();
        this.dependants = new HashMap<>();
        this.cleanliness = ModuleCleanliness.NEW;
        new MState(context, this, spec);
        context.addModule(this);
    }
    
    // --------------------------------------------------------------------------------------------
    // Object methods
    // --------------------------------------------------------------------------------------------
    
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof Module)) return false;
        assert !this.getId().equals(((Module) obj).getId()) : "Module identifiers are equal but modules are not the same instance! (id: " + getId() + ")";
        return this.getId().equals(((Module) obj).getId());
    }
    
    @Override
    public int hashCode() {
        return name.hashCode() + (parentId == null ? 0 : (31 * parentId.hashCode()));
    }
    
    @Override
    public String toString() {
        return "@" + getId();
    }
}
